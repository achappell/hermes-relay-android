package com.achappell.hermesrelay

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Visible Android transport-recovery state for the active Hermes Session. */
internal sealed interface AndroidConnectionState {
    data object Connected : AndroidConnectionState

    data object Disconnected : AndroidConnectionState

    data class Reconnecting(val attempt: Int, val of: Int) : AndroidConnectionState

    data class Failed(val reason: String) : AndroidConnectionState
}

/**
 * A turn that lost transport before a terminal event was observed.
 *
 * The Android Client cannot prove whether Hermes received or answered it, so
 * the request is retained for an explicit user decision and never resent on
 * the Client's own initiative.
 */
internal data class AndroidUnconfirmedTurn(
    /** Null when prompt delivery became uncertain before Home returned a turn ID. */
    val binding: AndroidTurnBinding?,
    val request: AndroidTurnRequest,
)

internal data class AndroidRecoveryState(
    // Disconnected until a handshake actually succeeds. Defaulting to Connected
    // would claim a Session before one exists.
    val connection: AndroidConnectionState = AndroidConnectionState.Disconnected,
    val connectionId: String? = null,
    val unconfirmedTurn: AndroidUnconfirmedTurn? = null,
    val unresolvedHomeTurn: Boolean = false,
    val resumedTurnBinding: AndroidTurnBinding? = null,
    val isRecovering: Boolean = false,
) {
    /** Compatibility alias; the value is a local bridge connection identity. */
    @Deprecated("Use connectionId; this value is not a Hermes Session ID.")
    val sessionId: String?
        get() = connectionId

    val hasUnconfirmedTurn: Boolean
        get() = unconfirmedTurn != null
}

/** Typed reconnect result owned by the adapter, not by Compose. */
internal sealed interface AndroidReconnectOutcome {
    /** Home authenticated and reopened the existing opaque conversation. */
    data class Connected(
        val connectionId: String,
        val route: AndroidRoute? = null,
        val capabilities: AndroidHomeCapabilities = AndroidHomeCapabilities(),
        val unresolvedTurn: Boolean = false,
        val unresolvedTurnId: String? = null,
        val unresolvedTurnBinding: AndroidTurnBinding? = null,
        val unresolvedTurnWasBoolean: Boolean = false,
    ) : AndroidReconnectOutcome {
        /** Compatibility alias for pre-Home fakes; never a Hermes Session ID. */
        @Deprecated("Use connectionId; this value is local to the bridge connection.")
        val sessionId: String
            get() = connectionId
    }

    /** Transient loss; the bounded ladder may try again. */
    data class Retryable(
        val reason: String,
        val reasonCode: AndroidHomeUnavailableReason? = null,
    ) : AndroidReconnectOutcome

    /** Configuration or authorization failure; retrying cannot help. */
    data class Unrecoverable(
        val reason: String,
        val reasonCode: AndroidHomeUnavailableReason? = null,
        /** A claim request whose response was lost: the ladder must never send a second one. */
        val ambiguousClaim: Boolean = false,
    ) : AndroidReconnectOutcome
}

internal sealed interface AndroidResendResult {
    data class Sent(val binding: AndroidTurnBinding) : AndroidResendResult

    data class Rejected(val reason: AndroidInitiationFailure) : AndroidResendResult

    data class Uncertain(val reason: AndroidHomeUnavailableReason) : AndroidResendResult

    /** No unconfirmed turn is retained, so nothing may be resent. */
    data object NothingToResend : AndroidResendResult

    /** Transport is not connected, so a resend cannot be attempted yet. */
    data object NotConnected : AndroidResendResult
}

/**
 * Owns Android's bounded reconnect ladder and the no-automatic-replay rule.
 *
 * The controller is pure with respect to transport: the adapter performs the
 * reconnect and the resend, while this class owns visible recovery state and
 * the guarantee that an uncertain turn is resent only by explicit user action,
 * exactly once.
 */
