package com.achappell.hermesrelay

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.logging.Level
import java.util.logging.Logger
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// =============================================================================
// ANDROID-STD-01 slice 1, step 1: the Baseline check.
//
// A scripted probe for the household Standard 0.21.5 endpoint. It lives under
// the test tooling (never shipped) and drives the REAL OkHttpStandardSessionClient
// so it exercises the same adapter the app ships. It records PASS / FAIL /
// NOT-REPRODUCIBLE per check for validation-android-std-01.md.
//
// Redaction is a hard rule: the record, stdout, exception text and journal
// lines carry names, counts, booleans, durations, sample rates and versions
// only. Never a token, endpoint query, Hermes session/durable id, prompt,
// response, transcript or audio byte.
// =============================================================================

internal enum class BaselineStatus(val label: String) {
    Pass("PASS"),
    Fail("FAIL"),
    NotReproducible("NOT-REPRODUCIBLE"),
    NotRun("NOT-RUN"),
}

internal data class BaselineCheck(
    val number: Int,
    val name: String,
    val status: BaselineStatus,
    /** Already-sanitised key/value facts. */
    val facts: List<Pair<String, String>> = emptyList(),
) {
    fun fact(key: String): String? = facts.firstOrNull { it.first == key }?.second
}

internal data class StandardBaselineRecord(
    val hermesProfile: String,
    val generatedAt: String,
    val checks: List<BaselineCheck>,
    /** Null when the audio check did not run. */
    val audioUsable: Boolean?,
    val journalLines: List<String>,
) {
    fun check(number: Int): BaselineCheck = checks.first { it.number == number }

    val verdict: String
        get() = when {
            check(1).status != BaselineStatus.Pass ->
                "STOP: the Standard session could not be created; fix the endpoint or token and rerun the probe"
            audioUsable == false -> AUDIO_STOP
            checks.any { it.status == BaselineStatus.Fail } ->
                "REVIEW: " + checks.filter { it.status == BaselineStatus.Fail }
                    .joinToString(", ") { "check ${it.number} ${it.name}" } + " failed"
            else -> "PROCEED: baseline checks passed; NOT-REPRODUCIBLE items stay as live gates"
        }

    fun toMarkdown(): String = buildString {
        appendLine("# Standard baseline check (ANDROID-STD-01)")
        appendLine()
        appendLine("- Generated: $generatedAt")
        appendLine("- Hermes profile: $hermesProfile")
        appendLine("- Verdict: $verdict")
        appendLine()
        appendLine("## Checks")
        checks.sortedBy { it.number }.forEach { check ->
            appendLine()
            appendLine("### ${check.number}. ${check.name}: ${check.status.label}")
            check.facts.forEach { (key, value) -> appendLine("- $key: $value") }
        }
        appendLine()
        appendLine("## Adapter journal (event names, counts, durations only)")
        if (journalLines.isEmpty()) appendLine("- none")
        journalLines.forEach { appendLine("- $it") }
        appendLine()
        appendLine(
            "Redaction: no token, endpoint query, Hermes session id, prompt, response, " +
                "transcript or audio bytes are recorded.",
        )
    }

    companion object {
        const val AUDIO_STOP =
            "STOP: audio unusable — bring the Q1 choice (on-device Android TTS vs text-only) back to Amanda"
    }
}

