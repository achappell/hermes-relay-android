package com.achappell.hermesrelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Safe, content-free request counts for one live-gate scenario. */
internal data class LiveHomeRequestCounts(
    val promptSubmit: Int,
    val interrupt: Int,
) {
    init {
        require(promptSubmit >= 0)
        require(interrupt >= 0)
    }
}

internal data class LiveHomeHandshakeFacts(
    val connectionReady: Boolean?,
    val responseIdMatched: Boolean?,
    val unresolvedTurn: Boolean?,
    val route: AndroidRoute?,
    val capabilities: AndroidHomeCapabilities?,
)

internal data class LiveHomeTypedAudioFacts(
    val format: AndroidAudioFormat?,
    val acceptedBytes: Int?,
    val acceptedFrames: Int?,
    val audioChunkReceived: Boolean?,
    val audioFailed: Boolean?,
    val drained: Boolean?,
    val underrunCount: Int?,
    val terminalEventObserved: Boolean?,
    val finalPhase: AndroidTurnPhase?,
    val finalAudio: AndroidAudioDelivery?,
    val byteOrder: String = "little",
)

internal data class LiveHomeInterruptFacts(
    val nonTerminalStateObserved: Boolean?,
    val interruptSentCount: Int?,
    val acknowledgementObserved: Boolean?,
    val terminalEventObserved: Boolean?,
    val terminalEvent: String?,
)

internal data class LiveHomeReconnectFacts(
    val acceptedPromptSubmitCount: Int?,
    val postAcceptPromptSubmitCount: Int?,
    val peerCloseObserved: Boolean?,
    val conversationReconnectObserved: Boolean?,
    val sameConversation: Boolean?,
    val uncertaintyPreserved: Boolean?,
)

internal sealed interface LiveHomeScenarioFacts {
    data class Handshake(val value: LiveHomeHandshakeFacts) : LiveHomeScenarioFacts

    data class TypedAudio(val value: LiveHomeTypedAudioFacts) : LiveHomeScenarioFacts

    data class Interrupt(val value: LiveHomeInterruptFacts) : LiveHomeScenarioFacts

    data class Reconnect(val value: LiveHomeReconnectFacts) : LiveHomeScenarioFacts
}

internal data class LiveHomeScenarioEvidence(
    val available: Boolean,
    val requestCounts: LiveHomeRequestCounts,
    val facts: LiveHomeScenarioFacts?,
)

internal data class LiveHomeScenarioResult(
    val runId: String,
    val scenario: String,
    val status: String,
    val reason: String?,
    val evidence: LiveHomeScenarioEvidence,
)

/**
 * Device-side handoff for the live Home gate.
 *
 * The writer accepts typed projections only. It never accepts a raw frame,
 * prompt, credential, handle, turn ID, exception, or PCM buffer, and it
 * refuses to replace an existing scenario result.
 */
internal object LiveHomeSafeResult {
    const val SCHEMA_VERSION = 2
    const val CACHE_DIRECTORY = "hermes-live-home"

    private val scenarios = setOf("handshake", "typed_audio", "interrupt", "reconnect")
    private val statuses = setOf("pass", "fail", "inconclusive", "not-run")
    private val reasons = setOf(
        "DEVICE_SELECTION",
        "WRONG_DEVICE",
        "AUDIO_OUTPUT",
        "MISSING_PAIRING",
        "INVALID_BINDING",
        "HOME_404",
        "HOME_UNREACHABLE",
        "HOME_UNAVAILABLE",
        "UNRESOLVED_TURN",
        "CAPABILITY_SHAPE_INVALID",
        "CAPABILITY_UNAVAILABLE",
        "HOME_PROVENANCE",
        "CONTROLLED_CLOSE",
        "NATURAL_COMPLETION_RACE",
        "TERMINAL_TIMEOUT",
        "AUDIO_FAILURE",
        "RECONNECT_TRACE",
        "HARNESS_FAILURE",
    )
    private val routeClasses = setOf("home", "tailscale", "public")
    private val phases = AndroidTurnPhase.entries.associateBy { it.name }
    private val audioStates = AndroidAudioDelivery.entries.associateBy { it.name }

    fun write(context: Context, result: LiveHomeScenarioResult): Boolean {
        synchronized(this) {
            val validation = validate(result)
            if (!validation) return false
            val directory = File(context.cacheDir, "$CACHE_DIRECTORY/${result.runId}")
            if (!directory.exists() && !directory.mkdirs()) return false
            val finalFile = File(directory, "${result.scenario}.json")
            if (finalFile.exists()) return false
            val temporary = runCatching {
                File.createTempFile(".${result.scenario}-", ".tmp", directory)
            }.getOrNull() ?: return false
            return runCatching {
                temporary.setReadable(false, false)
                temporary.setWritable(true, true)
                temporary.writeText(toJson(result).toString())
                if (finalFile.exists() || !temporary.renameTo(finalFile)) {
                    temporary.delete()
                    false
                } else {
                    finalFile.setReadable(false, false)
                    finalFile.setReadable(true, true)
                    true
                }
            }.getOrElse {
                temporary.delete()
                false
            }
        }
    }