internal class AndroidRecoveryController(
    private val clientPort: AndroidClientPort,
    private val policy: ReconnectPolicy = ReconnectPolicy.default,
    private val clock: MonotonicClock = MonotonicClock.Real,
    private val pacer: RecoveryPacer = LockRecoveryPacer(),
    private val journal: DiagnosticsJournal = DiagnosticsJournal.None,
    private val onStateChange: (AndroidRecoveryState) -> Unit = {},
) {
    private val ladderGeneration = AtomicLong(0)
    private val liveLadder = AtomicLong(NO_LADDER)

    var state: AndroidRecoveryState = AndroidRecoveryState()
        private set(value) {
            val changed = field != value
            field = value
            if (changed) {
                onStateChange(value)
            }
        }

    /**
     * Record an unexpected transport loss.
     *
     * A turn still in flight becomes the retained unconfirmed turn. A loss
     * reported while a ladder is already running does not start a second one
     * and does not reset its attempt counter.
     */
    fun transportLost(
        reason: String,
        inFlightTurn: AndroidUnconfirmedTurn? = null,
    ): AndroidRecoveryState {
        val retained = state.unconfirmedTurn ?: inFlightTurn
        state = if (state.isRecovering) {
            state.copy(
                connectionId = null,
                unresolvedHomeTurn = false,
                resumedTurnBinding = null,
                unconfirmedTurn = retained,
            )
        } else {
            state.copy(
                connection = AndroidConnectionState.Disconnected,
                connectionId = null,
                unresolvedHomeTurn = false,
                resumedTurnBinding = null,
                unconfirmedTurn = retained,
            )
        }
        return state
    }

    /**
     * The user ended the session on purpose (`ANDROID-HOME-12`). The state is
     * Disconnected and nothing reconnects it; an unconfirmed turn is kept so
     * it is still offered after the user connects again, and never replayed.
     */
    fun disconnectDeliberately(): AndroidRecoveryState {
        state = state.copy(
            connection = AndroidConnectionState.Disconnected,
            connectionId = null,
            unresolvedHomeTurn = false,
            resumedTurnBinding = null,
            isRecovering = false,
        )
        return state
    }

    /**
     * The app stopped and its transport was closed (`ANDROID-HOME-06`). A live
     * or pending state becomes Disconnected at once; a reason already shown
     * and an unconfirmed turn are kept.
     */
    fun disconnectForLifecycle(): AndroidRecoveryState {
        if (state.connection is AndroidConnectionState.Failed) return state
        state = state.copy(
            connection = AndroidConnectionState.Disconnected,
            connectionId = null,
            unresolvedHomeTurn = false,
            resumedTurnBinding = null,
            isRecovering = false,
        )
        return state
    }

    /**
     * Stop the running ladder (and so any wait between attempts) at its next
     * checkpoint; it leaves the state Disconnected rather than Reconnecting.
     * Safe from any thread, a no-op when no ladder runs.
     */
    fun cancelRecovery() {
        ladderGeneration.incrementAndGet()
        pacer.wake()
    }

    /** Skip the rest of the current backoff wait and attempt at once (the network returned). */
    fun retryNow() = pacer.wake()

    /** True while a ladder runs that nothing has cancelled. */
    val isLadderLive: Boolean
        get() = liveLadder.get() == ladderGeneration.get()

    /**
     * Run the paced reconnect ladder (`ANDROID-HOME-04`).
     *
     * The first attempt runs at once and each retry waits out its
     * [ReconnectPolicy] delay; a transport failure continues the ladder until
     * Connected, the overall deadline, a non-retryable reason or
     * [cancelRecovery]. A
     * successful reconnect adopts a fresh local bridge connection identity.
     * No retained turn is submitted here under any outcome.
     */
    fun recover(): AndroidRecoveryState {
        if (state.isRecovering) {
            return state
        }

        val ladder = ladderGeneration.get()
        liveLadder.set(ladder)
        pacer.reset()
        val startedAt = clock.nanoTime()
        val maxAttempts = policy.maxAttempts
        var lastReason = "Hermes Session transport was lost."

        fun cancelled() = ladderGeneration.get() != ladder

        fun stopped(attempt: Int): AndroidRecoveryState {
            liveLadder.compareAndSet(ladder, NO_LADDER)
            journal.record("home connect retry cancelled attempt=$attempt")
            state = state.copy(connection = AndroidConnectionState.Disconnected, isRecovering = false)
            return state
        }

        fun failed(reason: String): AndroidRecoveryState {
            liveLadder.compareAndSet(ladder, NO_LADDER)
            state = state.copy(connection = AndroidConnectionState.Failed(reason), isRecovering = false)
            return state
        }

        var attempt = 0
        while (attempt < maxAttempts) {
            attempt += 1
            state = state.copy(
                connection = AndroidConnectionState.Reconnecting(attempt, maxAttempts),
                isRecovering = true,
            )

            policy.delayBefore(attempt)?.let { delay ->
                journal.record("home connect retry scheduled attempt=$attempt delay_ms=$delay")
                try {
                    pacer.pause(delay)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return stopped(attempt)
                }
                if (cancelled()) return stopped(attempt)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedAt)
                if (elapsedMillis >= policy.overallMillis) return failed(lastReason)
            }

            val elapsedBeforeAttempt = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedAt)
            if (elapsedBeforeAttempt >= policy.overallMillis) return failed(lastReason)
            val attemptBudget = minOf(policy.attemptMillis, policy.overallMillis - elapsedBeforeAttempt)
            when (val outcome = clientPort.reconnect(attemptBudget)) {
                is AndroidReconnectOutcome.Connected -> {
                    liveLadder.compareAndSet(ladder, NO_LADDER)
                    state = state.copy(
                        connection = AndroidConnectionState.Connected,
                        connectionId = outcome.connectionId,
                        unresolvedHomeTurn = outcome.unresolvedTurn,
                        resumedTurnBinding = outcome.unresolvedTurnBinding,
                        unconfirmedTurn = if (outcome.unresolvedTurnBinding != null) {
                            null
                        } else {
                            state.unconfirmedTurn
                        },
                        isRecovering = false,
                    )
                    return state
                }

                is AndroidReconnectOutcome.Retryable -> {
                    lastReason = outcome.reason
                    if (cancelled()) return stopped(attempt)
                    if (TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedAt) >= policy.overallMillis) {
                        return failed(lastReason)
                    }
                }

                is AndroidReconnectOutcome.Unrecoverable -> {
                    if (cancelled()) return stopped(attempt)
                    if (!outcome.isTransportFailure() || outcome.ambiguousClaim) return failed(outcome.reason)
                    lastReason = outcome.reason
                    if (TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startedAt) >= policy.overallMillis) {
                        return failed(lastReason)
                    }
                }
            }
        }

        return failed(lastReason)
    }

    /**
     * Resend the retained uncertain turn after an explicit user action.
     *
     * The marker is cleared only when the adapter accepts the request, so an
     * accepted resend cannot be repeated and a rejected one is not retried
     * automatically.
     */
    fun resendUnconfirmedTurn(): AndroidResendResult {
        val unconfirmed = state.unconfirmedTurn ?: return AndroidResendResult.NothingToResend
        if (state.connection != AndroidConnectionState.Connected) {
            return AndroidResendResult.NotConnected
        }

        clientPort.prepareForExplicitResend()
        return when (val result = clientPort.beginTurn(unconfirmed.request)) {
            is AndroidInitiationResult.Accepted -> {
                state = state.copy(unconfirmedTurn = null)
                AndroidResendResult.Sent(result.binding)
            }

            is AndroidInitiationResult.Rejected -> AndroidResendResult.Rejected(result.reason)
            is AndroidInitiationResult.Uncertain -> AndroidResendResult.Uncertain(result.reason)
        }
    }

    /** Drop the retained turn without sending it. */
    fun discardUnconfirmedTurn(): AndroidRecoveryState {
        clientPort.prepareForExplicitResend()
        state = state.copy(unconfirmedTurn = null)
        return state
    }

    /** Clear Home-owned turn state after its terminal event reaches the client. */
    fun resolveHomeTurn(): AndroidRecoveryState {
        if (state.unresolvedHomeTurn || state.resumedTurnBinding != null) {
            state = state.copy(
                unresolvedHomeTurn = false,
                resumedTurnBinding = null,
            )
        }
        return state
    }

    /** A refused-by-the-network outcome the paced ladder retries; anything else ends it. */
    private fun AndroidReconnectOutcome.Unrecoverable.isTransportFailure() =
        reasonCode == AndroidHomeUnavailableReason.TransportUnavailable ||
            reasonCode == AndroidHomeUnavailableReason.TransportTimeout

    private companion object {
        const val NO_LADDER = -1L
    }
}