internal class StandardBaselineProbe(
    private val endpoint: String,
    private val hermesProfile: String,
    private val token: String,
    private val httpClient: OkHttpClient,
    private val userPrompt: String = DEFAULT_PROMPT,
    private val requestTimeoutMillis: Long = 30_000,
    private val turnTimeoutMillis: Long = 120_000,
    private val audioTimeoutMillis: Long = 60_000,
    /** How long after the interrupt Hermes may take to end the turn before it counts as unsupported. */
    private val interruptWindowMillis: Long = 4_000,
    /** Progress sink: check number, name and status only. */
    private val progress: (String) -> Unit = {},
) {
    private class RecordingJournal : DiagnosticsJournal {
        private val entries = CopyOnWriteArrayList<JournalEntry>()
        @Volatile var interruptTerminal: CountDownLatch? = null

        override fun record(event: String) {
            entries += JournalEntry(System.currentTimeMillis(), event)
            if (event.startsWith("standard finishing terminal outcome=")) {
                interruptTerminal?.countDown()
            }
        }

        override fun snapshot(): List<JournalEntry> = entries.toList()
    }

    private class PromptSighting(val type: String, val sensitive: Boolean, val optionCount: Int)

    /** Accumulates names and counts from normalised events. Never text. */
    private class TurnTrace(private val sightings: MutableList<PromptSighting>) {
        val kinds = sortedSetOf<String>()
        var unknownCount = 0
        var textEvents = 0
        var textChars = 0
        var terminal: String? = null

        fun accept(event: AndroidNormalizedEvent) {
            kinds += event::class.java.simpleName
            when (event) {
                is AndroidNormalizedEvent.Unknown -> unknownCount++
                is AndroidNormalizedEvent.StructuredPrompt ->
                    sightings += PromptSighting(eventName(event.type), event.sensitive, event.optionCount)
                is AndroidNormalizedEvent.ResponseTextDelta -> {
                    textEvents++
                    textChars += event.text.length
                }
                is AndroidNormalizedEvent.ResponseTextReplace -> {
                    textEvents++
                    textChars = event.text.length
                }
                is AndroidNormalizedEvent.TurnCompleted -> terminal = "completed"
                is AndroidNormalizedEvent.TurnFailed -> terminal = "failed"
                is AndroidNormalizedEvent.TurnInterrupted -> terminal = "interrupted"
                is AndroidNormalizedEvent.Disconnected -> terminal = "disconnected"
                else -> Unit
            }
        }
    }

    private val secrets = listOf(token, userPrompt, endpoint).filter { it.isNotBlank() }
    private val sightings = CopyOnWriteArrayList<PromptSighting>()
    private val transportSocket = AtomicReference<Socket>()
    private val probeHttpClient = httpClient.newBuilder()
        .eventListener(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                if (call.request().url.encodedPath.endsWith("/api/ws")) {
                    transportSocket.set(connection.socket())
                }
            }
        })
        .build()

    private fun clean(value: String): String =
        secrets.fold(value) { acc, secret -> acc.replace(secret, "<redacted>") }

    private fun safe(value: String): String {
        val cleaned = clean(value)
        return if (SAFE_VALUE.matches(cleaned)) cleaned else "<redacted>"
    }

    private fun f(key: String, value: Any?): Pair<String, String> = key to safe(value.toString())

    private fun check(
        number: Int,
        name: String,
        status: BaselineStatus,
        vararg facts: Pair<String, String>,
    ): BaselineCheck = BaselineCheck(number, name, status, facts.toList()).also {
        progress("check $number $name: ${status.label}")
    }

    private fun notRun(number: Int, name: String, why: String) =
        check(number, name, BaselineStatus.NotRun, f("reason", why))

    /** An unexpected failure records its class name only; never the message. */
    private fun guarded(number: Int, name: String, block: () -> BaselineCheck): BaselineCheck =
        try {
            block()
        } catch (failure: Exception) {
            check(number, name, BaselineStatus.Fail, f("exception", failure::class.java.simpleName))
        }

    fun run(): StandardBaselineRecord {
        val journal = RecordingJournal()
        val profile = RelayProfile(
            id = PROFILE_ID,
            endpoint = endpoint,
            clientId = "android",
            deviceId = "",
            displayName = "Baseline probe",
            mode = RelayProfileMode.Standard,
            hermesProfile = hermesProfile,
        )
        val collection = RelayProfileCollection().add(profile)
        val client = OkHttpStandardSessionClient(
            collection = { collection },
            credentials = InMemoryRelayCredentialStore(standardCredentials = mapOf(PROFILE_ID to token)),
            httpClient = probeHttpClient,
            readyTimeoutMillis = requestTimeoutMillis,
            requestTimeoutMillis = requestTimeoutMillis,
            remoteInterruptVerified = true,
            journal = journal,
        )
        val checks = mutableListOf<BaselineCheck>()
        var audioUsable: Boolean? = null
        try {
            val create = guarded(1, "session.create") { checkCreate(client) }
            checks += create
            if (create.status != BaselineStatus.Pass) {
                checks += notRun(2, "typed turn events", "session.create did not pass")
                checks += notRun(3, "structured prompt", "session.create did not pass")
                checks += notRun(4, "audio speak-stream", "session.create did not pass")
                checks += notRun(5, "interrupt", "session.create did not pass")
                checks += notRun(6, "dropped socket and reconnect", "session.create did not pass")
            } else {
                val typed = guarded(2, "typed turn events") { checkTypedTurn(client) }
                checks += typed
                var audio: BaselineCheck
                try {
                    audio = checkAudio()
                    audioUsable = audio.status == BaselineStatus.Pass
                } catch (failure: Exception) {
                    audioUsable = false
                    audio = check(
                        4, "audio speak-stream", BaselineStatus.Fail,
                        f("exception", failure::class.java.simpleName),
                    )
                }
                checks += audio
                if (audioUsable == false) {
                    checks += notRun(5, "interrupt", "stopped at audio (Q1)")
                    checks += notRun(6, "dropped socket and reconnect", "stopped at audio (Q1)")
                } else {
                    checks += if (typed.status == BaselineStatus.Pass) {
                        guarded(5, "interrupt") { checkInterrupt(client, journal) }
                    } else {
                        notRun(5, "interrupt", "typed turn did not pass")
                    }
                    checks += guarded(6, "dropped socket and reconnect") { checkReconnect(client, journal) }
                }
                checks += structuredPromptCheck()
            }
        } finally {
            client.close()
        }
        return StandardBaselineRecord(
            hermesProfile = "<redacted>",
            generatedAt = Instant.now().toString(),
            checks = checks.sortedBy { it.number },
            audioUsable = audioUsable,
            journalLines = journal.snapshot().map { it.event }.filter { it in SAFE_JOURNAL_LINES },
        )
    }

    // ---- 1 ------------------------------------------------------------------

    private fun checkCreate(client: OkHttpStandardSessionClient): BaselineCheck {
        val started = System.nanoTime()
        val outcome = client.reconnect()
        val ms = elapsedMillis(started)
        return when (outcome) {
            is AndroidReconnectOutcome.Connected -> check(
                1, "session.create", BaselineStatus.Pass,
                f("session_reference_returned", true),
                f("started_fresh", outcome.sessionStartedFresh),
                f("duration_ms", ms),
            )
            is AndroidReconnectOutcome.Retryable -> check(
                1, "session.create", BaselineStatus.Fail,
                f("session_reference_returned", false),
                f("reason", outcome.reasonCode?.name ?: "none"),
            )
            is AndroidReconnectOutcome.Unrecoverable -> check(
                1, "session.create", BaselineStatus.Fail,
                f("session_reference_returned", false),
                f("reason", outcome.reasonCode?.name ?: "none"),
            )
        }
    }

    // ---- 2 ------------------------------------------------------------------

    private fun checkTypedTurn(client: OkHttpStandardSessionClient): BaselineCheck {
        val started = System.nanoTime()
        val begin = client.beginTurn(typed(userPrompt))
        if (begin !is AndroidInitiationResult.Accepted) {
            return check(2, "typed turn events", BaselineStatus.Fail, f("initiation", describe(begin)))
        }
        val trace = TurnTrace(sightings)
        val queue = LinkedBlockingQueue<AndroidNormalizedEvent>()
        val observation = client.observeTurn(begin.binding) { queue.add(it) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(turnTimeoutMillis)
        while (trace.terminal == null) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            trace.accept(queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break)
        }
        observation.cancel()
        settle(client, begin.binding)
        val structured = sightings.isNotEmpty()
        val passed = trace.textEvents > 0 &&
            (trace.terminal == "completed" || (trace.terminal == "interrupted" && structured))
        return check(
            2, "typed turn events", if (passed) BaselineStatus.Pass else BaselineStatus.Fail,
            f("event_kinds", trace.kinds.joinToString(",").ifEmpty { "none" }),
            f("unknown_event_count", trace.unknownCount),
            f("text_events", trace.textEvents),
            f("text_chars", trace.textChars),
            f("terminal", trace.terminal ?: "timeout"),
            f("duration_ms", elapsedMillis(started)),
        )
    }

    // ---- 3 ------------------------------------------------------------------

    private fun structuredPromptCheck(): BaselineCheck {
        val seen = sightings.toList()
        if (seen.isEmpty()) {
            return check(
                3, "structured prompt", BaselineStatus.NotReproducible,
                f("note", "none appeared during checks 2 and 5; the probe cannot force one"),
            )
        }
        return check(
            3, "structured prompt", BaselineStatus.Pass,
            f("types", seen.map { it.type }.distinct().sorted().joinToString(",")),
            f("count", seen.size),
            f("any_sensitive", seen.any { it.sensitive }),
            f("max_option_count", seen.maxOf { it.optionCount }),
        )
    }

    // ---- 4 ------------------------------------------------------------------

    private sealed interface AudioFrame {
        class Json(val type: String, val body: JSONObject) : AudioFrame

        class Binary(val size: Int) : AudioFrame

        class Failed(val httpStatus: Int?) : AudioFrame

        data object Closed : AudioFrame

        data object Malformed : AudioFrame
    }

    private fun audioUrl(): HttpUrl? {
        val base = StandardWire.url(endpoint, hermesProfile, token) ?: return null
        val path = base.encodedPath.trimEnd('/')
        val audioPath = if (path.endsWith("/ws")) {
            path.removeSuffix("/ws") + "/audio/speak-stream"
        } else {
            "$path/audio/speak-stream"
        }
        return base.newBuilder().encodedPath(audioPath).build()
    }

    private fun checkAudio(): BaselineCheck {
        val url = audioUrl() ?: return check(
            4, "audio speak-stream", BaselineStatus.Fail, f("reason", "endpoint_not_usable"),
        )
        val frames = LinkedBlockingQueue<AudioFrame>()
        val socket = httpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("text", "Ready.").toString())
                    webSocket.send(JSONObject().put("done", true).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val body = runCatching { JSONObject(text) }.getOrNull()
                    frames.add(
                        if (body == null) {
                            AudioFrame.Malformed
                        } else {
                            AudioFrame.Json(body.optString("type").lowercase(), body)
                        },
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames.add(AudioFrame.Binary(bytes.size))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    frames.add(AudioFrame.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    frames.add(AudioFrame.Failed(response?.code))
                }
            },
        )
        var sampleRate = -1
        var channels = -1
        var width = -1
        var byteOrder = "unknown"
        var started = false
        var binaryFrames = 0
        var totalBytes = 0L
        var terminal = "timeout"
        var httpStatus: Int? = null
        var otherFrames = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(audioTimeoutMillis)
        loop@ while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            when (val frame = frames.poll(remaining, TimeUnit.NANOSECONDS) ?: break) {
                is AudioFrame.Binary -> {
                    binaryFrames++
                    totalBytes += frame.size
                }
                is AudioFrame.Json -> when (frame.type) {
                    "start" -> {
                        started = true
                        sampleRate = frame.body.optInt("sample_rate", -1)
                        channels = frame.body.optInt("channels", -1)
                        width = frame.body.optInt("sample_width", 2)
                        byteOrder = frame.body.optString("byte_order", "little").lowercase()
                    }
                    "end" -> {
                        terminal = "end"
                        break@loop
                    }
                    "fallback" -> {
                        terminal = "fallback"
                        break@loop
                    }
                    "speech_timing" -> Unit
                    else -> otherFrames = true
                }
                is AudioFrame.Malformed -> {
                    otherFrames = true
                    terminal = "malformed"
                    break@loop
                }
                is AudioFrame.Failed -> {
                    httpStatus = frame.httpStatus
                    terminal = "failed"
                    break@loop
                }
                AudioFrame.Closed -> {
                    terminal = "closed"
                    break@loop
                }
            }
        }
        socket.cancel()

        val littleEndian = byteOrder in setOf("little", "le", "little-endian")
        val encoding = if (width == 2 && littleEndian) "pcm_s16le" else "pcm_unsupported"
        val format = AndroidAudioFormat(sampleRate, channels, width, encoding)
        val usable = started && terminal == "end" && !otherFrames && binaryFrames > 0 &&
            totalBytes > 0 && totalBytes % (width.coerceAtLeast(1) * channels.coerceAtLeast(1)) == 0L &&
            format.isSupported && channels == 1 && littleEndian
        return check(
            4, "audio speak-stream",
            if (usable) BaselineStatus.Pass else BaselineStatus.Fail,
            f("format", encoding),
            f("sample_rate", sampleRate),
            f("channels", channels),
            f("sample_width_bytes", width),
            f("byte_order", if (littleEndian) "little" else "other"),
            f("binary_frames", binaryFrames),
            f("chunked", binaryFrames > 1),
            f("total_bytes", totalBytes),
            f("terminal", terminal),
            f("http_status", httpStatus ?: "none"),
            f("usable", usable),
        )
    }

    // ---- 5 ------------------------------------------------------------------

    private fun checkInterrupt(
        client: OkHttpStandardSessionClient,
        journal: RecordingJournal,
    ): BaselineCheck {
        val remoteTerminal = CountDownLatch(1)
        journal.interruptTerminal = remoteTerminal
        val before = journal.snapshot().size
        try {
            val begin = client.beginTurn(typed(LONG_PROMPT))
            if (begin !is AndroidInitiationResult.Accepted) {
                return check(5, "interrupt", BaselineStatus.Fail, f("initiation", describe(begin)))
            }
            val trace = TurnTrace(sightings)
            val queue = LinkedBlockingQueue<AndroidNormalizedEvent>()
            val observation = client.observeTurn(begin.binding) { queue.add(it) }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(turnTimeoutMillis)
            while (trace.textEvents == 0 && trace.terminal == null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                trace.accept(queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break)
            }
            if (trace.textEvents == 0) {
                observation.cancel()
                settle(client, begin.binding)
                return check(
                    5, "interrupt", BaselineStatus.NotReproducible,
                    f("note", "no streamed delta arrived before the turn ended or timed out"),
                    f("terminal", trace.terminal ?: "timeout"),
                )
            }
            val charsBefore = trace.textChars
            val interrupted = client.interruptTurn(begin.binding)
            observation.cancel()
            if (!interrupted) {
                return check(
                    5, "interrupt", BaselineStatus.NotReproducible,
                    f("note", "the turn ended before the interrupt could be sent"),
                )
            }
            // The local stop event and a natural remote completion are not
            // evidence of cancellation. Require a correlated positive RPC ack
            // and an explicit remote interrupt terminal.
            val remoteEnded = remoteTerminal.await(interruptWindowMillis, TimeUnit.MILLISECONDS)
            val evidence = journal.snapshot().drop(before).map { it.event }
            val sent = "standard stop local=true remote=sent" in evidence
            val acknowledged = "standard interrupt acknowledged" in evidence
            val remoteInterrupted = "standard finishing terminal outcome=interrupted" in evidence
            val passed = sent && acknowledged && remoteEnded && remoteInterrupted
            return check(
                5, "interrupt",
                if (passed) BaselineStatus.Pass else BaselineStatus.Fail,
                f("interrupt_sent", sent),
                f("interrupt_acknowledged", acknowledged),
                f("remote_interrupt_terminal", remoteInterrupted),
                f("turn_ended_early", passed),
                f("chars_before_interrupt", charsBefore),
                f("remote_terminal_observed", remoteEnded),
                f("window_ms", interruptWindowMillis),
                f("interrupt", if (passed) "supported" else "unsupported"),
            )
        } finally {
            journal.interruptTerminal = null
        }
    }

    // ---- 6 ------------------------------------------------------------------

    private fun checkReconnect(
        client: OkHttpStandardSessionClient,
        journal: RecordingJournal,
    ): BaselineCheck {
        fun promptLines() = journal.snapshot().count { it.event.contains("method=prompt.submit") }
        val before = promptLines()
        val disconnected = CountDownLatch(1)
        val observation = client.observeConnection { disconnected.countDown() }
        val dropped = try {
            val socket = transportSocket.getAndSet(null)
            if (socket == null) false else {
                socket.close()
                disconnected.await(requestTimeoutMillis, TimeUnit.MILLISECONDS)
            }
        } finally {
            observation.cancel()
        }
        if (!dropped) {
            return check(
                6, "dropped socket and reconnect", BaselineStatus.Fail,
                f("transport_loss_observed", false),
            )
        }
        val started = System.nanoTime()
        val outcome = client.reconnect()
        val promptsDuringRecovery = promptLines() - before
        if (outcome !is AndroidReconnectOutcome.Connected) {
            val reason = when (outcome) {
                is AndroidReconnectOutcome.Retryable -> outcome.reasonCode?.name
                is AndroidReconnectOutcome.Unrecoverable -> outcome.reasonCode?.name
                else -> null
            }
            return check(
                6, "dropped socket and reconnect", BaselineStatus.Fail,
                f("reconnected", false),
                f("reason", reason ?: "none"),
                f("prompts_sent_during_recovery", promptsDuringRecovery),
            )
        }
        val resumed = !outcome.sessionStartedFresh
        return check(
            6, "dropped socket and reconnect",
            if (resumed && promptsDuringRecovery == 0) BaselineStatus.Pass else BaselineStatus.Fail,
            f("reconnected", true),
            f("transport_loss_observed", true),
            f("session_resumed", resumed),
            f("started_fresh", outcome.sessionStartedFresh),
            f("prompts_sent_during_recovery", promptsDuringRecovery),
            f("duration_ms", elapsedMillis(started)),
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun typed(text: String) =
        AndroidTurnRequest(AndroidProfile(PROFILE_ID, "Baseline probe"), AndroidTurnInput.Typed(text))

    /**
     * The adapter delivers the terminal event to the observer and only then
     * clears its active turn, all under its inbound lock. Taking that lock
     * (observing) is a barrier that makes the next prompt deterministic.
     */
    private fun settle(client: OkHttpStandardSessionClient, binding: AndroidTurnBinding) {
        client.observeTurn(binding) {}.cancel()
    }

    private fun describe(result: AndroidInitiationResult): String = when (result) {
        is AndroidInitiationResult.Accepted -> "accepted"
        is AndroidInitiationResult.Rejected -> "rejected_${result.reason.name}"
        is AndroidInitiationResult.Uncertain -> "uncertain_${result.reason.name}"
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    companion object {
        const val DEFAULT_PROMPT = "Reply with the single word ready."

        /** Fixed internal prompt for the interrupt check; never the user's. */
        const val LONG_PROMPT =
            "Write a very long, detailed essay of at least 3000 words about the history of the " +
                "bicycle. Keep going until you reach the full length."

        private const val PROFILE_ID = "standard-baseline-probe"
        private val SAFE_VALUE = Regex("[A-Za-z0-9 _.,:=/()+<>-]{0,200}")
        private val SAFE_EVENT_NAMES = setOf("approval.request", "input.request", "prompt.request")
        private val SAFE_JOURNAL_LINES = setOf(
            "standard transport lost",
            "standard client close",
            "standard interrupt acknowledged",
            "standard finishing terminal outcome=interrupted",
            "standard finishing terminal outcome=completed",
            "standard finishing terminal outcome=failed",
            "standard stop local=true remote=sent",
            "standard stop local=true remote=failed",
            "standard finishing state=true",
            "standard finishing state=false",
        )

        fun eventName(raw: String): String = if (raw in SAFE_EVENT_NAMES) raw else "<redacted>"
    }
}

// =============================================================================
// Live run: skipped unless the env vars are set.
// =============================================================================

class StandardBaselineProbeLiveTest {
    @Test
    fun run_the_baseline_probe_against_the_live_standard_endpoint() {
        val endpoint = System.getenv("HERMES_STANDARD_PROBE_ENDPOINT").orEmpty().trim()
        assumeTrue("explicit live opt-in required", System.getenv("HERMES_STANDARD_PROBE_LIVE") == "1")
        val token = System.getenv("HERMES_STANDARD_PROBE_TOKEN").orEmpty().trim()
        assumeTrue("HERMES_STANDARD_PROBE_ENDPOINT not set", endpoint.isNotEmpty())
        assumeTrue("HERMES_STANDARD_PROBE_TOKEN not set", token.isNotEmpty())
        assumeTrue(
            "endpoint must be a normalised wss://host:port/api/ws URL",
            runCatching {
                val uri = URI(endpoint)
                uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null && uri.rawQuery == null &&
                    uri.rawFragment == null && uri.path.endsWith("/api/ws")
            }.getOrDefault(false),
        )
        val profile = System.getenv("HERMES_STANDARD_PROBE_PROFILE").orEmpty().trim().ifEmpty { "default" }
        val prompt = System.getenv("HERMES_STANDARD_PROBE_PROMPT").orEmpty().trim()
            .ifEmpty { StandardBaselineProbe.DEFAULT_PROMPT }

        val record = try {
            StandardBaselineProbe(
                endpoint = endpoint,
                hermesProfile = profile,
                token = token,
                httpClient = OkHttpStandardSessionClient.defaultClient(),
                userPrompt = prompt,
                progress = { println(it) },
            ).run()
        } catch (_: Exception) {
            // Do not attach the cause: HTTP/TLS exceptions may contain a token URL.
            throw AssertionError("Standard baseline probe failed before a redacted record was available")
        }

        val markdown = record.toMarkdown()
        val out = File("build/standard-baseline-probe.md")
        out.parentFile?.mkdirs()
        out.writeText(markdown)
        println(markdown)
        assertFalse("the record leaked the token", markdown.contains(token))
    }
}

// =============================================================================
// Always-running proof of the probe against a local fake Standard gateway.
// =============================================================================

class StandardBaselineProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates
    private val serverLogger = Logger.getLogger(MockWebServer::class.java.name)
    private var previousLogLevel: Level? = null

    @Before
    fun setUp() {
        previousLogLevel = serverLogger.level
        serverLogger.level = Level.OFF // Request URLs contain query credentials.
        server = MockWebServer()
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1")
            .build()
        server.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(serverCertificate)
                .build()
                .sslSocketFactory(),
            false,
        )
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } finally {
            serverLogger.level = previousLogLevel
        }
    }

    @Test
    fun every_check_passes_against_a_complete_gateway() {
        val fake = serve(FakeStandard())

        val record = probe().run()

        assertEquals(BaselineStatus.Pass, record.check(1).status)
        assertEquals("true", record.check(1).fact("session_reference_returned"))
        assertEquals(BaselineStatus.Pass, record.check(2).status)
        assertEquals("0", record.check(2).fact("unknown_event_count"))
        assertTrue(record.check(2).fact("event_kinds")!!.contains("ResponseTextDelta"))
        assertEquals(BaselineStatus.NotReproducible, record.check(3).status)
        assertEquals(BaselineStatus.Pass, record.check(4).status)
        assertEquals("pcm_s16le", record.check(4).fact("format"))
        assertEquals("24000", record.check(4).fact("sample_rate"))
        assertEquals("1", record.check(4).fact("channels"))
        assertEquals("2", record.check(4).fact("sample_width_bytes"))
        assertEquals("3", record.check(4).fact("binary_frames"))
        assertEquals("true", record.check(4).fact("chunked"))
        assertEquals("60", record.check(4).fact("total_bytes"))
        assertEquals(BaselineStatus.Pass, record.check(5).status)
        assertEquals("supported", record.check(5).fact("interrupt"))
        assertEquals(BaselineStatus.Pass, record.check(6).status)
        assertEquals("true", record.check(6).fact("session_resumed"))
        assertEquals("true", record.check(6).fact("transport_loss_observed"))
        assertEquals("0", record.check(6).fact("prompts_sent_during_recovery"))
        assertTrue(record.verdict, record.verdict.startsWith("PROCEED"))
        assertEquals(true, record.audioUsable)

        // Server-side proof: two prompts in all (typed + long), none after the drop.
        assertEquals(2, fake.count("prompt.submit"))
        assertEquals("session.create", fake.methods().first())
        assertEquals("session.resume", fake.methods().last())
        assertTrue(fake.methods().contains("session.interrupt"))
        assertTrue(
            "recovery must resume the original durable reference",
            synchronized(fake.requests) {
                fake.requests.single { it.getString("method") == "session.resume" }
                    .getJSONObject("params").getString("session_id") == "durable-1"
            },
        )

        // The audio socket used the gateway URL shape and the documented frames.
        val audio = fake.audioRequests.single()
        assertEquals("/api/audio/speak-stream", audio.encodedPath)
        assertEquals(setOf("token", "profile"), audio.queryParameterNames)
        assertTrue("audio credential mismatch", TOKEN == audio.queryParameter("token"))
        assertTrue("audio profile mismatch", audio.queryParameter("profile") == "default")
        assertTrue(
            "audio request shape mismatch",
            fake.audioTextFrames.toList() == listOf("""{"text":"Ready."}""", """{"done":true}"""),
        )
    }

    @Test
    fun a_single_audio_frame_is_usable_but_recorded_as_not_chunked() {
        serve(FakeStandard(audio = FakeAudio(frames = 1, sampleRate = 48_000)))

        val record = probe().run()

        assertEquals(BaselineStatus.Pass, record.check(4).status)
        assertEquals("false", record.check(4).fact("chunked"))
        assertEquals("48000", record.check(4).fact("sample_rate"))
    }

    @Test
    fun payload_derived_event_names_are_counted_never_rendered() {
        serve(
            FakeStandard(
                extraEvents = listOf("tool.progress" to JSONObject(), "secret_session_123" to JSONObject()),
            ),
        )

        val record = probe().run()

        assertEquals(BaselineStatus.Pass, record.check(2).status)
        assertEquals("2", record.check(2).fact("unknown_event_count"))
        assertFalse(record.toMarkdown().contains("tool.progress"))
        assertFalse(record.toMarkdown().contains("secret_session_123"))
    }

    @Test
    fun a_structured_prompt_is_recorded_by_type_and_the_turn_still_ends() {
        serve(FakeStandard(structuredPrompt = true))

        val record = probe().run()

        assertEquals(BaselineStatus.Pass, record.check(2).status)
        assertEquals("interrupted", record.check(2).fact("terminal"))
        assertEquals(BaselineStatus.Pass, record.check(3).status)
        assertEquals("approval.request", record.check(3).fact("types"))
        assertEquals("false", record.check(3).fact("any_sensitive"))
    }

    @Test
    fun an_interrupt_that_does_not_end_the_turn_is_recorded_as_unsupported() {
        serve(FakeStandard(interruptEndsTurn = false))

        val record = probe(interruptWindowMillis = 300).run()

        assertEquals(BaselineStatus.Fail, record.check(5).status)
        assertEquals("unsupported", record.check(5).fact("interrupt"))
        assertEquals("false", record.check(5).fact("turn_ended_early"))
        // An unsupported interrupt does not block the reconnect check.
        assertEquals(BaselineStatus.Pass, record.check(6).status)
        assertTrue(record.verdict, record.verdict.startsWith("REVIEW"))
        assertTrue(record.verdict.contains("interrupt"))
    }

    @Test
    fun natural_completion_after_an_interrupt_is_not_remote_interrupt_proof() {
        serve(FakeStandard(interruptTerminal = "message.complete"))

        val record = probe().run()

        assertEquals(BaselineStatus.Fail, record.check(5).status)
        assertEquals("true", record.check(5).fact("interrupt_acknowledged"))
        assertEquals("false", record.check(5).fact("remote_interrupt_terminal"))
        assertEquals("false", record.check(5).fact("turn_ended_early"))
    }

    @Test
    fun an_interrupt_terminal_without_a_positive_ack_is_not_verified() {
        serve(FakeStandard(acknowledgeInterrupt = false))

        val record = probe().run()

        assertEquals(BaselineStatus.Fail, record.check(5).status)
        assertEquals("false", record.check(5).fact("interrupt_acknowledged"))
        assertEquals("true", record.check(5).fact("remote_interrupt_terminal"))
    }

    @Test
    fun a_gateway_that_starts_a_fresh_session_on_reconnect_fails_check_6_without_sending_a_prompt() {
        val fake = serve(FakeStandard(durableId = null))

        val record = probe().run()

        assertEquals(BaselineStatus.Fail, record.check(6).status)
        assertEquals("false", record.check(6).fact("session_resumed"))
        assertEquals("true", record.check(6).fact("started_fresh"))
        assertEquals("0", record.check(6).fact("prompts_sent_during_recovery"))
        assertEquals(2, fake.count("prompt.submit"))
    }

    @Test
    fun a_gateway_without_a_structured_prompt_form_never_blocks_the_rest() {
        serve(FakeStandard())

        val record = probe().run()

        assertEquals(BaselineStatus.NotReproducible, record.check(3).status)
        assertFalse(record.verdict.startsWith("STOP"))
        assertFalse(record.verdict.startsWith("REVIEW"))
    }

    @Test
    fun unusable_audio_stops_the_probe_with_the_q1_verdict_and_nothing_downstream_runs() {
        val cases = mapOf(
            "http_404" to FakeAudio(notFound = true),
            "stereo" to FakeAudio(channels = 2),
            "eight_bit" to FakeAudio(sampleWidth = 1),
            "rate_below_player_range" to FakeAudio(sampleRate = 4_000),
            "big_endian" to FakeAudio(byteOrder = "big"),
            "server_fallback" to FakeAudio(terminal = "fallback"),
            "no_pcm" to FakeAudio(frames = 0),
        )
        cases.forEach { (label, audio) ->
            val fake = serve(FakeStandard(audio = audio))

            val record = probe(audioTimeoutMillis = 5_000).run()

            assertEquals(label, BaselineStatus.Fail, record.check(4).status)
            assertEquals(label, false, record.audioUsable)
            assertEquals(label, StandardBaselineRecord.AUDIO_STOP, record.verdict)
            assertTrue(
                record.verdict,
                record.verdict ==
                    "STOP: audio unusable — bring the Q1 choice (on-device Android TTS vs text-only) back to Amanda",
            )
            assertEquals(label, BaselineStatus.NotRun, record.check(5).status)
            assertEquals(label, BaselineStatus.NotRun, record.check(6).status)
            assertFalse(label, record.toMarkdown().contains("PROCEED"))
            // Only the typed turn ran; the interrupt prompt and the reconnect never did.
            assertEquals(label, 1, fake.count("prompt.submit"))
            assertEquals(label, 0, fake.count("session.interrupt"))
            assertEquals(label, 0, fake.count("session.resume"))
        }
    }

    @Test
    fun a_failed_session_create_stops_everything_without_touching_audio() {
        val fake = serve(FakeStandard(createFails = true))

        val record = probe().run()

        assertEquals(BaselineStatus.Fail, record.check(1).status)
        assertEquals("false", record.check(1).fact("session_reference_returned"))
        (2..6).forEach { assertEquals(BaselineStatus.NotRun, record.check(it).status) }
        assertTrue(record.verdict, record.verdict.startsWith("STOP"))
        assertEquals(0, fake.count("prompt.submit"))
        assertTrue(fake.audioRequests.isEmpty())
    }

    @Test
    fun the_record_stdout_and_progress_contain_no_secret_identity_or_content() {
        serve(FakeStandard(extraEvents = listOf("tool.progress" to JSONObject().put("text", REPLY_TEXT))))
        val progress = mutableListOf<String>()
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val record = try {
            System.setOut(PrintStream(captured, true, "UTF-8"))
            probe(progress = { progress += it }).run()
        } finally {
            System.setOut(originalOut)
        }
        val failing = run {
            serve(FakeStandard(createFails = true))
            probe(progress = { progress += it }).run()
        }

        listOf(record, failing).forEach { rec ->
            val everything = listOf(
                rec.toMarkdown(),
                rec.toString(),
                rec.verdict,
                captured.toString("UTF-8"),
                progress.joinToString("\n"),
            ).joinToString("\n")
            listOf(
                TOKEN, "token=", "profile=", "wss://", "https://", "localhost", "?",
                "runtime-1", "durable-1", "turn-1", "standard-", "approval-1",
                PROMPT_TEXT, REPLY_TEXT, "AUDIOBYTES", StandardBaselineProbe.LONG_PROMPT,
            ).forEach { forbidden ->
                assertFalse("sensitive content leaked", everything.contains(forbidden))
            }
        }
        // It still said something useful: names and numbers were recorded.
        assertTrue(record.toMarkdown().contains("total_bytes: 60"))
        assertTrue(progress.any { it.startsWith("check 1 session.create: PASS") })
    }

    @Test
    fun an_endpoint_that_cannot_be_reached_records_a_class_name_only() {
        val record = StandardBaselineProbe(
            endpoint = "wss://localhost:1/api/ws",
            hermesProfile = "default",
            token = TOKEN,
            httpClient = httpClient(),
            requestTimeoutMillis = 2_000,
        ).run()

        assertEquals(BaselineStatus.Fail, record.check(1).status)
        val text = record.toMarkdown()
        assertFalse(text.contains(TOKEN))
        assertFalse(text.contains("localhost"))
    }

    // ---- fake Standard gateway ----------------------------------------------

    private class FakeAudio(
        val notFound: Boolean = false,
        val sampleRate: Int = 24_000,
        val channels: Int = 1,
        val sampleWidth: Int = 2,
        val byteOrder: String = "little",
        val frames: Int = 3,
        val terminal: String = "end",
    )

    private inner class FakeStandard(
        val durableId: String? = "durable-1",
        val extraEvents: List<Pair<String, JSONObject>> = emptyList(),
        val structuredPrompt: Boolean = false,
        val interruptEndsTurn: Boolean = true,
        val interruptTerminal: String = "session.interrupted",
        val acknowledgeInterrupt: Boolean = true,
        val createFails: Boolean = false,
        val audio: FakeAudio = FakeAudio(),
    ) {
        val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val audioRequests: MutableList<HttpUrl> = Collections.synchronizedList(mutableListOf())
        val audioTextFrames: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun methods(): List<String> =
            synchronized(requests) { requests.map { it.getString("method") } }

        fun count(method: String) = methods().count { it == method }

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl!!.encodedPath) {
                    "/api/ws" -> MockResponse().withWebSocketUpgrade(GatewaySocket())
                    "/api/audio/speak-stream" -> {
                        audioRequests += request.requestUrl!!
                        if (audio.notFound) {
                            MockResponse().setResponseCode(404)
                        } else {
                            MockResponse().withWebSocketUpgrade(AudioSocket())
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }

        private inner class GatewaySocket : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(eventText("gateway.ready", JSONObject().put("skin", "default"), null))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                requests += request
                val id = request.getString("id")
                val params = request.getJSONObject("params")
                when (request.getString("method")) {
                    "session.create" -> webSocket.send(
                        if (createFails) errorText(id, 401, "unauthorized") else resultText(id, session(durableId)),
                    )
                    "session.resume" -> webSocket.send(
                        resultText(id, session(params.getString("session_id"))),
                    )
                    "prompt.submit" -> {
                        webSocket.send(
                            resultText(id, JSONObject().put("accepted", true).put("turn_id", "turn-1")),
                        )
                        if (params.getString("text") == StandardBaselineProbe.LONG_PROMPT) {
                            emit(webSocket, "message.start")
                            emit(webSocket, "message.delta", JSONObject().put("text", "Chapter one. "))
                        } else {
                            emit(webSocket, "message.start")
                            emit(webSocket, "message.delta", JSONObject().put("text", REPLY_TEXT))
                            extraEvents.forEach { (type, payload) -> emit(webSocket, type, payload) }
                            if (structuredPrompt) {
                                emit(webSocket, "approval.request", JSONObject().put("request_id", "approval-1"))
                            } else {
                                emit(webSocket, "message.complete", JSONObject().put("text", REPLY_TEXT))
                            }
                        }
                    }
                    "session.interrupt" -> if (interruptEndsTurn) {
                        webSocket.send(
                            resultText(id, JSONObject().put("accepted", acknowledgeInterrupt)),
                        )
                        emit(webSocket, interruptTerminal)
                    }
                }
            }

            private fun emit(webSocket: WebSocket, type: String, payload: JSONObject = JSONObject()) {
                webSocket.send(eventText(type, payload, "runtime-1", "turn-1"))
            }

            private fun session(durable: String?): JSONObject {
                val result = JSONObject().put("session_id", "runtime-1")
                if (durable != null) result.put("stored_session_id", durable)
                return result
            }
        }

        private inner class AudioSocket : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                audioTextFrames += text
                if (!JSONObject(text).optBoolean("done", false)) return
                webSocket.send(
                    JSONObject()
                        .put("type", "start")
                        .put("sample_rate", audio.sampleRate)
                        .put("channels", audio.channels)
                        .put("sample_width", audio.sampleWidth)
                        .put("byte_order", audio.byteOrder)
                        .toString(),
                )
                val chunk = "AUDIOBYTES-SENTINEL--".toByteArray().toByteString()
                repeat(audio.frames) { webSocket.send(chunk) }
                webSocket.send(JSONObject().put("type", audio.terminal).toString())
            }
        }
    }

    private fun serve(fake: FakeStandard): FakeStandard {
        server.dispatcher = fake.dispatcher
        return fake
    }

    private fun endpoint(): String =
        server.url("/api/ws").toString().replaceFirst("https://", "wss://")

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    private fun probe(
        interruptWindowMillis: Long = 5_000,
        audioTimeoutMillis: Long = 5_000,
        progress: (String) -> Unit = {},
    ) = StandardBaselineProbe(
        endpoint = endpoint(),
        hermesProfile = "default",
        token = TOKEN,
        httpClient = httpClient(),
        userPrompt = PROMPT_TEXT,
        requestTimeoutMillis = 5_000,
        turnTimeoutMillis = 5_000,
        audioTimeoutMillis = audioTimeoutMillis,
        interruptWindowMillis = interruptWindowMillis,
        progress = progress,
    )

    private companion object {
        const val TOKEN = "std-token-secret-0123456789"
        const val PROMPT_TEXT = "my private prompt text"
        const val REPLY_TEXT = "a private reply"

        fun resultText(id: String, result: JSONObject): String = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

        fun errorText(id: String, code: Int, message: String): String = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

        fun eventText(
            type: String,
            payload: JSONObject,
            session: String?,
            turn: String? = null,
        ): String {
            val params = JSONObject().put("type", type).put("payload", payload)
            if (session != null) params.put("session_id", session)
            if (turn != null) params.put("turn_id", turn)
            return JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "event")
                .put("params", params)
                .toString()
        }
    }
}
