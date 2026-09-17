package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTurnStateTest {
    @Test
    fun an_interrupted_turn_is_terminal_and_keeps_the_partial_response() {
        val binding = AndroidTurnBinding("amanda", "session-1", "turn-1")
        var state = AndroidTurnState.awaitingEvents(binding)

        listOf(
            AndroidNormalizedEvent.ResponseTextDelta(binding, "Half an ans"),
            AndroidNormalizedEvent.AudioStarted(binding),
            AndroidNormalizedEvent.TurnInterrupted(binding, "user interrupted"),
        ).forEach { state = AndroidTurnStateReducer.reduce(state, it) }

        assertEquals(AndroidTurnPhase.Interrupted, state.phase)
        assertEquals("Half an ans", state.responseText)
        assertEquals(AndroidAudioDelivery.Unavailable, state.audio)
        assertTrue(state.isTerminal)
    }

    @Test
    fun a_late_event_cannot_revive_an_interrupted_turn() {
        val binding = AndroidTurnBinding("amanda", "session-1", "turn-1")
        var state = AndroidTurnState.awaitingEvents(binding)
        state = AndroidTurnStateReducer.reduce(
            state,
            AndroidNormalizedEvent.TurnInterrupted(binding, "user interrupted"),
        )

        val after = AndroidTurnStateReducer.reduce(
            state,
            AndroidNormalizedEvent.ResponseTextDelta(binding, " and more"),
        )

        assertEquals(state, after)
    }

    @Test
    fun interrupt_acknowledgement_is_not_terminal() {
        val thinking = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
        val acknowledgement = AndroidInterruptTelemetry(
            sentCount = 1,
            acknowledgementObserved = true,
            terminalObserved = false,
        )

        assertTrue(acknowledgement.acknowledgementObserved)
        assertFalse(acknowledgement.terminalObserved)
        assertFalse(thinking.isTerminal)
        assertEquals(AndroidTurnPhase.Thinking, thinking.phase)
    }

    private val binding = AndroidTurnBinding(
        profileId = "amanda",
        sessionId = "session-1",
        turnId = "turn-1",
    )

    @Test
    fun normalized_lifecycle_advances_once_and_completes_after_audio_delivery() {
        var state = AndroidTurnState.awaitingEvents(binding)

        state = state.reduce(AndroidNormalizedEvent.CaptureStarted(binding))
        assertEquals(AndroidTurnPhase.Listening, state.phase)
        state = state.reduce(AndroidNormalizedEvent.TranscriptionStarted(binding))
        assertEquals(AndroidTurnPhase.Transcribing, state.phase)
        state = state.reduce(AndroidNormalizedEvent.Thinking(binding))
        assertEquals(AndroidTurnPhase.Thinking, state.phase)
        state = state.reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "Hello "))
        state = state.reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "Amanda"))
        state = state.reduce(AndroidNormalizedEvent.AudioBuffering(binding))
        assertEquals(AndroidTurnPhase.Buffering, state.phase)

        state = state.reduce(AndroidNormalizedEvent.Thinking(binding))
        assertEquals(AndroidTurnPhase.Buffering, state.phase)
        state = state.reduce(AndroidNormalizedEvent.AudioStarted(binding))
        assertEquals(AndroidTurnPhase.Speaking, state.phase)
        state = state.reduce(AndroidNormalizedEvent.AudioChunkReceived(binding))
        state = state.reduce(AndroidNormalizedEvent.AudioEnded(binding))
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
        assertEquals(AndroidTurnPhase.Speaking, state.phase)
        state = state.reduce(AndroidNormalizedEvent.TurnCompleted(binding))

        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
        assertEquals("Hello Amanda", state.responseText)
        assertTrue(state.isTerminal)
    }

    @Test
    fun turn_completion_waits_for_audio_when_delivery_is_in_flight() {
        var state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "Answer"))
            .reduce(AndroidNormalizedEvent.AudioBuffering(binding))
            .reduce(AndroidNormalizedEvent.TurnCompleted(binding))

        assertEquals(AndroidTurnPhase.Buffering, state.phase)
        assertFalse(state.isTerminal)
        assertTrue(state.turnCompleteObserved)

        state = state.reduce(AndroidNormalizedEvent.AudioStarted(binding))
            .reduce(AndroidNormalizedEvent.AudioEnded(binding))

        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
    }

    @Test
    fun text_remains_visible_when_audio_is_unavailable() {
        val state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "The answer"))
            .reduce(AndroidNormalizedEvent.TurnCompleted(binding))

        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
        assertEquals(AndroidAudioDelivery.Unavailable, state.audio)
        assertEquals("The answer", state.responseText)
        assertTrue(state.isTerminal)
    }

    @Test
    fun audio_failure_preserves_response_and_never_claims_speaking() {
        var state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "Partial answer"))
            .reduce(AndroidNormalizedEvent.AudioFailed(binding, "decoder unavailable"))

        assertEquals(AndroidTurnPhase.Thinking, state.phase)
        assertEquals(AndroidAudioDelivery.Unavailable, state.audio)
        assertEquals("Partial answer", state.responseText)
        assertEquals("decoder unavailable", state.unavailableReason)
        assertFalse(state.isTerminal)

        state = state.reduce(AndroidNormalizedEvent.TurnCompleted(binding))
        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
        assertTrue(state.isTerminal)
    }

    @Test
    fun stale_or_unknown_events_cannot_mutate_the_active_turn() {
        val staleBinding = binding.copy(turnId = "turn-old")
        val state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
        val afterStale = state
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(staleBinding, "wrong turn"))
            .reduce(AndroidNormalizedEvent.AudioStarted(staleBinding))
            .reduce(AndroidNormalizedEvent.Unknown(binding, "future.event"))

        assertEquals(state, afterStale)
    }

    @Test
    fun disconnected_event_requires_the_active_session_identity() {
        val state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.Thinking(binding))
        val afterWrongSession = state.reduce(
            AndroidNormalizedEvent.Disconnected("session-old", "old session"),
        )
        assertEquals(state, afterWrongSession)

        val disconnected = state.reduce(
            AndroidNormalizedEvent.Disconnected(binding.sessionId, "transport lost"),
        )
        assertEquals(AndroidTurnPhase.Disconnected, disconnected.phase)
        assertEquals(AndroidAudioDelivery.Unavailable, disconnected.audio)
        assertEquals("transport lost", disconnected.unavailableReason)
    }

    @Test
    fun response_replacement_does_not_duplicate_streamed_text() {
        val state = AndroidTurnState.awaitingEvents(binding)
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, "Hel"))
            .reduce(AndroidNormalizedEvent.ResponseTextReplace(binding, "Hello"))
            .reduce(AndroidNormalizedEvent.ResponseTextDelta(binding, " world"))

        assertEquals("Hello world", state.responseText)
    }

    private fun AndroidTurnState.reduce(event: AndroidNormalizedEvent): AndroidTurnState =
        AndroidTurnStateReducer.reduce(this, event)
}
