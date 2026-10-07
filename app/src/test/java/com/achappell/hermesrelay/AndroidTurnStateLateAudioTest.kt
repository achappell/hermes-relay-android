package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-HOME-05`: the reducer keeps a turn whose text is complete but whose
 * audio has not started in an honest waiting state. The new event did not exist
 * before this ticket, so these tests are compile-red against the previous code.
 */
class AndroidTurnStateLateAudioTest {
    private val binding = AndroidTurnBinding("amanda", "conversation-1", "turn-1")
    private val start = AndroidTurnState.awaitingEvents(binding)

    private fun after(vararg events: AndroidNormalizedEvent): AndroidTurnState =
        events.fold(start) { state, event -> AndroidTurnStateReducer.reduce(state, event) }

    @Test
    fun text_complete_with_audio_pending_waits_and_is_neither_complete_nor_speaking() {
        val state = after(
            AndroidNormalizedEvent.ResponseTextDelta(binding, "Hello"),
            AndroidNormalizedEvent.TextCompleted(binding, "Hello"),
        )

        assertFalse(state.isTerminal)
        assertEquals(AndroidTurnPhase.Buffering, state.phase)
        assertEquals(AndroidAudioDelivery.NotStarted, state.audio)
        assertEquals("Hello", state.responseText)
        assertTrue(state.turnCompleteObserved)
    }

    @Test
    fun late_audio_goes_through_speaking_to_complete() {
        val speaking = after(
            AndroidNormalizedEvent.TextCompleted(binding, "Hello"),
            AndroidNormalizedEvent.AudioStarted(binding, AndroidAudioFormat(24_000, 1, 2, "pcm_s16le")),
        )
        assertEquals(AndroidTurnPhase.Speaking, speaking.phase)

        val done = AndroidTurnStateReducer.reduce(speaking, AndroidNormalizedEvent.AudioEnded(binding))

        assertEquals(AndroidTurnPhase.Complete, done.phase)
        assertEquals(AndroidAudioDelivery.Delivered, done.audio)
    }

    @Test
    fun audio_unavailable_after_the_text_ends_the_turn_and_keeps_the_text() {
        val state = after(
            AndroidNormalizedEvent.TextCompleted(binding, "Kept"),
            AndroidNormalizedEvent.AudioFailed(binding, "transport_timeout"),
        )

        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
        assertEquals("Kept", state.responseText)
        assertEquals(AndroidAudioDelivery.Unavailable, state.audio)
    }

    @Test
    fun a_dropped_connection_while_waiting_for_audio_is_disconnected_not_complete() {
        val waiting = after(AndroidNormalizedEvent.TextCompleted(binding, "Hi"))

        val dropped = AndroidTurnStateReducer.reduce(
            waiting,
            AndroidNormalizedEvent.Disconnected(binding.connectionId, "Transport closed."),
        )

        assertEquals(AndroidTurnPhase.Disconnected, dropped.phase)
        assertEquals("Hi", dropped.responseText)
    }

    @Test
    fun the_old_terminal_without_audio_is_unchanged_when_audio_was_never_advertised() {
        val state = after(
            AndroidNormalizedEvent.ResponseTextDelta(binding, "Hi"),
            AndroidNormalizedEvent.TurnCompleted(binding, "Hi"),
        )

        assertTrue(state.isTerminal)
        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
    }
}
