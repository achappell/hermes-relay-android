package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecoveryControllerTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("Check the weather"))
    private val lostBinding = AndroidTurnBinding(profile.id, "session-1", "turn-1")
    private val unconfirmed = AndroidUnconfirmedTurn(lostBinding, request)

    @Test
    fun bounded_ladder_reports_progress_and_restores_a_fresh_session() {
        val port = FakeRecoveryPort(
            outcomes = listOf(
                AndroidReconnectOutcome.Retryable("Network unavailable."),
                AndroidReconnectOutcome.Connected("session-2"),
            ),
        )
        val controller = AndroidRecoveryController(port, maxAttempts = 3) { changed ->
            port.observedProgress += changed.connection
        }

        controller.transportLost("Transport closed.")
        val state = controller.recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        assertEquals("session-2", state.sessionId)
        assertEquals(2, port.reconnectAttempts)
        // The controller starts Disconnected, so the loss itself changes
        // nothing; visible progress begins with the first attempt.
        assertEquals(
            listOf(
                AndroidConnectionState.Reconnecting(1, 3),
                AndroidConnectionState.Reconnecting(2, 3),
                AndroidConnectionState.Connected,
            ),
            port.observedProgress,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun exhausted_ladder_fails_visibly_and_a_later_manual_attempt_still_succeeds() {
        val port = FakeRecoveryPort(
            outcomes = listOf(
                AndroidReconnectOutcome.Retryable("Timed out."),
                AndroidReconnectOutcome.Retryable("Timed out."),
                AndroidReconnectOutcome.Retryable("Timed out."),
                AndroidReconnectOutcome.Connected("session-2"),
            ),
        )
        val controller = AndroidRecoveryController(port, maxAttempts = 3)

        controller.transportLost("Transport closed.")
        val failed = controller.recover()

        assertEquals(AndroidConnectionState.Failed("Timed out."), failed.connection)
        assertEquals(3, port.reconnectAttempts)

        val recovered = controller.recover()

        assertEquals(AndroidConnectionState.Connected, recovered.connection)
        assertEquals("session-2", recovered.sessionId)
    }

    @Test
    fun an_unrecoverable_failure_abandons_the_ladder_immediately() {
        val port = FakeRecoveryPort(
            outcomes = listOf(
                AndroidReconnectOutcome.Unrecoverable("The Hermes Profile is not authorized."),
                AndroidReconnectOutcome.Connected("session-2"),
            ),
        )
        val controller = AndroidRecoveryController(port, maxAttempts = 5)

        controller.transportLost("Transport closed.")
        val state = controller.recover()

        assertEquals(
            AndroidConnectionState.Failed("The Hermes Profile is not authorized."),
            state.connection,
        )
        assertEquals(1, port.reconnectAttempts)
    }

    @Test
    fun recovery_never_replays_an_uncertain_turn() {
        val port = FakeRecoveryPort(outcomes = listOf(AndroidReconnectOutcome.Connected("session-2")))
        val controller = AndroidRecoveryController(port)

        controller.transportLost("Transport closed.", unconfirmed)
        val state = controller.recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        assertEquals(unconfirmed, state.unconfirmedTurn)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun an_explicit_resend_sends_the_retained_turn_exactly_once() {
        val port = FakeRecoveryPort(
            outcomes = listOf(AndroidReconnectOutcome.Connected("session-2")),
            initiation = AndroidInitiationResult.Accepted(
                AndroidTurnBinding(profile.id, "session-2", "turn-2"),
            ),
        )
        val controller = AndroidRecoveryController(port)

        controller.transportLost("Transport closed.", unconfirmed)
        controller.recover()

        val first = controller.resendUnconfirmedTurn()
        val second = controller.resendUnconfirmedTurn()

        assertEquals(
            AndroidResendResult.Sent(AndroidTurnBinding(profile.id, "session-2", "turn-2")),
            first,
        )
        assertEquals(AndroidResendResult.NothingToResend, second)
        assertEquals(listOf(request), port.requests)
        assertNull(controller.state.unconfirmedTurn)
    }

    @Test
    fun a_rejected_resend_retains_the_turn_without_an_automatic_retry() {
        val port = FakeRecoveryPort(
            outcomes = listOf(AndroidReconnectOutcome.Connected("session-2")),
            initiation = AndroidInitiationResult.Rejected(
                AndroidInitiationFailure.SessionUnavailable,
            ),
        )
        val controller = AndroidRecoveryController(port)

        controller.transportLost("Transport closed.", unconfirmed)
        controller.recover()
        val result = controller.resendUnconfirmedTurn()

        assertEquals(
            AndroidResendResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            result,
        )
        assertEquals(unconfirmed, controller.state.unconfirmedTurn)
        assertEquals(1, port.requests.size)
    }

    @Test
    fun a_resend_is_refused_while_transport_is_not_connected() {
        val port = FakeRecoveryPort(outcomes = listOf(AndroidReconnectOutcome.Retryable("Down.")))
        val controller = AndroidRecoveryController(port, maxAttempts = 1)

        controller.transportLost("Transport closed.", unconfirmed)
        controller.recover()
        val result = controller.resendUnconfirmedTurn()

        assertEquals(AndroidResendResult.NotConnected, result)
        assertEquals(unconfirmed, controller.state.unconfirmedTurn)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun a_second_loss_during_recovery_does_not_stack_ladders() {
        val port = FakeRecoveryPort(
            outcomes = listOf(
                AndroidReconnectOutcome.Retryable("Timed out."),
                AndroidReconnectOutcome.Connected("session-2"),
            ),
        )
        val controller = AndroidRecoveryController(port, maxAttempts = 3)
        port.onReconnect = {
            controller.transportLost("A second loss was reported.", unconfirmed)
            controller.recover()
        }

        controller.transportLost("Transport closed.")
        val state = controller.recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        assertEquals(2, port.reconnectAttempts)
        assertEquals(unconfirmed, state.unconfirmedTurn)
    }

    @Test
    fun a_discarded_turn_is_not_resent() {
        val port = FakeRecoveryPort(outcomes = listOf(AndroidReconnectOutcome.Connected("session-2")))
        val controller = AndroidRecoveryController(port)

        controller.transportLost("Transport closed.", unconfirmed)
        controller.recover()
        controller.discardUnconfirmedTurn()

        assertEquals(AndroidResendResult.NothingToResend, controller.resendUnconfirmedTurn())
        assertEquals(0, port.requests.size)
    }

    @Test
    fun a_superseded_session_event_cannot_mutate_the_turn_after_recovery() {
        val resentBinding = AndroidTurnBinding(profile.id, "session-2", "turn-2")
        val recovered = AndroidTurnState.awaitingEvents(resentBinding)

        val stale = AndroidTurnStateReducer.reduce(
            recovered,
            AndroidNormalizedEvent.ResponseTextDelta(lostBinding, "Answer from the lost session"),
        )
        val staleCompletion = AndroidTurnStateReducer.reduce(
            stale,
            AndroidNormalizedEvent.TurnCompleted(lostBinding, "Stale final text"),
        )
        val staleDisconnect = AndroidTurnStateReducer.reduce(
            staleCompletion,
            AndroidNormalizedEvent.Disconnected("session-1", "The superseded socket closed."),
        )

        assertEquals(recovered, staleDisconnect)
        assertTrue(staleDisconnect.responseText.isEmpty())
        assertEquals(AndroidTurnPhase.Idle, staleDisconnect.phase)
    }

    private class FakeRecoveryPort(
        private val outcomes: List<AndroidReconnectOutcome>,
        private val initiation: AndroidInitiationResult =
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
    ) : AndroidClientPort {
        val requests = mutableListOf<AndroidTurnRequest>()
        val observedProgress = mutableListOf<AndroidConnectionState>()
        var reconnectAttempts = 0
        var onReconnect: (() -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("amanda", "Amanda"),
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return initiation
        }

        override fun reconnect(): AndroidReconnectOutcome {
            onReconnect?.invoke()
            val outcome = outcomes[reconnectAttempts.coerceAtMost(outcomes.lastIndex)]
            reconnectAttempts += 1
            return outcome
        }
    }
}
