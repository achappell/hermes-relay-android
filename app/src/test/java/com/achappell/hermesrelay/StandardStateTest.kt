package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ANDROID-STD-01`: the pure state rules a Standard conversation relies on. */
class StandardStateTest {
    private val binding = AndroidTurnBinding("std", "standard-1", "conn", "turn-1")

    // ---- reducer: a text-only turn is complete, a Home turn without audio is not ----

    @Test
    fun a_text_only_turn_completes_without_audio() {
        val state = AndroidTurnStateReducer.reduce(
            AndroidTurnState.awaitingEvents(binding),
            AndroidNormalizedEvent.TurnCompleted(binding, "Hello", textOnly = true),
        )

        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.NotStarted, state.audio)
        assertEquals("Hello", state.responseText)
        assertNull(state.unavailableReason)
        assertTrue(state.isTerminal)
    }

    @Test
    fun a_home_turn_without_audio_is_still_unavailable() {
        val state = AndroidTurnStateReducer.reduce(
            AndroidTurnState.awaitingEvents(binding),
            AndroidNormalizedEvent.TurnCompleted(binding, "Hello"),
        )

        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
        assertEquals("Response audio was not delivered.", state.unavailableReason)
    }

    @Test
    fun a_text_only_completion_does_not_cut_off_audio_that_is_playing() {
        var state = AndroidTurnState.awaitingEvents(binding)
        state = AndroidTurnStateReducer.reduce(state, AndroidNormalizedEvent.AudioStarted(binding))
        state = AndroidTurnStateReducer.reduce(
            state,
            AndroidNormalizedEvent.TurnCompleted(binding, "Hello", textOnly = true),
        )

        assertEquals(AndroidTurnPhase.Speaking, state.phase)
        assertFalse(state.isTerminal)
    }

    @Test
    fun a_stopped_standard_turn_settles_as_interrupted_not_failed() {
        val state = AndroidTurnStateReducer.reduce(
            AndroidTurnStateReducer.reduce(
                AndroidTurnState.awaitingEvents(binding),
                AndroidNormalizedEvent.ResponseTextDelta(binding, "Half"),
            ),
            AndroidNormalizedEvent.TurnInterrupted(binding, "stopped"),
        )

        assertEquals(AndroidTurnPhase.Interrupted, state.phase)
        assertEquals("Half", state.responseText)
    }

    @Test
    fun a_standard_disconnect_makes_the_turn_terminal_so_it_can_be_retained_as_uncertain() {
        val state = AndroidTurnStateReducer.reduce(
            AndroidTurnState.awaitingEvents(binding),
            AndroidNormalizedEvent.Disconnected(binding.connectionId, "lost"),
        )

        assertEquals(AndroidTurnPhase.Disconnected, state.phase)
        assertTrue(state.isTerminal)
    }

    // ---- composer ----

    @Test
    fun finishing_the_previous_response_blocks_sending_even_when_connected() {
        assertEquals(
            AndroidComposerBlock.FinishingPreviousResponse,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = false,
                prompt = "next",
                isFinishingPreviousResponse = true,
            ),
        )
    }

    @Test
    fun an_active_or_uncertain_turn_outranks_finishing_and_authorization_outranks_both() {
        assertEquals(
            AndroidComposerBlock.ActiveTurn,
            resolveAndroidComposerBlock(true, true, true, true, "x", isFinishingPreviousResponse = true),
        )
        assertEquals(
            AndroidComposerBlock.UnconfirmedTurn,
            resolveAndroidComposerBlock(
                true, true, true, false, "x",
                hasUnconfirmedTurn = true,
                isFinishingPreviousResponse = true,
            ),
        )
        assertEquals(
            AndroidComposerBlock.Authorization,
            resolveAndroidComposerBlock(true, false, true, false, "x", isFinishingPreviousResponse = true),
        )
    }

    @Test
    fun the_composer_is_open_once_finishing_has_ended() {
        assertNull(
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = false,
                prompt = "next",
                isFinishingPreviousResponse = false,
            ),
        )
    }

    // ---- recovery: reconnect restores transport, never clears uncertainty ----

    @Test
    fun reconnect_keeps_the_uncertain_turn_and_reports_a_fresh_session_once() {
        val request = AndroidTurnRequest(
            AndroidProfile("std", "Standard"),
            AndroidTurnInput.Typed("hello"),
        )
        val port = OutcomePort(
            AndroidReconnectOutcome.Connected("c1", sessionStartedFresh = true),
            AndroidReconnectOutcome.Connected("c2", sessionStartedFresh = false),
        )
        val controller = AndroidRecoveryController(port)
        controller.transportLost("lost", AndroidUnconfirmedTurn(binding, request))

        val first = controller.recover()
        assertEquals(AndroidConnectionState.Connected, first.connection)
        assertTrue(first.startedFreshSession)
        assertEquals("the uncertain turn survives reconnect", AndroidUnconfirmedTurn(binding, request), first.unconfirmedTurn)
        assertFalse("reconnect never claims Hermes resolved the turn", first.unresolvedHomeTurn)

        controller.transportLost("lost again")
        val second = controller.recover()
        assertFalse(second.startedFreshSession)
        assertEquals(AndroidUnconfirmedTurn(binding, request), second.unconfirmedTurn)
        assertEquals("nothing was submitted by recovery", 0, port.beginTurns)
    }

    private class OutcomePort(vararg outcomes: AndroidReconnectOutcome) : AndroidClientPort {
        private val queue = ArrayDeque(outcomes.toList())
        var beginTurns = 0

        override fun snapshot() = AndroidClientSnapshot(0, 0, 0)

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            beginTurns += 1
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
        }

        override fun reconnect(): AndroidReconnectOutcome = queue.removeFirst()
    }
}
