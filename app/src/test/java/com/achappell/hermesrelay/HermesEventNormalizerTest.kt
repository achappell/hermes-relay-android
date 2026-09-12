package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesEventNormalizerTest {
    private val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")

    private fun normalizer() = HermesEventNormalizer("amanda-laptop").apply { beginTurn() }

    private fun frame(vararg pairs: Pair<String, Any>): String =
        org.json.JSONObject(mapOf("session_id" to "session-1", "turn_id" to "turn-1") + pairs)
            .toString()

    @Test
    fun streamed_deltas_accumulate_into_one_response() {
        val normalizer = normalizer()

        val first = normalizer.normalize(frame("type" to "text_delta", "text" to "Rain "), binding)
        val second = normalizer.normalize(frame("type" to "text_delta", "text" to "later"), binding)

        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain ")),
            first,
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, "later")),
            second,
        )
    }

    @Test
    fun a_final_text_repeating_the_stream_emits_nothing() {
        val normalizer = normalizer()
        normalizer.normalize(frame("type" to "text_delta", "text" to "Rain "), binding)
        normalizer.normalize(frame("type" to "text_delta", "text" to "later"), binding)

        val final = normalizer.normalize(
            frame("type" to "text_final", "text" to "Rain later"),
            binding,
        )

        assertTrue("the response would have rendered twice", final.isEmpty())
    }

    @Test
    fun a_final_text_continuing_the_stream_emits_only_the_suffix() {
        val normalizer = normalizer()
        normalizer.normalize(frame("type" to "text_delta", "text" to "Rain"), binding)

        val final = normalizer.normalize(
            frame("type" to "text_final", "text" to "Rain later today"),
            binding,
        )

        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, " later today")),
            final,
        )
    }

    @Test
    fun a_final_text_rewriting_the_stream_replaces_it() {
        val normalizer = normalizer()
        normalizer.normalize(frame("type" to "text_delta", "text" to "Rain"), binding)

        val final = normalizer.normalize(
            frame("type" to "text_final", "text" to "Actually, sunshine"),
            binding,
        )

        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextReplace(binding, "Actually, sunshine")),
            final,
        )
    }

    @Test
    fun a_final_text_with_no_prior_stream_becomes_the_response() {
        val final = normalizer().normalize(
            frame("type" to "text_final", "text" to "Only answer"),
            binding,
        )

        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, "Only answer")),
            final,
        )
    }

    @Test
    fun a_new_turn_resets_streamed_text_state() {
        val normalizer = normalizer()
        normalizer.normalize(frame("type" to "text_delta", "text" to "First answer"), binding)

        normalizer.beginTurn()
        val second = normalizer.normalize(
            frame("type" to "text_final", "text" to "First answer"),
            binding,
        )

        assertEquals(
            listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, "First answer")),
            second,
        )
    }

    @Test
    fun status_frames_project_the_canonical_phases() {
        val normalizer = normalizer()

        assertEquals(
            listOf(AndroidNormalizedEvent.Thinking(binding)),
            normalizer.normalize(frame("type" to "status", "status" to "thinking"), binding),
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.CaptureStarted(binding)),
            normalizer.normalize(frame("type" to "status", "status" to "listening"), binding),
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.TranscriptionStarted(binding)),
            normalizer.normalize(frame("type" to "status", "status" to "transcribing"), binding),
        )
        assertTrue(
            normalizer.normalize(frame("type" to "status", "status" to "idle"), binding).isEmpty(),
        )
    }

    @Test
    fun audio_lifecycle_frames_map_without_retaining_bytes() {
        val normalizer = normalizer()

        assertEquals(
            listOf(AndroidNormalizedEvent.AudioStarted(binding)),
            normalizer.normalize(frame("type" to "audio_start"), binding),
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.AudioEnded(binding)),
            normalizer.normalize(frame("type" to "audio_end"), binding),
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.AudioBuffering(binding)),
            normalizer.normalize(frame("type" to "audio_file_start"), binding),
        )
        assertEquals(
            listOf(AndroidNormalizedEvent.AudioEnded(binding)),
            normalizer.normalize(frame("type" to "audio_file_end"), binding),
        )
        assertEquals(
            AndroidNormalizedEvent.AudioChunkReceived(binding),
            normalizer.normalizeBinary(binding),
        )
    }

    @Test
    fun an_aborted_audio_stream_reports_the_reason() {
        val event = normalizer().normalize(
            frame("type" to "audio_abort", "reason" to "output device lost"),
            binding,
        ).single()

        assertEquals(AndroidNormalizedEvent.AudioFailed(binding, "output device lost"), event)
    }

    @Test
    fun a_duplicate_turn_is_reported_rather_than_replayed() {
        val event = normalizer().normalize(
            frame("type" to "turn_duplicate", "reason" to "already_processed"),
            binding,
        ).single()

        assertEquals(AndroidNormalizedEvent.TurnFailed(binding, "already_processed"), event)
    }

    @Test
    fun an_accepted_turn_emits_no_presentation_event() {
        assertTrue(
            normalizer().normalize(frame("type" to "turn_accepted"), binding).isEmpty(),
        )
    }

    @Test
    fun an_interrupted_turn_and_an_error_both_fail_the_turn() {
        val normalizer = normalizer()

        assertEquals(
            AndroidNormalizedEvent.TurnFailed(binding, "user interrupted"),
            normalizer.normalize(
                frame("type" to "turn_interrupted", "reason" to "user interrupted"),
                binding,
            ).single(),
        )
        assertEquals(
            AndroidNormalizedEvent.TurnFailed(binding, "model unavailable"),
            normalizer.normalize(
                frame("type" to "error", "error" to "model unavailable"),
                binding,
            ).single(),
        )
    }

    @Test
    fun turn_end_completes_the_turn() {
        assertEquals(
            listOf(AndroidNormalizedEvent.TurnCompleted(binding)),
            normalizer().normalize(frame("type" to "turn_end"), binding),
        )
    }

    @Test
    fun frames_this_slice_does_not_own_are_reported_as_unknown() {
        val normalizer = normalizer()

        listOf("speech_timing", "prompt_request", "steer_accepted", "pong", "dm").forEach { type ->
            assertEquals(
                "expected $type to be unknown",
                listOf(AndroidNormalizedEvent.Unknown(binding, type)),
                normalizer.normalize(frame("type" to type), binding),
            )
        }
    }

    @Test
    fun a_malformed_frame_is_unknown_rather_than_a_crash() {
        assertEquals(
            listOf(AndroidNormalizedEvent.Unknown(binding, "malformed")),
            normalizer().normalize("{ not json", binding),
        )
    }

    @Test
    fun identity_comes_from_the_frame_so_a_stale_turn_cannot_be_adopted() {
        val event = normalizer().normalize(
            org.json.JSONObject()
                .put("type", "text_final")
                .put("text", "stale answer")
                .put("session_id", "session-0")
                .put("turn_id", "turn-0")
                .toString(),
            binding,
        ).single()

        val staleBinding = AndroidTurnBinding("amanda-laptop", "session-0", "turn-0")
        assertEquals(
            AndroidNormalizedEvent.ResponseTextDelta(staleBinding, "stale answer"),
            event,
        )

        // The A-2 reducer rejects it because the identity does not match.
        val state = AndroidTurnState.awaitingEvents(binding)
        assertEquals(state, AndroidTurnStateReducer.reduce(state, event))
    }

    @Test
    fun a_payload_wrapped_frame_is_read_the_same_way() {
        val event = normalizer().normalize(
            org.json.JSONObject()
                .put("type", "text_final")
                .put(
                    "payload",
                    org.json.JSONObject()
                        .put("text", "wrapped answer")
                        .put("session_id", "session-1")
                        .put("turn_id", "turn-1"),
                )
                .toString(),
            binding,
        ).single()

        assertEquals(
            AndroidNormalizedEvent.ResponseTextDelta(binding, "wrapped answer"),
            event,
        )
    }
}
