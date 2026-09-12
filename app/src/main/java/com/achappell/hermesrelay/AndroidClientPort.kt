package com.achappell.hermesrelay

import androidx.annotation.StringRes

internal data class AndroidClientSnapshot(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val boundaryRes: Int,
    val selectedProfile: AndroidProfile? = null,
    val authorizationState: AndroidAuthorizationState = AndroidAuthorizationState.NotConfigured,
)

internal data class AndroidProfile(
    val id: String,
    val displayName: String,
)

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
    val sessionId: String,
    val turnId: String,
)

internal enum class AndroidInitiationFailure {
    ProfileUnavailable,
    AuthorizationRequired,
    EmptyTypedPrompt,
    SessionUnavailable,
}

internal sealed interface AndroidInitiationResult {
    data class Accepted(val binding: AndroidTurnBinding) : AndroidInitiationResult

    data class Rejected(val reason: AndroidInitiationFailure) : AndroidInitiationResult
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

    /**
     * Attempt one reconnect and negotiate a fresh Session.
     *
     * The adapter owns backoff timing, credentials, and transport teardown. A
     * successful reconnect never resumes the prior Session and never resends a
     * turn on the Client's behalf.
     */
    fun reconnect(): AndroidReconnectOutcome =
        AndroidReconnectOutcome.Unrecoverable("Hermes Session transport is not configured.")
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
