package com.achappell.hermesrelay

import org.json.JSONObject

/**
 * Translates Hermes voice-session frames into the normalized events the
 * Android reducer already consumes.
 *
 * The normalizer is stateful on purpose. The relay streams response text as
 * `text_delta` increments and then repeats the whole answer in `text_final`,
 * so a naive reader renders the response twice. Tracking what has already been
 * rendered lets a repeat collapse to nothing, a continuation become a delta,
 * and a genuine rewrite become a replace.
 *
 * Wire frames stop here. Nothing above this class sees a Hermes frame, and no
 * audio bytes are retained — a binary frame becomes lifecycle evidence only.
 */
internal class HermesEventNormalizer(
    private val clientId: String,
) {
    private var rendered = ""
    private var streamed = false
    private var audioFileActive = false

    /** Resets streamed-text state at the start of a turn. */
    fun beginTurn() {
        rendered = ""
        streamed = false
        audioFileActive = false
    }

    /** A binary frame is delivery evidence; the bytes are deliberately dropped. */
    fun normalizeBinary(binding: AndroidTurnBinding): AndroidNormalizedEvent =
        AndroidNormalizedEvent.AudioChunkReceived(binding)

    fun normalize(text: String, fallback: AndroidTurnBinding): List<AndroidNormalizedEvent> {
        val frame = runCatching { JSONObject(text) }.getOrNull()
            ?: return listOf(AndroidNormalizedEvent.Unknown(fallback, "malformed"))

        val payload = frame.optJSONObject("payload") ?: frame
        val type = frame.optString("type").ifBlank { "missing" }
        val binding = bindingFor(frame, payload, fallback)

        return when (type) {
            "turn_accepted" -> emptyList()

            // The relay proved this turn was already processed. Surfacing it as
            // a failure keeps the no-replay guarantee honest: the Client shows
            // the turn did not run again rather than silently pretending it did.
            "turn_duplicate" -> listOf(
                AndroidNormalizedEvent.TurnFailed(
                    binding,
                    payload.optString("reason").ifBlank { "already_processed" },
                ),
            )

            "status" -> statusEvent(binding, payload, frame)

            "text_delta" -> deltaEvents(
                binding,
                payload.optString("text").ifBlank { payload.optString("rendered") },
                replace = payload.optBoolean("replace", false),
            )

            "text_final" -> finalTextEvents(
                binding,
                payload.optString("text").ifBlank { payload.optString("rendered") },
            )

            "audio_start" -> listOf(
                AndroidNormalizedEvent.AudioStarted(
                    binding,
                    AndroidAudioFormat(
                        sampleRate = payload.optInt("sample_rate", 24_000),
                        channels = payload.optInt("channels", 1),
                        sampleWidth = payload.optInt("sample_width", 2),
                        encoding = payload.optString("encoding").ifBlank { "pcm_s16le" },
                    ),
                ),
            )

            "audio_file_start" -> {
                audioFileActive = true
                listOf(AndroidNormalizedEvent.AudioBuffering(binding))
            }

            "audio_end" -> listOf(AndroidNormalizedEvent.AudioEnded(binding))

            "audio_file_end" -> {
                audioFileActive = false
                listOf(AndroidNormalizedEvent.AudioEnded(binding))
            }

            "audio_abort" -> listOf(
                AndroidNormalizedEvent.AudioFailed(
                    binding,
                    firstNonBlank(payload, "error", "reason", "message")
                        ?: "The response audio stream was aborted.",
                ),
            )

            "turn_interrupted" -> listOf(
                AndroidNormalizedEvent.TurnInterrupted(
                    binding,
                    firstNonBlank(payload, "reason", "error", "message")
                        ?: "The turn was interrupted.",
                ),
            )

            "error" -> listOf(
                AndroidNormalizedEvent.TurnFailed(
                    binding,
                    firstNonBlank(payload, "error", "message")
                        ?: "The Hermes relay reported an error.",
                ),
            )

            "turn_end" -> listOf(AndroidNormalizedEvent.TurnCompleted(binding))

            // Frames this slice does not own: speech timing, steering, prompts,
            // session management, keepalives. They are reported as unknown
            // rather than guessed at, and the reducer leaves state untouched.
            else -> listOf(AndroidNormalizedEvent.Unknown(binding, type))
        }
    }

    private fun statusEvent(
        binding: AndroidTurnBinding,
        payload: JSONObject,
        frame: JSONObject,
    ): List<AndroidNormalizedEvent> {
        val status = payload.optString("status")
            .ifBlank { frame.optString("status") }
            .lowercase()

        return when {
            status.startsWith("listen") -> listOf(AndroidNormalizedEvent.CaptureStarted(binding))
            status.startsWith("transcrib") -> listOf(
                AndroidNormalizedEvent.TranscriptionStarted(binding),
            )
            status.startsWith("think") -> listOf(AndroidNormalizedEvent.Thinking(binding))
            else -> emptyList()
        }
    }

    private fun deltaEvents(
        binding: AndroidTurnBinding,
        text: String,
        replace: Boolean,
    ): List<AndroidNormalizedEvent> {
        if (text.isEmpty()) return emptyList()

        if (replace) {
            rendered = text
            streamed = true
            return listOf(AndroidNormalizedEvent.ResponseTextReplace(binding, text))
        }

        rendered += text
        streamed = true
        return listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, text))
    }

    private fun finalTextEvents(
        binding: AndroidTurnBinding,
        finalText: String,
    ): List<AndroidNormalizedEvent> {
        if (finalText.isEmpty()) return emptyList()

        if (!streamed) {
            rendered = finalText
            streamed = true
            return listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, finalText))
        }

        // The relay repeated exactly what was already streamed.
        if (finalText == rendered) return emptyList()

        if (finalText.startsWith(rendered)) {
            val suffix = finalText.substring(rendered.length)
            rendered = finalText
            return if (suffix.isEmpty()) {
                emptyList()
            } else {
                listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, suffix))
            }
        }

        rendered = finalText
        return listOf(AndroidNormalizedEvent.ResponseTextReplace(binding, finalText))
    }

    private fun bindingFor(
        frame: JSONObject,
        payload: JSONObject,
        fallback: AndroidTurnBinding,
    ): AndroidTurnBinding {
        val turnId = firstNonBlank(payload, "turn_id")
            ?: firstNonBlank(frame, "turn_id")
            ?: fallback.turnId
        val sessionId = firstNonBlank(payload, "session_id")
            ?: firstNonBlank(frame, "session_id")
            ?: fallback.sessionId
        return AndroidTurnBinding(
            profileId = clientId,
            sessionId = sessionId,
            turnId = turnId,
        )
    }

    private fun firstNonBlank(source: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = source.optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }
}
