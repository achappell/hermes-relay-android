package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Client must not claim it is speaking when it is not. These tests pin the
 * relationship between the relay's audio frames, the sink, and the phases the
 * A-2 reducer projects.
 */
class ResponseAudioPlaybackTest {
    private val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")
    private val relayFormat = AndroidAudioFormat(24_000, 1, 2, "pcm_s16le")

    @Test
    fun the_relays_announced_format_is_parsed_and_supported() {
        val normalizer = HermesEventNormalizer("amanda-laptop").apply { beginTurn() }

        val event = normalizer.normalize(
            org.json.JSONObject()
                .put("type", "audio_start")
                .put("session_id", "session-1")
                .put("turn_id", "turn-1")
                .put("sample_rate", 24_000)
                .put("channels", 1)
                .put("sample_width", 2)
                .put("encoding", "pcm_s16le")
                .toString(),
            binding,
        ).single() as AndroidNormalizedEvent.AudioStarted

        assertEquals(relayFormat, event.format)
        assertTrue(event.format!!.isSupported)
    }

    @Test
    fun unsupported_formats_are_rejected_rather_than_played_wrongly() {
        assertTrue(!AndroidAudioFormat(24_000, 1, 2, "opus").isSupported)
        assertTrue(!AndroidAudioFormat(24_000, 1, 4, "pcm_s16le").isSupported)
        assertTrue(!AndroidAudioFormat(24_000, 7, 2, "pcm_s16le").isSupported)
        assertTrue(!AndroidAudioFormat(1_000, 1, 2, "pcm_s16le").isSupported)
        assertTrue(AndroidAudioFormat(48_000, 2, 2, "PCM_S16LE").isSupported)
    }

    @Test
    fun a_played_turn_reaches_complete_with_audio_delivered() {
        val sink = RecordingAudioSink()
        val events = mutableListOf<AndroidNormalizedEvent>()

        // The exact frame order observed from the relay.
        val incoming = listOf(
            AndroidNormalizedEvent.Thinking(binding),
            AndroidNormalizedEvent.AudioStarted(binding, relayFormat),
            AndroidNormalizedEvent.ResponseTextDelta(binding, "One short sentence."),
            AndroidNormalizedEvent.AudioChunkReceived(binding),
            AndroidNormalizedEvent.AudioEnded(binding),
            AndroidNormalizedEvent.TurnCompleted(binding),
        )
        incoming.forEach { event ->
            when (event) {
                is AndroidNormalizedEvent.AudioStarted -> {
                    assertTrue(sink.start(event.format!!))
                    events += event
                }
                is AndroidNormalizedEvent.AudioChunkReceived -> {
                    sink.write(ByteArray(8192))
                    events += event
                }
                is AndroidNormalizedEvent.AudioEnded ->
                    sink.finish(onDrained = { events += event }, onFailure = { })
                else -> events += event
            }
        }

        var state = AndroidTurnState.awaitingEvents(binding)
        events.forEach { state = AndroidTurnStateReducer.reduce(state, it) }

        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
        assertEquals("One short sentence.", state.responseText)
        assertEquals(8192, sink.bytesWritten)
        assertEquals(relayFormat, sink.startedFormat)
    }

    @Test
    fun a_rejected_format_never_claims_speaking_and_keeps_the_text() {
        val sink = RecordingAudioSink(acceptFormat = false)
        val started = AndroidNormalizedEvent.AudioStarted(binding, relayFormat)

        assertTrue(!sink.start(started.format!!))

        // The Client substitutes a failure rather than reporting Speaking.
        var state = AndroidTurnState.awaitingEvents(binding)
        listOf(
            AndroidNormalizedEvent.ResponseTextDelta(binding, "Text survives"),
            AndroidNormalizedEvent.AudioFailed(binding, "cannot play this format"),
            AndroidNormalizedEvent.TurnCompleted(binding),
        ).forEach { state = AndroidTurnStateReducer.reduce(state, it) }

        assertEquals(AndroidTurnPhase.Unavailable, state.phase)
        assertEquals(AndroidAudioDelivery.Unavailable, state.audio)
        assertEquals("Text survives", state.responseText)
    }

    @Test
    fun finishing_without_a_start_reports_failure_rather_than_silent_success() {
        val sink = RecordingAudioSink()
        var drained = false
        var failure: String? = null

        sink.finish(onDrained = { drained = true }, onFailure = { failure = it })

        assertTrue(!drained)
        assertTrue(failure != null)
    }
}
