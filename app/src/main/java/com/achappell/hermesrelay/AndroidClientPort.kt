package com.achappell.hermesrelay

import androidx.annotation.StringRes

internal data class AndroidClientSnapshot(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val boundaryRes: Int,
    val selectedProfile: AndroidProfile? = null,
    val authorizationState: AndroidAuthorizationState = AndroidAuthorizationState.NotConfigured,
    val route: AndroidRoute? = null,
    val capabilities: AndroidHomeCapabilities = AndroidHomeCapabilities(),
    val unavailableReason: AndroidHomeUnavailableReason? = null,
)

internal data class AndroidProfile(
    val id: String,
    val displayName: String,
    val deviceLabel: String? = null,
)

/** Safe route identity returned by Home after the bridge is ready. */
internal data class AndroidRoute(
    val routeClass: String,
    val routeId: String,
)

/** Capabilities are safe facts from Home, never guesses from credential presence. */
internal data class AndroidHomeCapabilities(
    val heartbeat: Boolean = false,
    val timing: String = "absent",
    val commands: Set<String> = emptySet(),
    val interrupt: Boolean = false,
    val audio: Boolean = false,
)

internal enum class AndroidHomeUnavailableReason {
    MissingBinding,
    InvalidBinding,
    InvalidCredential,
    SecureStorageUnavailable,
    AuthorizationUnavailable,
    Unauthorized,
    StaleConversation,
    ReconnectRequired,
    ConversationMismatch,
    HermesTimeout,
    HermesUnavailable,
    Home404,
    RequestRejected,
    TransportUnavailable,
    TransportTimeout,
    ProtocolError,
    CapabilityUnavailable,
    UnresolvedTurn,
    CapabilityShapeInvalid,
    ReconnectTrace,
}

/** Capability that a live proof branch must see before it can create a turn. */
internal enum class LiveHomeCapability {
    Audio,
    Interrupt,
}

/** Safe result of applying the stricter live-gate rules to ordinary recovery. */
internal data class LiveHomeReadinessResult(
    val connected: AndroidReconnectOutcome.Connected? = null,
    val reason: AndroidHomeUnavailableReason? = null,
    val preservesUnresolvedTurn: Boolean = false,
) {
    val accepted: Boolean
        get() = connected != null && reason == null
}

/**
 * Live evidence is stricter than ordinary reconnect.  The ordinary adapter
 * still exposes an unresolved connection so the product can show recovery;
 * these helpers decide whether a particular proof branch may proceed.
 */
internal object LiveHomeReadiness {
    fun assertNewTurnReadiness(outcome: AndroidReconnectOutcome): LiveHomeReadinessResult =
        when (outcome) {
            is AndroidReconnectOutcome.Connected -> when {
                !outcome.unresolvedTurnWasBoolean -> LiveHomeReadinessResult(
                    reason = AndroidHomeUnavailableReason.ProtocolError,
                )
                outcome.unresolvedTurn -> LiveHomeReadinessResult(
                    reason = AndroidHomeUnavailableReason.UnresolvedTurn,
                )
                else -> LiveHomeReadinessResult(connected = outcome)
            }

            is AndroidReconnectOutcome.Retryable -> LiveHomeReadinessResult(
                reason = outcome.reasonCode ?: AndroidHomeUnavailableReason.TransportUnavailable,
            )

            is AndroidReconnectOutcome.Unrecoverable -> LiveHomeReadinessResult(
                reason = outcome.reasonCode ?: AndroidHomeUnavailableReason.ProtocolError,
            )
        }

    fun assertReconnectReadiness(outcome: AndroidReconnectOutcome): LiveHomeReadinessResult =
        when (outcome) {
            is AndroidReconnectOutcome.Connected -> when {
                !outcome.unresolvedTurnWasBoolean && outcome.unresolvedTurnBinding == null -> LiveHomeReadinessResult(
                    reason = AndroidHomeUnavailableReason.ProtocolError,
                )
                outcome.unresolvedTurn -> LiveHomeReadinessResult(
                    connected = outcome,
                    preservesUnresolvedTurn = true,
                )
                else -> LiveHomeReadinessResult(
                    reason = AndroidHomeUnavailableReason.ReconnectTrace,
                )
            }

            is AndroidReconnectOutcome.Retryable -> LiveHomeReadinessResult(
                reason = outcome.reasonCode ?: AndroidHomeUnavailableReason.ReconnectTrace,
            )

            is AndroidReconnectOutcome.Unrecoverable -> LiveHomeReadinessResult(
                reason = outcome.reasonCode ?: AndroidHomeUnavailableReason.ProtocolError,
            )
        }