    fun validate(result: LiveHomeScenarioResult): Boolean {
        if (!isValidRunId(result.runId) || result.scenario !in scenarios) return false
        if (result.status !in statuses) return false
        if (result.status == "pass" && result.reason != null) return false
        if (result.status != "pass" && result.reason !in reasons) return false
        val evidence = result.evidence
        if (result.status == "pass" && (!evidence.available || evidence.facts == null)) return false
        if (evidence.available != (evidence.facts != null)) return false
        if (!validateFacts(result.scenario, evidence.facts)) return false
        return result.status != "pass" || validatePassingFacts(result.scenario, evidence.facts)
    }

    fun toJson(result: LiveHomeScenarioResult): JSONObject {
        check(validate(result))
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("run_id", result.runId)
            .put("scenario", result.scenario)
            .put("status", result.status)
            .putNullable("reason", result.reason)
            .put(
                "evidence",
                JSONObject()
                    .put("available", result.evidence.available)
                    .put(
                        "request_counts",
                        JSONObject()
                            .put("observed", true)
                            .put("prompt_submit", result.evidence.requestCounts.promptSubmit)
                            .put("interrupt", result.evidence.requestCounts.interrupt),
                    )
                    .putNullable("facts", result.evidence.facts?.let(::factsJson)),
            )
    }

    private fun validateFacts(
        scenario: String,
        facts: LiveHomeScenarioFacts?,
    ): Boolean {
        if (facts == null) return true
        return when (scenario) {
            "handshake" -> (facts as? LiveHomeScenarioFacts.Handshake)
                ?.value
                ?.let(::validateHandshake) == true
            "typed_audio" -> (facts as? LiveHomeScenarioFacts.TypedAudio)
                ?.value
                ?.let(::validateTypedAudio) == true
            "interrupt" -> (facts as? LiveHomeScenarioFacts.Interrupt)
                ?.value
                ?.let(::validateInterrupt) == true
            "reconnect" -> (facts as? LiveHomeScenarioFacts.Reconnect)
                ?.value
                ?.let(::validateReconnect) == true
            else -> false
        }
    }

    private fun validateHandshake(facts: LiveHomeHandshakeFacts): Boolean =
        validateRoute(facts.route) && validateCapabilities(facts.capabilities)

    private fun validateTypedAudio(facts: LiveHomeTypedAudioFacts): Boolean =
        validateFormat(facts.format, facts.byteOrder) &&
            validateCount(facts.acceptedBytes) &&
            validateCount(facts.acceptedFrames) &&
            validateCount(facts.underrunCount) &&
            (facts.finalPhase == null || facts.finalPhase.name in phases) &&
            (facts.finalAudio == null || facts.finalAudio.name in audioStates)

    private fun validateInterrupt(facts: LiveHomeInterruptFacts): Boolean =
        validateCount(facts.interruptSentCount) &&
            (facts.terminalEvent == null || facts.terminalEvent in setOf("completed", "interrupted"))

    private fun validateReconnect(facts: LiveHomeReconnectFacts): Boolean =
        validateCount(facts.acceptedPromptSubmitCount) &&
            validateCount(facts.postAcceptPromptSubmitCount)

    private fun validatePassingFacts(scenario: String, facts: LiveHomeScenarioFacts?): Boolean =
        when (scenario) {
            "handshake" -> (facts as? LiveHomeScenarioFacts.Handshake)?.value?.let {
                it.connectionReady == true &&
                    it.responseIdMatched == true &&
                    it.unresolvedTurn == false &&
                    it.route != null &&
                    it.capabilities != null
            } == true
            "typed_audio" -> (facts as? LiveHomeScenarioFacts.TypedAudio)?.value?.let {
                it.format != null &&
                    it.acceptedBytes != null && it.acceptedBytes > 0 &&
                    it.acceptedFrames != null && it.acceptedFrames > 0 &&
                    it.audioChunkReceived == true &&
                    it.audioFailed == false &&
                    it.drained == true &&
                    it.underrunCount == 0 &&
                    it.terminalEventObserved == true &&
                    it.finalPhase == AndroidTurnPhase.Complete &&
                    it.finalAudio == AndroidAudioDelivery.Delivered
            } == true
            "interrupt" -> (facts as? LiveHomeScenarioFacts.Interrupt)?.value?.let {
                it.nonTerminalStateObserved == true &&
                    it.interruptSentCount == 1 &&
                    it.acknowledgementObserved == true &&
                    it.terminalEventObserved == true &&
                    it.terminalEvent == "interrupted"
            } == true
            "reconnect" -> (facts as? LiveHomeScenarioFacts.Reconnect)?.value?.let {
                it.acceptedPromptSubmitCount == 1 &&
                    it.postAcceptPromptSubmitCount == 0 &&
                    it.peerCloseObserved == true &&
                    it.conversationReconnectObserved == true &&
                    it.sameConversation == true &&
                    it.uncertaintyPreserved == true
            } == true
            else -> false
        }

