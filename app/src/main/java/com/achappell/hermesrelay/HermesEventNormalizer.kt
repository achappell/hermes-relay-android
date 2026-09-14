package com.achappell.hermesrelay

import org.json.JSONObject

/**
 * Converts the Home endpoint envelope into the typed events consumed by the
 * Android reducer.
 *
 * Home keeps Standard event names and cumulative-preview meaning intact. This
 * class is the only place that knows how to turn those wire payloads into
 * append-versus-replace events. Audio bytes are handled by the transport and
 * are never retained here.
 */
internal class HermesEventNormalizer(
    /** Retained for compatibility with the pre-Home fixture constructor. */
    private val clientId: String,
    /** Legacy fixtures are useful in unit tests, but the live client is Home-only. */
    private val allowLegacyFrames: Boolean = true,
) {
    private var rendered = ""
    private var streamed = false
    private var audioStarted = false

    fun beginTurn() {
        rendered = ""
        streamed = false
        audioStarted = false
    }

    /** A binary frame is delivery evidence; the bytes are deliberately dropped. */
    fun normalizeBinary(binding: AndroidTurnBinding): AndroidNormalizedEvent =
        AndroidNormalizedEvent.AudioChunkReceived(binding)

    fun normalize(text: String, fallback: AndroidTurnBinding): List<AndroidNormalizedEvent> {
        val frame = runCatching { JSONObject(text) }.getOrNull()
            ?: return listOf(AndroidNormalizedEvent.Unknown(fallback, "malformed"))

        val method = frame.optString("method")
        return when {
            method == "event" -> normalizeEndpointEvent(frame, fallback)
            method == AUDIO_METHOD -> normalizeEndpointAudio(frame, fallback)
            else -> {
                if (allowLegacyFrames) {
                    // This compatibility path keeps old deterministic fixtures
                    // useful; the live client passes false and is Home-only.
                    normalizeLegacyFrame(frame, fallback)
                } else {
                    listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
                }
            }
        }
    }

    private fun normalizeEndpointEvent(
        frame: JSONObject,
        fallback: AndroidTurnBinding,
    ): List<AndroidNormalizedEvent> {
        if (!isHomeEnvelope(frame)) {
            return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        }
        val params = frame.optJSONObject("params")
            ?: return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        if (!params.has("schema") || params.optInt("schema", 0) != SCHEMA_VERSION) {
            return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        }
        val handle = params.optString("conversation_handle")
        if (handle.isBlank()) {
            return listOf(AndroidNormalizedEvent.Unknown(fallback, "missing_handle"))
        }

        val turnId = params.optString("turn_id")
        val binding = fallback.copy(
            conversationHandle = handle,
            turnId = turnId.ifBlank { fallback.turnId },
        )
        if (handle != fallback.conversationHandle) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "conversation_mismatch"))
        }
        if (turnId.isBlank()) {
            // Home global events may omit turn_id. They cannot complete or
            // retarget the active turn, so ignore them at this boundary.
            return emptyList()
        }
        if (turnId != fallback.turnId) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "turn_mismatch"))
        }
        val event = params.optJSONObject("event")
            ?: return listOf(AndroidNormalizedEvent.Unknown(binding, "protocol_error"))
        val type = event.optString("type")
        if (type.isBlank()) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "protocol_error"))
        }
        val payload = event.optJSONObject("payload") ?: JSONObject()
        val correlationId = firstNonBlank(
            params,
            "correlation_id",
        ) ?: firstNonBlank(payload, "correlation_id", "request_id", "prompt_id", "id")

        return standardEvents(type, payload, binding, correlationId)
    }

    private fun normalizeEndpointAudio(
        frame: JSONObject,
        fallback: AndroidTurnBinding,
    ): List<AndroidNormalizedEvent> {
        if (!isHomeEnvelope(frame)) {
            return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        }
        val params = frame.optJSONObject("params")
            ?: return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        if (!params.has("schema") || params.optInt("schema", 0) != SCHEMA_VERSION) {
            return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))
        }

        val audioFrame = params.optJSONObject("frame")
            ?: return listOf(AndroidNormalizedEvent.Unknown(fallback, "protocol_error"))

        val handle = params.optString("conversation_handle")
        val turnId = params.optString("turn_id")
        val binding = fallback.copy(
            conversationHandle = handle.ifBlank { fallback.conversationHandle },
            turnId = turnId.ifBlank { fallback.turnId },
        )
        if (handle.isBlank()) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "missing_handle"))
        }
        if (turnId.isBlank()) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "missing_turn"))
        }
        if (handle != fallback.conversationHandle) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "conversation_mismatch"))
        }
        if (turnId != fallback.turnId) {
            return listOf(AndroidNormalizedEvent.Unknown(binding, "turn_mismatch"))
        }

        return when (audioFrame.optString("kind")) {
            "start" -> {
                val format = endpointAudioFormat(audioFrame)
                if (format == null) {
                    listOf(
                        AndroidNormalizedEvent.AudioFailed(
                            binding,
                            "Response audio metadata was missing or unsupported.",
                        ),
                    )
                } else {
                    audioStarted = true
                    listOf(AndroidNormalizedEvent.AudioStarted(binding, format))
                }
            }

            "end" -> {
                audioStarted = false
                listOf(AndroidNormalizedEvent.AudioEnded(binding))
            }

            "fallback", "unavailable" -> {
                audioStarted = false
                listOf(
                    AndroidNormalizedEvent.AudioFailed(
                        binding,
                        (firstNonBlank(audioFrame, "reason", "code")
                            ?: firstNonBlank(params, "reason", "code"))
                            ?.take(MAX_REASON_LENGTH)
                            ?: "Home could not provide response audio.",
                    ),
                )
            }

            else -> listOf(AndroidNormalizedEvent.Unknown(binding, "protocol_error"))
        }
    }

    private fun isHomeEnvelope(frame: JSONObject): Boolean =
        frame.optInt("schema", 0) == SCHEMA_VERSION &&
            frame.optString("jsonrpc") == "2.0"

    private fun normalizeLegacyFrame(
        frame: JSONObject,
        fallback: AndroidTurnBinding,
    ): List<AndroidNormalizedEvent> {
        val payload = frame.optJSONObject("payload") ?: frame
        val type = frame.optString("type").ifBlank { "missing" }
        val binding = legacyBindingFor(frame, payload, fallback)

        return when (type) {
            "turn_accepted" -> emptyList()
            "turn_duplicate" -> listOf(
                AndroidNormalizedEvent.TurnFailed(
                    binding,
                    payload.optString("reason").ifBlank { "already_processed" },
                ),
            )
            "status" -> statusEvent(binding, payload, frame)
            "text_delta" -> deltaEvents(
                binding,
                firstNonBlank(payload, "text", "delta", "rendered").orEmpty(),
                replace = payload.optBoolean("replace", false),
            )
            "text_final" -> finalTextEvents(
                binding,
                firstNonBlank(payload, "text", "rendered").orEmpty(),
            )
            "audio_start" -> {
                audioStarted = true
                listOf(AndroidNormalizedEvent.AudioStarted(binding, audioFormat(payload)))
            }
            "audio_file_start" -> listOf(AndroidNormalizedEvent.AudioBuffering(binding))
            "audio_end", "audio_file_end" -> {
                audioStarted = false
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
            else -> listOf(AndroidNormalizedEvent.Unknown(binding, type))
        }
    }

    private fun standardEvents(
        type: String,
        payload: JSONObject,
        binding: AndroidTurnBinding,
        correlationId: String?,
    ): List<AndroidNormalizedEvent> = when (type) {
        "gateway.ready", "status" -> statusEvent(binding, payload, JSONObject())
        "message.start" -> listOf(AndroidNormalizedEvent.Thinking(binding))
        "message.delta", "message.interim", "text_delta" -> {
            val text = firstNonBlank(payload, "rendered", "text", "delta").orEmpty()
            val cumulative = payload.has("rendered") ||
                payload.optBoolean("cumulative", false) ||
                payload.optString("mode").equals("cumulative", ignoreCase = true)
            deltaEvents(binding, text, replace = cumulative)
        }
        "message.complete" -> completeEvents(binding, payload)
        "session.interrupted", "turn.interrupted", "turn.cancelled" -> listOf(
            AndroidNormalizedEvent.TurnInterrupted(
                binding,
                firstNonBlank(payload, "reason", "status") ?: "The turn was interrupted.",
            ),
        )
        "error", "turn.error" -> listOf(
            AndroidNormalizedEvent.TurnFailed(
                binding,
                safeReason(payload),
            ),
        )
        "approval.request", "clarify.request", "secret.request", "sudo.request" -> {
            val id = correlationId ?: return listOf(
                AndroidNormalizedEvent.Unknown(binding, "protocol_error"),
            )
            listOf(
                AndroidNormalizedEvent.StructuredPrompt(
                    binding = binding,
                    type = type,
                    correlationId = id,
                    sensitive = type == "secret.request" || type == "sudo.request" ||
                        payload.optBoolean("sensitive", false) ||
                        payload.optString("sensitivity").lowercase() in HIGH_SENSITIVITY,
                    optionCount = payload.optJSONArray("options")?.length() ?: 0,
                ),
            )
        }
        "command.available" -> {
            val command = firstNonBlank(payload, "command", "name")
            if (command == null) {
                listOf(AndroidNormalizedEvent.Unknown(binding, "protocol_error"))
            } else {
                listOf(AndroidNormalizedEvent.CommandAvailable(binding, command))
            }
        }
        else -> listOf(AndroidNormalizedEvent.Unknown(binding, type))
    }

    private fun completeEvents(
        binding: AndroidTurnBinding,
        payload: JSONObject,
    ): List<AndroidNormalizedEvent> {
        val finalText = firstNonBlank(payload, "rendered", "text")
        val events = mutableListOf<AndroidNormalizedEvent>()
        if (finalText != null) {
            events += finalTextEvents(binding, finalText)
        }
        val status = payload.optString("status").lowercase()
        if (status in INTERRUPTED_STATUSES) {
            events += AndroidNormalizedEvent.TurnInterrupted(
                binding,
                status,
            )
        } else if (status in FAILED_STATUSES) {
            events += AndroidNormalizedEvent.TurnFailed(
                binding,
                status,
            )
        } else if (status.isBlank() || status in COMPLETED_STATUSES) {
            events += AndroidNormalizedEvent.TurnCompleted(binding)
        } else {
            events += AndroidNormalizedEvent.TurnFailed(
                binding,
                "Unknown terminal status: $status",
            )
        }
        return events
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
        if (!replace) {
            rendered += text
            streamed = true
            return listOf(AndroidNormalizedEvent.ResponseTextDelta(binding, text))
        }

        if (text == rendered) return emptyList()
        val event = if (text.startsWith(rendered)) {
            AndroidNormalizedEvent.ResponseTextDelta(binding, text.substring(rendered.length))
        } else {
            AndroidNormalizedEvent.ResponseTextReplace(binding, text)
        }
        rendered = text
        streamed = true
        return if (event is AndroidNormalizedEvent.ResponseTextDelta && event.text.isEmpty()) {
            emptyList()
        } else {
            listOf(event)
        }
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

    private fun audioFormat(payload: JSONObject): AndroidAudioFormat = AndroidAudioFormat(
        sampleRate = payload.optInt("sample_rate", 24_000),
        channels = payload.optInt("channels", 1),
        sampleWidth = payload.optInt("sample_width", 2),
        encoding = payload.optString("encoding").ifBlank { "pcm_s16le" },
    )

    private fun endpointAudioFormat(payload: JSONObject): AndroidAudioFormat? {
        if (
            !payload.has("sample_rate") ||
            !payload.has("channels") ||
            !payload.has("sample_width") ||
            payload.optString("byte_order") != "little"
        ) {
            return null
        }
        val format = audioFormat(payload)
        return format.takeIf { it.isSupported }
    }

    private fun legacyBindingFor(
        frame: JSONObject,
        payload: JSONObject,
        fallback: AndroidTurnBinding,
    ): AndroidTurnBinding {
        val session = firstNonBlank(payload, "session_id")
            ?: firstNonBlank(frame, "session_id")
        val turn = firstNonBlank(payload, "turn_id")
            ?: firstNonBlank(frame, "turn_id")
            ?: fallback.turnId
        return if (session == null) {
            fallback.copy(turnId = turn)
        } else {
            // Only the legacy fixture path reads this field. The Home endpoint
            // path has no Session ID to read and therefore cannot expose one.
            fallback.copy(
                conversationHandle = session,
                connectionId = session,
                turnId = turn,
            )
        }
    }

    private fun safeReason(payload: JSONObject): String =
        firstNonBlank(payload, "code", "reason", "status", "error")
            ?.take(MAX_REASON_LENGTH)
            ?: "The Home bridge reported an error."

    private fun firstNonBlank(source: JSONObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = source.optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_REASON_LENGTH = 120
        const val AUDIO_METHOD = "audio.frame"
        val INTERRUPTED_STATUSES = setOf(
            "cancelled",
            "canceled",
            "interrupted",
            "aborted",
            "stopped",
        )
        val COMPLETED_STATUSES = setOf(
            "complete",
            "completed",
            "done",
            "success",
            "succeeded",
            "ok",
        )
        val FAILED_STATUSES = setOf(
            "failed",
            "error",
            "timeout",
            "timed_out",
            "timed-out",
        )
        val HIGH_SENSITIVITY = setOf("high", "secret", "sensitive")
    }
}