    fun assertLiveHomeCapabilities(
        outcome: AndroidReconnectOutcome.Connected,
        required: LiveHomeCapability,
    ): LiveHomeReadinessResult {
        if (!outcome.unresolvedTurnWasBoolean) {
            return LiveHomeReadinessResult(reason = AndroidHomeUnavailableReason.ProtocolError)
        }
        val supported = when (required) {
            LiveHomeCapability.Audio -> outcome.capabilities.audio
            LiveHomeCapability.Interrupt -> outcome.capabilities.interrupt
        }
        return if (supported) {
            LiveHomeReadinessResult(
                connected = outcome,
                preservesUnresolvedTurn = outcome.unresolvedTurn,
            )
        } else {
            LiveHomeReadinessResult(reason = AndroidHomeUnavailableReason.CapabilityUnavailable)
        }
    }
}

internal enum class AndroidAuthorizationState {
    NotConfigured,
    Verifying,
    Verified,
    Unavailable,
}

internal sealed interface AndroidTurnInput {
    data class Typed(val text: String) : AndroidTurnInput

    data object TapToSpeak : AndroidTurnInput
}

internal data class AndroidTurnRequest(
    val profile: AndroidProfile,
    val input: AndroidTurnInput,
)

/** The verified identity that must own an accepted Android turn. */
internal data class AndroidTurnBinding(
    val profileId: String,
    val conversationHandle: String,
    val connectionId: String,
    val turnId: String,
) {
    /** Compatibility alias for the pre-Home test seam; this is never a Hermes Session ID. */
    @Deprecated("Use connectionId; this value is local to the Android bridge connection.")
    val sessionId: String
        get() = connectionId

    /** Compatibility constructor for existing surface fakes and fixtures. */
    constructor(profileId: String, sessionId: String, turnId: String) : this(
        profileId = profileId,
        conversationHandle = sessionId,
        connectionId = sessionId,
        turnId = turnId,
    )
}

internal enum class AndroidInitiationFailure {
    ProfileUnavailable,
    AuthorizationRequired,
    EmptyTypedPrompt,
    SessionUnavailable,
    HomeBindingUnavailable,
    RequestRejected,
    DeliveryUncertain,
}

internal sealed interface AndroidInitiationResult {
    data class Accepted(val binding: AndroidTurnBinding) : AndroidInitiationResult

    data class Rejected(val reason: AndroidInitiationFailure) : AndroidInitiationResult

    /** The request may have reached Home, so it must never be replayed implicitly. */
    data class Uncertain(
        val request: AndroidTurnRequest,
        val reason: AndroidHomeUnavailableReason,
    ) : AndroidInitiationResult
}

/** Typed seam for future Hermes session and device adapters.
 *
 * The adapter owns authorization, session identity, transport, and any
 * platform capture work. The Android UI receives a typed result and never
 * parses Hermes frames or manufactures a response.
 */
internal interface AndroidClientPort {
    fun snapshot(): AndroidClientSnapshot

    fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult

    /**
     * Observe normalized lifecycle events for one accepted turn.
     *
     * The adapter owns Hermes frame normalization, audio delivery, and
     * transport lifetime. The Android UI receives typed events only.
     */
    fun observeTurn(
        binding: AndroidTurnBinding,
        onEvent: (AndroidNormalizedEvent) -> Unit,
    ): AndroidTurnObservation = AndroidTurnObservation {}

    /** Observe transport loss even when no turn is currently active. */
    fun observeConnection(
        onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
    ): AndroidTurnObservation = AndroidTurnObservation {}

    /**
     * Attempt one reconnect and re-open the same opaque Home conversation.
     *
     * The adapter owns backoff timing, credentials, and transport teardown. A
     * successful reconnect never resends a prompt or replays an old response.
     */
    fun reconnect(): AndroidReconnectOutcome =
        AndroidReconnectOutcome.Unrecoverable(
            "Home bridge transport is not configured.",
            AndroidHomeUnavailableReason.TransportUnavailable,
        )

    /**
     * Whether the connected relay advertised the `interrupt` capability.
     *
     * The affordance is offered only when the relay says it supports it,
     * rather than presenting a control that might silently do nothing.
     */
    fun supportsInterrupt(): Boolean = false

    /** Asks the relay to stop the named turn. Returns false if not sent. */
    fun interruptTurn(binding: AndroidTurnBinding): Boolean = false

    /** True while Home still owns an accepted turn, including audio fallback. */
    fun hasActiveTurn(): Boolean = false

    /** Clears a client-side uncertain-delivery guard after an explicit user action. */
    fun prepareForExplicitResend() = Unit

    /** Lifecycle owner calls this when the screen leaves the active lifecycle. */
    fun close() = Unit
}

fun interface AndroidTurnObservation {
    fun cancel()
}

internal object BootstrapClientPort : AndroidClientPort {
    override fun snapshot() = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
    )

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
        AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
}