    private fun validateRoute(route: AndroidRoute?): Boolean = route == null || (
        route.routeClass in routeClasses && isSafeIdentity(route.routeId)
        )

    private fun validateCapabilities(capabilities: AndroidHomeCapabilities?): Boolean =
        capabilities == null || (
            capabilities.heartbeat &&
                capabilities.timing == "absent" &&
                capabilities.commands.isEmpty()
            )

    private fun validateFormat(format: AndroidAudioFormat?, byteOrder: String): Boolean =
        format == null || (
            byteOrder == "little" &&
                format.isSupported &&
                format.sampleRate in 8_000..192_000 &&
                format.sampleWidth == 2 &&
                format.encoding == "pcm_s16le"
            )

    private fun validateCount(value: Int?): Boolean = value == null || value >= 0

    private fun isValidRunId(value: String): Boolean =
        value.length in 1..128 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))

    private fun isSafeIdentity(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size in 1..256 &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || it.isWhitespace() }

    private fun factsJson(facts: LiveHomeScenarioFacts): JSONObject = when (facts) {
        is LiveHomeScenarioFacts.Handshake -> facts.value.toJson()
        is LiveHomeScenarioFacts.TypedAudio -> facts.value.toJson()
        is LiveHomeScenarioFacts.Interrupt -> facts.value.toJson()
        is LiveHomeScenarioFacts.Reconnect -> facts.value.toJson()
    }

    private fun LiveHomeHandshakeFacts.toJson(): JSONObject = JSONObject()
        .putNullable("connection_ready", connectionReady)
        .putNullable("response_id_matched", responseIdMatched)
        .putNullable("unresolved_turn", unresolvedTurn)
        .putNullable("route", route?.let { JSONObject().put("class", it.routeClass).put("id", it.routeId) })
        .putNullable("capabilities", capabilities?.let {
            JSONObject()
                .put("heartbeat", it.heartbeat)
                .put("timing", it.timing)
                .put("commands", JSONArray(it.commands.toList().sorted()))
                .put("interrupt", it.interrupt)
                .put("audio", it.audio)
        })

    private fun LiveHomeTypedAudioFacts.toJson(): JSONObject = JSONObject()
        .putNullable("format", format?.let {
            JSONObject()
                .put("sample_rate", it.sampleRate)
                .put("channels", it.channels)
                .put("sample_width", it.sampleWidth)
                .put("byte_order", byteOrder)
                .put("encoding", it.encoding)
        })
        .putNullable("accepted_bytes", acceptedBytes)
        .putNullable("accepted_frames", acceptedFrames)
        .putNullable("audio_chunk_received", audioChunkReceived)
        .putNullable("audio_failed", audioFailed)
        .putNullable("drained", drained)
        .putNullable("underrun_count", underrunCount)
        .putNullable("terminal_event_observed", terminalEventObserved)
        .putNullable("final_phase", finalPhase?.name)
        .putNullable("final_audio", finalAudio?.name)

    private fun LiveHomeInterruptFacts.toJson(): JSONObject = JSONObject()
        .putNullable("non_terminal_state_observed", nonTerminalStateObserved)
        .putNullable("interrupt_sent_count", interruptSentCount)
        .putNullable("acknowledgement_observed", acknowledgementObserved)
        .putNullable("terminal_event_observed", terminalEventObserved)
        .putNullable("terminal_event", terminalEvent)

    private fun LiveHomeReconnectFacts.toJson(): JSONObject = JSONObject()
        .putNullable("accepted_prompt_submit_count", acceptedPromptSubmitCount)
        .putNullable("post_accept_prompt_submit_count", postAcceptPromptSubmitCount)
        .putNullable("peer_close_observed", peerCloseObserved)
        .putNullable("conversation_reconnect_observed", conversationReconnectObserved)
        .putNullable("same_conversation", sameConversation)
        .putNullable("uncertainty_preserved", uncertaintyPreserved)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)
}
