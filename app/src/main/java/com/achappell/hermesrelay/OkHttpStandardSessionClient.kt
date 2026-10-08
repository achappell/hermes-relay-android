package com.achappell.hermesrelay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A Standard `/api/ws` failure. The message is only the enum name: a failure
 * can reach logs and crash reports, and must never carry a URL, token, session
 * reference or any gateway text.
 */
internal class StandardFailure(val reason: AndroidHomeUnavailableReason) :
    RuntimeException(reason.name)

/** One validated server event frame. */
internal class StandardEvent(
    val type: String,
    /** Session identity from `params.session_id` and/or `payload.session_id`. */
    val sessionId: String?,
    val payload: JSONObject,
    val payloadPresent: Boolean,
    val turnId: String?,
    val seq: Long?,
)

/** The identities a successful `session.create` or `session.resume` reply carries. */
internal class StandardSessionIds(
    val runtimeId: String,
    val durableId: String?,
)

/** Acceptance proof for `prompt.submit` and `session.interrupt`. */
internal sealed interface StandardAck {
    data class Accepted(val turnId: String?) : StandardAck

    /** An explicit refusal: a JSON-RPC error or `accepted:false`/a rejecting status. */
    data object Rejected : StandardAck

    /** A reply that proves neither acceptance nor refusal. */
    data object NoProof : StandardAck
}

internal object StandardWire {
    const val READY = "gateway.ready"
    const val SOURCE = "android"

    private val ACCEPTED_STATUSES = setOf("accepted", "queued", "streaming", "submitted")
    private val INTERRUPT_STATUSES = ACCEPTED_STATUSES + setOf("interrupted", "cancelled", "canceled")
    private val REJECTED_STATUSES = setOf("rejected", "refused", "failed", "error")
    private val PROFILE_KEYS = listOf("profile_id", "profile", "profile_name")

    /**
     * The WebSocket URL: the normalized endpoint plus the `token` and `profile`
     * query keys the Standard gateway and the household pilot proxy accept.
     * Returned as [HttpUrl] so nothing needs to stringify it.
     */
    fun url(endpoint: String, hermesProfile: String?, token: String): HttpUrl? {
        val converted = when {
            endpoint.startsWith("wss://", ignoreCase = true) -> "https://" + endpoint.substring(6)
            endpoint.startsWith("ws://", ignoreCase = true) -> "http://" + endpoint.substring(5)
            else -> return null
        }
        val base = converted.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .encodedQuery(null)
            .addQueryParameter("token", token)
            .apply {
                if (!hermesProfile.isNullOrBlank()) addQueryParameter("profile", hermesProfile)
            }
            .build()
    }

    fun requestText(id: String, method: String, params: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params)
        .toString()

    fun sessionParams(hermesProfile: String?, resumeId: String?): JSONObject {
        val params = JSONObject()
        if (resumeId != null) params.put("session_id", resumeId)
        params.put("source", SOURCE)
        if (!hermesProfile.isNullOrBlank()) params.put("profile", hermesProfile)
        return params
    }

    fun parseEvent(frame: JSONObject): StandardEvent {
        val params = frame.opt("params") as? JSONObject ?: throw protocol()
        val type = nonBlankString(params, "type") ?: nonBlankString(params, "event")
            ?: throw protocol()
        val rawPayload = params.opt("payload")
        val payloadPresent = rawPayload is JSONObject
        if (rawPayload != null && rawPayload !== JSONObject.NULL && !payloadPresent) throw protocol()
        val payload = rawPayload as? JSONObject ?: JSONObject()

        val advertised = optionalId(params, "session_id")
        val sessionId = if (type == "session.title") {
            // The title event's payload id is the durable session id, not a conflict.
            advertised
        } else {
            val inner = optionalId(payload, "session_id")
            if (advertised != null && inner != null && advertised != inner) throw protocol()
            advertised ?: inner
        }

        val turnId = optionalId(params, "turn_id") ?: optionalId(payload, "turn_id")
        val seq = when (val raw = params.opt("seq")) {
            null, JSONObject.NULL -> null
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw protocol()
        }
        if (seq != null && seq < 1) throw protocol()
        return StandardEvent(type, sessionId, payload, payloadPresent, turnId, seq)
    }

    /** Whether the reply carries exactly one of `result` and `error`. */
    fun isWellFormedReply(reply: JSONObject): Boolean =
        reply.has("result") != reply.has("error")

    fun parseSession(
        reply: JSONObject,
        expectedProfile: String?,
        resumeId: String?,
    ): StandardSessionIds {
        if (!isWellFormedReply(reply)) throw protocol()
        if (reply.has("error")) throw rpcFailure(reply)
        val result = reply.opt("result") as? JSONObject ?: throw protocol()

        val runtimeIds = listOf("session_id", "runtime_session_id").mapNotNull { key ->
            sessionString(result, key)
        }.toSet()
        if (runtimeIds.size != 1) throw protocol()

        val durableIds = listOf("stored_session_id", "session_key").mapNotNull { key ->
            sessionString(result, key)
        }.toSet()
        if (durableIds.size > 1) throw protocol()
        val durableId = durableIds.firstOrNull()

        if (!expectedProfile.isNullOrBlank()) {
            val info = result.opt("info") as? JSONObject
            for (source in listOfNotNull(result, info)) {
                for (key in PROFILE_KEYS) {
                    val echoed = (source.opt(key) as? String)?.trim()
                    if (!echoed.isNullOrEmpty() && echoed != expectedProfile.trim()) {
                        throw StandardFailure(AndroidHomeUnavailableReason.ConversationMismatch)
                    }
                }
            }
        }
        if (resumeId != null && durableId != resumeId) {
            throw StandardFailure(AndroidHomeUnavailableReason.ConversationMismatch)
        }
        return StandardSessionIds(runtimeIds.first(), durableId)
    }

    fun readAck(reply: JSONObject, interrupt: Boolean): StandardAck {
        if (!isWellFormedReply(reply)) return StandardAck.NoProof
        if (reply.has("error")) return StandardAck.Rejected
        val result = reply.opt("result") as? JSONObject ?: return StandardAck.NoProof
        val accepted = result.opt("accepted")
        val resolved = result.opt("resolved")
        val nullOrBoolean = { value: Any? -> value == null || value === JSONObject.NULL || value is Boolean }
        if (!nullOrBoolean(accepted) || !nullOrBoolean(resolved)) return StandardAck.NoProof
        return readAckFields(result, accepted as? Boolean, resolved as? Boolean, interrupt)
    }

    private fun readAckFields(
        result: JSONObject,
        accepted: Boolean?,
        resolved: Boolean?,
        interrupt: Boolean,
    ): StandardAck {
        val rawStatus = result.opt("status")
        if (rawStatus != null && rawStatus !== JSONObject.NULL && rawStatus !is String) {
            return StandardAck.NoProof
        }
        val status = (rawStatus as? String)?.lowercase().orEmpty()
        val rawTurn = result.opt("turn_id")
        val turnId = when {
            rawTurn == null || rawTurn === JSONObject.NULL -> null
            rawTurn is String && rawTurn.isNotBlank() -> rawTurn.trim()
            else -> return StandardAck.NoProof
        }
        val acceptedStatuses = if (interrupt) INTERRUPT_STATUSES else ACCEPTED_STATUSES
        return when {
            accepted == false || resolved == false || status in REJECTED_STATUSES ->
                StandardAck.Rejected
            accepted == true || resolved == true || status in acceptedStatuses ->
                StandardAck.Accepted(turnId)
            else -> StandardAck.NoProof
        }
    }

    fun rpcFailure(reply: JSONObject): StandardFailure {
        val error = reply.opt("error") as? JSONObject
        val code = (error?.opt("code") as? Number)?.toInt()
        val text = (error?.opt("message") as? String)?.lowercase().orEmpty()
        val auth = code == 401 || code == 403 ||
            AUTH_WORDS.any { text.contains(it) }
        return StandardFailure(
            if (auth) {
                AndroidHomeUnavailableReason.Unauthorized
            } else {
                AndroidHomeUnavailableReason.RequestRejected
            },
        )
    }

    fun protocol() = StandardFailure(AndroidHomeUnavailableReason.ProtocolError)

    private val AUTH_WORDS = listOf("unauthorized", "forbidden", "invalid token", "authentication")

    private fun nonBlankString(source: JSONObject, key: String): String? {
        val value = source.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        if (value !is String) throw protocol()
        return value.takeIf { it.isNotBlank() }
    }

    /** Absent, null and empty are "no identity"; any other non-string is a violation. */
    private fun optionalId(source: JSONObject, key: String): String? {
        val value = source.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        if (value !is String) throw protocol()
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun sessionString(source: JSONObject, key: String): String? {
        val value = source.opt(key)
        if (value == null || value === JSONObject.NULL || value == "") return null
        if (value !is String || value.isBlank()) throw protocol()
        return value.trim()
    }
}

/**
 * One Standard `/api/ws` socket: validates frames, enforces the
 * `gateway.ready`-first rule, correlates request replies and reports the
 * single end of the socket. Owns no session state.
 */
internal class StandardChannel(
    private val httpClient: OkHttpClient,
    private val readyTimeoutMillis: Long,
    private val onEvent: (StandardChannel, StandardEvent) -> Unit = { _, _ -> },
    /** Called once, only for a socket that had become ready and was not cancelled locally. */
    private val onEnd: (StandardChannel, StandardFailure) -> Unit = { _, _ -> },
    private val onBinary: () -> Unit = {},
    private val onPeerClose: (Int) -> Unit = {},
) {
    /** Local identity of this socket; never a Hermes session identity. */
    val connectionId: String = UUID.randomUUID().toString()

    private val readyLatch = CountDownLatch(1)
    private val openFailure = AtomicReference<StandardFailure?>(null)
    private val endFailure = AtomicReference<StandardFailure?>(null)
    private val pending = ConcurrentHashMap<String, PendingReply>()
    private val requestCounter = AtomicLong(0)
    private val ended = AtomicBoolean(false)
    private val closedByUs = AtomicBoolean(false)

    @Volatile
    private var ready = false

    @Volatile
    private var socket: WebSocket? = null

    val isEnded: Boolean
        get() = ended.get()

    private class PendingReply(private val onReply: ((JSONObject) -> Unit)? = null) {
        sealed interface Outcome {
            data class Frame(val value: JSONObject) : Outcome
            data object Lost : Outcome
            data object Timeout : Outcome
        }

        private val settled = CountDownLatch(1)

        @Volatile
        private var frame: JSONObject? = null

        fun complete(response: JSONObject) {
            frame = response
            settled.countDown()
            onReply?.invoke(response)
        }

        fun fail() = settled.countDown()

        fun await(timeoutMillis: Long): Outcome {
            if (!settled.await(timeoutMillis, TimeUnit.MILLISECONDS)) return Outcome.Timeout
            return frame?.let { Outcome.Frame(it) } ?: Outcome.Lost
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (ended.get()) return
            try {
                handleText(text)
            } catch (failure: StandardFailure) {
                end(failure)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The Standard gateway sends JSON text only; a binary frame is
            // counted and never interpreted or played.
            if (!ended.get()) onBinary()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onPeerClose(code)
            webSocket.close(1000, null)
            end(StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            end(StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Neither the throwable nor the response is read for text: both can
            // describe the URL.
            val code = response?.code
            val reason = when {
                code == 401 || code == 403 -> AndroidHomeUnavailableReason.Unauthorized
                code != null && code != 101 -> AndroidHomeUnavailableReason.TransportUnavailable
                t is SocketTimeoutException -> AndroidHomeUnavailableReason.TransportTimeout
                else -> AndroidHomeUnavailableReason.TransportUnavailable
            }
            end(StandardFailure(reason))
        }
    }

    /** Opens the socket and waits for `gateway.ready`. Blocking. */
    fun connect(url: HttpUrl) {
        val request = Request.Builder().url(url).build()
        val opened = httpClient.newWebSocket(request, listener)
        socket = opened
        if (ended.get()) opened.cancel()
        val signalled = try {
            readyLatch.await(readyTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel()
            throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
        }
        openFailure.get()?.let {
            cancel()
            throw it
        }
        if (!signalled) {
            cancel()
            throw StandardFailure(AndroidHomeUnavailableReason.TransportTimeout)
        }
    }

    /**
     * Sends one request and waits for its reply. The returned frame is the
     * reply as received; the caller classifies `result` versus `error`.
     */
    fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject {
        if (ended.get() || !ready) {
            throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
        }
        val id = nextId()
        val reply = PendingReply()
        pending[id] = reply
        val sent = socket?.send(StandardWire.requestText(id, method, params)) ?: false
        if (!sent) {
            pending.remove(id)
            throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
        }
        val outcome = try {
            reply.await(timeoutMillis)
        } catch (_: InterruptedException) {
            pending.remove(id)
            Thread.currentThread().interrupt()
            throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
        }
        return when (outcome) {
            is PendingReply.Outcome.Frame -> outcome.value
            PendingReply.Outcome.Lost -> throw (
                endFailure.get()
                    ?: StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
                )
            PendingReply.Outcome.Timeout -> {
                pending.remove(id)
                throw StandardFailure(AndroidHomeUnavailableReason.TransportTimeout)
            }
        }
    }

    /** Sends a request and does not wait for the reply. Returns whether it was written. */
    fun sendAsync(
        method: String,
        params: JSONObject,
        onReply: ((JSONObject) -> Unit)? = null,
    ): Boolean {
        if (ended.get() || !ready) return false
        val id = nextId()
        pending[id] = PendingReply(onReply)
        val sent = socket?.send(StandardWire.requestText(id, method, params)) ?: false
        if (!sent) pending.remove(id)
        return sent
    }

    /** Local, deliberate close: no end callback fires. */
    fun cancel() {
        closedByUs.set(true)
        socket?.cancel()
        end(StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable))
    }

    private fun nextId() = "android-std-${requestCounter.incrementAndGet()}"

    private fun handleText(text: String) {
        val frame = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw StandardWire.protocol()
        }
        if (frame.opt("jsonrpc") != "2.0") throw StandardWire.protocol()
        if (frame.has("method")) {
            // Only `event` notifications are meaningful; any other
            // server-initiated method is ignored.
            if (frame.opt("method") == "event") handleEvent(frame)
            return
        }
        handleReply(frame)
    }

    private fun handleEvent(frame: JSONObject) {
        val event = StandardWire.parseEvent(frame)
        if (!ready) {
            if (event.type != StandardWire.READY || !event.payloadPresent) {
                throw StandardWire.protocol()
            }
            ready = true
            readyLatch.countDown()
            return
        }
        if (event.type == StandardWire.READY) throw StandardWire.protocol()
        onEvent(this, event)
    }

    private fun handleReply(frame: JSONObject) {
        val id = frame.opt("id") as? String ?: throw StandardWire.protocol()
        if (!ready) throw StandardWire.protocol()
        // Settle the waiter first so its caller can classify a malformed reply
        // as a protocol error rather than a lost transport.
        pending.remove(id)?.complete(frame)
        if (!StandardWire.isWellFormedReply(frame)) throw StandardWire.protocol()
    }

    private fun end(failure: StandardFailure) {
        if (!ended.compareAndSet(false, true)) return
        endFailure.set(failure)
        val wasReady = ready
        if (!wasReady) {
            openFailure.compareAndSet(null, failure)
            readyLatch.countDown()
        }
        pending.values.forEach(PendingReply::fail)
        pending.clear()
        socket?.cancel()
        if (wasReady && !closedByUs.get()) onEnd(this, failure)
    }
}

/**
 * Setup-time check for a Standard Profile (`ANDROID-STD-01`): opens the socket,
 * waits for `gateway.ready`, runs `session.create`, validates the reply and
 * cancels the socket. Blocking. The token lives only in the URL handed to
 * OkHttp for this call; no failure text carries it.
 */
internal class OkHttpStandardConnectionChecker(
    private val httpClient: OkHttpClient = OkHttpStandardSessionClient.defaultClient(),
    private val readyTimeoutMillis: Long = 10_000,
    private val requestTimeoutMillis: Long = 30_000,
) : StandardConnectionChecker {
    override fun check(endpoint: String, hermesProfile: String, token: String): StandardCheckResult {
        val url = StandardWire.url(endpoint, hermesProfile, token)
            ?: return StandardCheckResult.Failed(AndroidHomeUnavailableReason.InvalidBinding)
        val channel = StandardChannel(httpClient, readyTimeoutMillis)
        return try {
            channel.connect(url)
            val reply = channel.request(
                "session.create",
                StandardWire.sessionParams(hermesProfile, resumeId = null),
                requestTimeoutMillis,
            )
            StandardWire.parseSession(reply, hermesProfile, resumeId = null)
            StandardCheckResult.Verified
        } catch (failure: StandardFailure) {
            StandardCheckResult.Failed(
                if (failure.reason == AndroidHomeUnavailableReason.ConversationMismatch) {
                    AndroidHomeUnavailableReason.ProtocolError
                } else {
                    failure.reason
                },
            )
        } finally {
            channel.cancel()
        }
    }
}

/**
 * Direct Standard `/api/ws` transport (`ANDROID-STD-01` slice 1): JSON-RPC 2.0
 * over one WebSocket, typed streaming chat, no Home, no audio.
 *
 * The Hermes session ids never leave this class: the observable conversation
 * handle and connection id are local UUIDs. Recovery never replays a prompt;
 * within one Profile identity, only a successful [newConversation] clears uncertainty.
 *
 * Lock order: [inboundLock] before [stateLock]; observer callbacks never run
 * under [stateLock].
 */
internal class OkHttpStandardSessionClient(
    private val collection: () -> RelayProfileCollection,
    private val credentials: RelayCredentialStore,
    private val httpClient: OkHttpClient = defaultClient(),
    private val readyTimeoutMillis: Long = 10_000,
    private val requestTimeoutMillis: Long = 30_000,
    /** Q2: false until a live check verifies 0.21.5 accepts session.interrupt; then stop also sends it. */
    private val remoteInterruptVerified: Boolean = false,
    private val journal: DiagnosticsJournal = DiagnosticsJournal.None,
    private val clock: MonotonicClock = MonotonicClock.Real,
) : AndroidClientPort, AndroidStandardSession {

    private class TurnObserver(
        val binding: AndroidTurnBinding,
        val onEvent: (AndroidNormalizedEvent) -> Unit,
    )

    private class ActiveTurn(
        val request: AndroidTurnRequest,
        val channel: StandardChannel,
        val conversationHandle: String,
    ) {
        /** Null until the `prompt.submit` reply is accepted; events before that wait in [early]. */
        @Volatile
        var binding: AndroidTurnBinding? = null
        var remoteTurnId: String? = null
        val early = ArrayList<StandardEvent>()
    }

    private val stateLock = Any()
    private val inboundLock = Any()
    private val connectMonitor = Any()

    // Guarded by stateLock.
    private var channel: StandardChannel? = null
    private var sessionProfileId: String? = null
    private var sessionHistoryKey: String? = null
    private var runtimeId: String? = null
    private var durableId: String? = null
    private var localHandle: String = newHandle()
    private var lastSeq = 0L
    private var activeTurn: ActiveTurn? = null
    private var uncertain = false
    private var finishing = false
    private var lastUnavailableReason: AndroidHomeUnavailableReason? = null
    private var lastUnavailableProfileId: String? = null
    private var lastUnavailableHistoryKey: String? = null

    // Guarded by inboundLock.
    private var turnObserver: TurnObserver? = null
    private val undelivered = ArrayDeque<AndroidNormalizedEvent>()
    private val normalizer = HermesEventNormalizer(
        clientId = "android",
        allowLegacyFrames = false,
        textOnlyTurns = true,
    )

    private val connectionObserver =
        AtomicReference<((AndroidNormalizedEvent.Disconnected) -> Unit)?>(null)
    private val finishingObservers = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val generation = AtomicLong(0)
    private val ignoredBinary = AtomicInteger(0)

    /** Binary frames received and discarded; they are never played or parsed. */
    internal val ignoredBinaryFrameCount: Int
        get() = ignoredBinary.get()

    override fun snapshot(): AndroidClientSnapshot {
        val selected = collection().selected
        val profile = selected?.takeIf { it.mode == RelayProfileMode.Standard }
        if (profile == null) {
            return AndroidClientSnapshot(
                titleRes = BootstrapState.titleRes,
                descriptionRes = BootstrapState.descriptionRes,
                boundaryRes = BootstrapState.boundaryRes,
                authorizationState = AndroidAuthorizationState.NotConfigured,
                mode = selected?.mode,
            )
        }
        val hasCredential = credentials.hasReadableStandardCredential(profile.id)
        val (recordedReason, established) = synchronized(stateLock) {
            val reason = if (
                lastUnavailableProfileId == profile.id && lastUnavailableHistoryKey == profile.historyKey
            ) lastUnavailableReason else null
            reason to (channel != null && runtimeId != null && matchesSession(profile))
        }
        val authorization = when {
            !hasCredential -> AndroidAuthorizationState.Unavailable
            recordedReason != null -> AndroidAuthorizationState.Unavailable
            established -> AndroidAuthorizationState.Verified
            else -> AndroidAuthorizationState.Verifying
        }
        return AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile(profile.id, profile.displayName, deviceLabel = null),
            authorizationState = authorization,
            capabilities = AndroidHomeCapabilities(),
            unavailableReason = if (!hasCredential) {
                AndroidHomeUnavailableReason.InvalidCredential
            } else {
                recordedReason
            },
            mode = RelayProfileMode.Standard,
        )
    }

    // ---- turns -------------------------------------------------------------

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
        val startedAt = clock.nanoTime()
        val result = attemptBeginTurn(request)
        val duration = elapsedMillis(startedAt)
        journal.record(
            when (result) {
                is AndroidInitiationResult.Accepted ->
                    "standard request completed method=prompt.submit duration_ms=$duration"
                is AndroidInitiationResult.Uncertain ->
                    "standard request failed method=prompt.submit reason=${result.reason.name} " +
                        "uncertain=true duration_ms=$duration"
                is AndroidInitiationResult.Rejected ->
                    if (result.reason == AndroidInitiationFailure.RequestRejected) {
                        "standard request failed method=prompt.submit " +
                            "reason=${AndroidHomeUnavailableReason.RequestRejected.name} " +
                            "uncertain=false duration_ms=$duration"
                    } else {
                        "standard request rejected method=prompt.submit reason=${result.reason.name}"
                    }
            },
        )
        return result
    }

    private fun attemptBeginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
        val profile = collection().selected?.takeIf { it.mode == RelayProfileMode.Standard }
        if (profile == null || request.profile.id != profile.id) {
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable)
        }
        val normalizedRequest = request.copy(
            profile = AndroidProfile(profile.id, profile.displayName, deviceLabel = null),
        )
        val turn: ActiveTurn
        val text: String
        val runtime: String
        synchronized(stateLock) {
            val current = channel
            val currentRuntime = runtimeId
            if (
                current == null || currentRuntime == null || current.isEnded ||
                !matchesSession(profile) || !matchesSelection(profile)
            ) {
                return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
            }
            text = when (val input = request.input) {
                is AndroidTurnInput.Typed -> input.text
                // Voice is slice 2; Standard has no tap-to-speak yet.
                AndroidTurnInput.TapToSpeak -> return AndroidInitiationResult.Rejected(
                    AndroidInitiationFailure.SessionUnavailable,
                )
            }
            if (text.isBlank()) {
                return AndroidInitiationResult.Rejected(AndroidInitiationFailure.EmptyTypedPrompt)
            }
            if (uncertain) {
                return AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain)
            }
            if (finishing) {
                return AndroidInitiationResult.Rejected(
                    AndroidInitiationFailure.PreviousResponseFinishing,
                )
            }
            if (activeTurn != null) {
                return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
            }
            runtime = currentRuntime
            turn = ActiveTurn(normalizedRequest, current, localHandle)
            activeTurn = turn
        }
        synchronized(inboundLock) { undelivered.clear() }

        fun uncertainResult(reason: AndroidHomeUnavailableReason): AndroidInitiationResult {
            synchronized(stateLock) {
                if (activeTurn === turn) {
                    uncertain = true
                    activeTurn = null
                }
            }
            return AndroidInitiationResult.Uncertain(normalizedRequest, reason)
        }

        val reply = try {
            turn.channel.request(
                "prompt.submit",
                JSONObject().put("session_id", runtime).put("text", text),
                requestTimeoutMillis,
            )
        } catch (failure: StandardFailure) {
            return uncertainResult(failure.reason)
        }
        val ack = StandardWire.readAck(reply, interrupt = false)
        if (ack is StandardAck.Rejected) {
            synchronized(stateLock) { if (activeTurn === turn) activeTurn = null }
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected)
        }
        if (ack !is StandardAck.Accepted) {
            return uncertainResult(AndroidHomeUnavailableReason.ProtocolError)
        }

        val binding = AndroidTurnBinding(
            profileId = profile.id,
            conversationHandle = turn.conversationHandle,
            connectionId = turn.channel.connectionId,
            turnId = ack.turnId ?: UUID.randomUUID().toString(),
        )
        var lost = false
        synchronized(inboundLock) {
            val stillActive = synchronized(stateLock) { activeTurn === turn }
            if (!stillActive) {
                lost = true
            } else {
                turn.remoteTurnId = ack.turnId
                turn.binding = binding
                normalizer.beginTurn()
                val buffered = ArrayList(turn.early)
                turn.early.clear()
                for (event in buffered) {
                    if (!processTurnEvent(turn, binding, event)) break
                }
            }
        }
        if (lost) {
            // Transport was lost after Hermes accepted: loss already flagged uncertainty.
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
        }
        return AndroidInitiationResult.Accepted(binding)
    }

    override fun observeTurn(
        binding: AndroidTurnBinding,
        onEvent: (AndroidNormalizedEvent) -> Unit,
    ): AndroidTurnObservation {
        val registered = TurnObserver(binding, onEvent)
        synchronized(inboundLock) {
            turnObserver = registered
            val queued = ArrayList(undelivered)
            undelivered.clear()
            for (event in queued) {
                val mine = event.binding == binding ||
                    (
                        event is AndroidNormalizedEvent.Disconnected &&
                            event.connectionId == binding.connectionId
                        )
                if (mine) registered.onEvent(event)
            }
        }
        return AndroidTurnObservation {
            synchronized(inboundLock) {
                if (turnObserver === registered) turnObserver = null
            }
        }
    }

    override fun observeConnection(
        onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
    ): AndroidTurnObservation {
        connectionObserver.set(onEvent)
        return AndroidTurnObservation {
            connectionObserver.compareAndSet(onEvent, null)
        }
    }

    override fun hasActiveTurn(): Boolean = synchronized(stateLock) { activeTurn != null }

    /** Standard never replays; reconnecting the same identity preserves uncertainty. */
    override fun prepareForExplicitResend() = Unit

    // ---- events ------------------------------------------------------------

    private fun onChannelEvent(source: StandardChannel, event: StandardEvent) {
        var finishedFinishing = false
        synchronized(inboundLock) {
            val turn: ActiveTurn?
            synchronized(stateLock) {
                if (channel !== source) return
                val runtime = runtimeId ?: return
                if (event.sessionId != runtime) return
                event.seq?.let { seq ->
                    if (seq <= lastSeq) return
                    lastSeq = seq
                }
                if (event.type == "session.title") return
                turn = activeTurn
                if (finishing && turn == null && isTurnEndType(event)) {
                    finishing = false
                    finishedFinishing = true
                    val outcome = canonicalTerminalTrace(event.type, event.payload)?.outcome
                        ?: "unknown"
                    journal.record("standard finishing terminal outcome=$outcome")
                }
            }
            if (turn != null) {
                val binding = turn.binding
                if (binding == null) {
                    if (turn.early.size >= MAX_EARLY_EVENTS) turn.early.removeAt(0)
                    turn.early.add(event)
                } else {
                    processTurnEvent(turn, binding, event)
                }
            }
        }
        if (finishedFinishing) announceFinishing(false)
    }

    /** Returns true while the turn is still live after this event. Caller holds [inboundLock]. */
    private fun processTurnEvent(
        turn: ActiveTurn,
        binding: AndroidTurnBinding,
        event: StandardEvent,
    ): Boolean {
        val eventTurn = event.turnId
        if (eventTurn != null) {
            val remote = turn.remoteTurnId
            if (remote != null && eventTurn != remote) {
                journal.record("standard event ignored reason=foreign_turn")
                return true
            }
            if (remote == null) turn.remoteTurnId = eventTurn
        }
        var ended = false
        for (normalized in normalizer.normalizeStandardEvent(event.type, event.payload, binding)) {
            val outcome = when (normalized) {
                is AndroidNormalizedEvent.TurnCompleted -> "completed"
                is AndroidNormalizedEvent.TurnFailed -> "failed"
                is AndroidNormalizedEvent.TurnInterrupted -> "interrupted"
                else -> null
            }
            if (outcome != null) {
                // Observers may immediately admit the next turn after a terminal event.
                synchronized(stateLock) { if (activeTurn === turn) activeTurn = null }
                journal.record("standard turn terminal outcome=$outcome")
                ended = true
            }
            deliverToTurnObserver(binding, normalized)
            if (normalized is AndroidNormalizedEvent.StructuredPrompt) cancelUnsupportedPrompt(turn)
        }
        return !ended
    }

    /**
     * Hermes is waiting on a prompt this slice cannot answer: cancel it so the
     * turn ends. A failed send is ignored; the turn then ends through its own
     * terminal event, the transport or the timeouts.
     */
    private fun cancelUnsupportedPrompt(turn: ActiveTurn) {
        val runtime = synchronized(stateLock) { runtimeId } ?: return
        val sent = turn.channel.sendAsync("session.interrupt", JSONObject().put("session_id", runtime))
        journal.record("standard unsupported prompt interrupt=${if (sent) "sent" else "failed"}")
    }

    private fun isTurnEndType(event: StandardEvent): Boolean =
        event.type in TURN_END_TYPES || canonicalTerminalTrace(event.type, event.payload) != null

    /** Caller holds [inboundLock]. */
    private fun deliverToTurnObserver(binding: AndroidTurnBinding, event: AndroidNormalizedEvent) {
        val observer = turnObserver
        if (observer != null && observer.binding == binding) {
            observer.onEvent(event)
        } else {
            while (undelivered.size >= MAX_UNDELIVERED_EVENTS) undelivered.removeFirst()
            undelivered.addLast(event)
        }
    }

    private fun onChannelBinary() {
        ignoredBinary.incrementAndGet()
    }

    // ---- transport loss ----------------------------------------------------

    private fun onChannelEnd(source: StandardChannel, failure: StandardFailure) {
        val reason = when (failure.reason) {
            AndroidHomeUnavailableReason.ProtocolError ->
                "The Standard gateway sent an invalid frame."
            AndroidHomeUnavailableReason.TransportTimeout ->
                "The Standard gateway connection timed out."
            else -> "The Standard gateway connection was lost."
        }
        val connectionId: String
        synchronized(inboundLock) {
            val turnBinding: AndroidTurnBinding?
            synchronized(stateLock) {
                // A socket that never committed a session reports through its
                // connect attempt, not as a lost session.
                if (channel !== source) return
                val turn = activeTurn
                if (turn != null) uncertain = true
                turnBinding = turn?.binding
                channel = null
                runtimeId = null
                activeTurn = null
                lastUnavailableReason = AndroidHomeUnavailableReason.TransportUnavailable
                lastUnavailableProfileId = sessionProfileId
                lastUnavailableHistoryKey = sessionHistoryKey
            }
            connectionId = source.connectionId
            journal.record("standard transport lost")
            if (turnBinding != null) {
                deliverToTurnObserver(
                    turnBinding,
                    AndroidNormalizedEvent.Disconnected(connectionId, reason),
                )
            }
        }
        connectionObserver.get()?.invoke(AndroidNormalizedEvent.Disconnected(connectionId, reason))
    }

    // ---- connect -----------------------------------------------------------

    override fun reconnect(): AndroidReconnectOutcome {
        val startedAt = clock.nanoTime()
        val outcome = synchronized(connectMonitor) { attemptReconnect() }
        val (result, reason) = when (outcome) {
            is AndroidReconnectOutcome.Connected ->
                (if (outcome.sessionStartedFresh) "connected" else "resumed") to null
            is AndroidReconnectOutcome.Retryable -> "failed" to outcome.reasonCode
            is AndroidReconnectOutcome.Unrecoverable -> "failed" to outcome.reasonCode
        }
        journal.record(
            "standard connect result=$result reason=${reason?.name ?: "none"} " +
                "duration_ms=${elapsedMillis(startedAt)}",
        )
        return outcome
    }

    private fun attemptReconnect(): AndroidReconnectOutcome {
        val profile = standardProfile()
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "No Standard Profile is selected.",
                AndroidHomeUnavailableReason.MissingBinding,
            )
        discardChannelForOtherProfile(profile)
        val token = credentials.readStandardCredential(profile.id)
            ?: return failedConnect(profile, AndroidHomeUnavailableReason.InvalidCredential)

        synchronized(stateLock) {
            val current = channel
            if (current != null && !current.isEnded && runtimeId != null && matchesSession(profile) &&
                matchesSelection(profile)
            ) {
                return AndroidReconnectOutcome.Connected(current.connectionId)
            }
        }

        val resumeId = synchronized(stateLock) {
            durableId.takeIf { matchesSession(profile) }
        }
        val attemptGeneration = generation.get()
        var opened: StandardChannel? = null
        try {
            val link = openChannel(profile, token)
            opened = link
            if (generation.get() != attemptGeneration || !matchesSelection(profile)) {
                throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
            }
            val reply = link.request(
                if (resumeId != null) "session.resume" else "session.create",
                StandardWire.sessionParams(profile.hermesProfile, resumeId),
                requestTimeoutMillis,
            )
            val ids = try {
                StandardWire.parseSession(reply, profile.hermesProfile, resumeId)
            } catch (failure: StandardFailure) {
                // A resume Hermes explicitly refuses means the held session is gone.
                if (resumeId != null && failure.reason == AndroidHomeUnavailableReason.RequestRejected) {
                    throw StandardFailure(AndroidHomeUnavailableReason.StaleConversation)
                }
                throw failure
            }
            if (generation.get() != attemptGeneration) {
                throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
            }
            commitSession(
                link, profile, ids, fresh = resumeId == null, clearFlags = false,
                attemptGeneration = attemptGeneration,
            )
            return AndroidReconnectOutcome.Connected(
                connectionId = link.connectionId,
                sessionStartedFresh = resumeId == null,
            )
        } catch (failure: StandardFailure) {
            opened?.cancel()
            return failedConnect(profile, failure.reason)
        }
    }

    private fun failedConnect(
        profile: RelayProfile,
        reason: AndroidHomeUnavailableReason,
    ): AndroidReconnectOutcome {
        synchronized(stateLock) {
            lastUnavailableReason = reason
            lastUnavailableProfileId = profile.id
            lastUnavailableHistoryKey = profile.historyKey
        }
        return when (reason) {
            AndroidHomeUnavailableReason.TransportUnavailable,
            AndroidHomeUnavailableReason.TransportTimeout -> AndroidReconnectOutcome.Retryable(
                if (reason == AndroidHomeUnavailableReason.TransportTimeout) {
                    "The Standard gateway did not answer in time."
                } else {
                    "The Standard gateway is unreachable."
                },
                reason,
            )
            else -> AndroidReconnectOutcome.Unrecoverable(
                when (reason) {
                    AndroidHomeUnavailableReason.Unauthorized ->
                        "The Standard gateway rejected the token."
                    AndroidHomeUnavailableReason.InvalidCredential ->
                        "No Standard token is stored for this Profile."
                    AndroidHomeUnavailableReason.StaleConversation ->
                        "Hermes no longer has the previous conversation."
                    else -> "The Standard gateway could not start a conversation."
                },
                reason,
            )
        }
    }

    private fun openChannel(profile: RelayProfile, token: String): StandardChannel {
        val url = StandardWire.url(profile.endpoint, profile.hermesProfile, token)
            ?: throw StandardFailure(AndroidHomeUnavailableReason.InvalidBinding)
        val link = StandardChannel(
            httpClient = httpClient,
            readyTimeoutMillis = readyTimeoutMillis,
            onEvent = ::onChannelEvent,
            onEnd = ::onChannelEnd,
            onBinary = ::onChannelBinary,
            onPeerClose = { code -> journal.record("standard websocket closed by peer code=$code") },
        )
        link.connect(url)
        return link
    }

    private fun discardChannelForOtherProfile(profile: RelayProfile) {
        var finishingCleared = false
        val stale = synchronized(inboundLock) {
            val previous = synchronized(stateLock) {
                if (sessionProfileId == null || matchesSession(profile)) return
                val current = channel
                channel = null
                runtimeId = null
                durableId = null
                sessionProfileId = null
                sessionHistoryKey = null
                localHandle = newHandle()
                lastSeq = 0L
                activeTurn = null
                uncertain = false
                finishingCleared = finishing
                finishing = false
                lastUnavailableReason = null
                lastUnavailableProfileId = null
                lastUnavailableHistoryKey = null
                current
            }
            turnObserver = null
            undelivered.clear()
            previous
        }
        stale?.cancel()
        if (finishingCleared) announceFinishing(false)
    }

    /**
     * Makes [link] the held socket with a new session. Fails if the socket
     * ended or the selected Profile identity changed while the reply was in flight.
     */
    private fun commitSession(
        link: StandardChannel,
        profile: RelayProfile,
        ids: StandardSessionIds,
        fresh: Boolean,
        clearFlags: Boolean,
        attemptGeneration: Long,
    ) {
        var finishingCleared = false
        synchronized(stateLock) {
            if (link.isEnded || generation.get() != attemptGeneration || !matchesSelection(profile)) {
                throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
            }
            val previous = channel
            channel = link
            if (fresh || !matchesSession(profile)) localHandle = newHandle()
            sessionProfileId = profile.id
            sessionHistoryKey = profile.historyKey
            runtimeId = ids.runtimeId
            durableId = ids.durableId
            lastSeq = 0L
            activeTurn = null
            lastUnavailableReason = null
            lastUnavailableProfileId = null
            lastUnavailableHistoryKey = null
            if (clearFlags) {
                uncertain = false
                if (finishing) {
                    finishing = false
                    finishingCleared = true
                }
            }
            if (previous != null && previous !== link) previous.cancel()
        }
        if (finishingCleared) announceFinishing(false)
    }

    // ---- new conversation --------------------------------------------------

    override fun newConversation(): AndroidNewConversationResult {
        val result = synchronized(connectMonitor) { createConversation() }
        journal.record(
            when (result) {
                AndroidNewConversationResult.Created ->
                    "standard new_conversation result=created reason=none"
                is AndroidNewConversationResult.Failed ->
                    "standard new_conversation result=failed reason=${result.reason.name}"
            },
        )
        return result
    }

    private fun createConversation(): AndroidNewConversationResult {
        val profile = standardProfile()
            ?: return AndroidNewConversationResult.Failed(AndroidHomeUnavailableReason.MissingBinding)
        discardChannelForOtherProfile(profile)
        val token = credentials.readStandardCredential(profile.id)
            ?: return AndroidNewConversationResult.Failed(AndroidHomeUnavailableReason.InvalidCredential)

        val existing = synchronized(stateLock) {
            channel?.takeIf { !it.isEnded && runtimeId != null && matchesSession(profile) }
        }
        val attemptGeneration = generation.get()
        var openedHere: StandardChannel? = null
        try {
            val link = existing ?: openChannel(profile, token).also { openedHere = it }
            if (generation.get() != attemptGeneration || !matchesSelection(profile)) {
                throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
            }
            val reply = link.request(
                "session.create",
                StandardWire.sessionParams(profile.hermesProfile, resumeId = null),
                requestTimeoutMillis,
            )
            val ids = StandardWire.parseSession(reply, profile.hermesProfile, resumeId = null)
            if (generation.get() != attemptGeneration) {
                throw StandardFailure(AndroidHomeUnavailableReason.TransportUnavailable)
            }
            commitSession(
                link, profile, ids, fresh = true, clearFlags = true,
                attemptGeneration = attemptGeneration,
            )
            synchronized(inboundLock) { undelivered.clear() }
            return AndroidNewConversationResult.Created
        } catch (failure: StandardFailure) {
            // A socket opened only for this attempt goes away; an existing
            // socket and every flag stay exactly as they were.
            openedHere?.cancel()
            return AndroidNewConversationResult.Failed(failure.reason)
        }
    }

    // ---- stop --------------------------------------------------------------

    override fun supportsInterrupt(): Boolean = synchronized(stateLock) {
        channel?.isEnded == false && runtimeId != null
    }

    override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
        val turn: ActiveTurn
        val runtime: String?
        var finishingChanged = false
        synchronized(stateLock) {
            val active = activeTurn ?: return false
            if (active.binding != binding) return false
            turn = active
            runtime = runtimeId
            activeTurn = null
            if (!finishing) {
                finishing = true
                finishingChanged = true
            }
        }
        if (finishingChanged) announceFinishing(true)
        synchronized(inboundLock) {
            deliverToTurnObserver(binding, AndroidNormalizedEvent.TurnInterrupted(binding, "stopped"))
        }
        val remote = when {
            !remoteInterruptVerified -> "not_verified"
            runtime != null &&
                turn.channel.sendAsync("session.interrupt", JSONObject().put("session_id", runtime)) { reply ->
                    if (StandardWire.readAck(reply, interrupt = true) is StandardAck.Accepted) {
                        journal.record("standard interrupt acknowledged")
                    }
                } ->
                "sent"
            else -> "failed"
        }
        journal.record("standard stop local=true remote=$remote")
        return true
    }

    override fun isFinishingPreviousResponse(): Boolean = synchronized(stateLock) { finishing }

    override fun hasUncertainTurn(): Boolean = synchronized(stateLock) { uncertain }

    override fun observeFinishing(onChange: (Boolean) -> Unit): AndroidTurnObservation {
        finishingObservers.add(onChange)
        return AndroidTurnObservation { finishingObservers.remove(onChange) }
    }

    private fun announceFinishing(value: Boolean) {
        journal.record("standard finishing state=$value")
        finishingObservers.forEach { it(value) }
    }

    // ---- teardown ----------------------------------------------------------

    override fun endSession() {
        generation.incrementAndGet()
        val closing = synchronized(stateLock) {
            val current = channel
            channel = null
            runtimeId = null
            activeTurn = null
            current
        }
        closing?.cancel()
        synchronized(inboundLock) { undelivered.clear() }
    }

    override fun close() {
        journal.record("standard client close")
        generation.incrementAndGet()
        val closing = synchronized(stateLock) {
            val current = channel
            channel = null
            runtimeId = null
            durableId = null
            sessionProfileId = null
            sessionHistoryKey = null
            activeTurn = null
            uncertain = false
            finishing = false
            lastUnavailableReason = null
            lastUnavailableProfileId = null
            lastUnavailableHistoryKey = null
            current
        }
        closing?.cancel()
        synchronized(inboundLock) {
            turnObserver = null
            undelivered.clear()
        }
        connectionObserver.set(null)
        finishingObservers.clear()
    }

    // ---- helpers -----------------------------------------------------------

    private fun standardProfile(): RelayProfile? =
        collection().selected?.takeIf { it.mode == RelayProfileMode.Standard }

    private fun matchesSession(profile: RelayProfile): Boolean =
        sessionProfileId == profile.id && sessionHistoryKey == profile.historyKey

    private fun matchesSelection(profile: RelayProfile): Boolean {
        val selected = collection().selected ?: return false
        return selected.id == profile.id && selected.historyKey == profile.historyKey
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos)

    companion object {
        private const val MAX_EARLY_EVENTS = 512
        private const val MAX_UNDELIVERED_EVENTS = 1024
        private val TURN_END_TYPES = setOf(
            "message.complete",
            "error",
            "session.interrupted",
            "turn_complete",
            "turn.complete",
            "turn.completed",
            "turn.end",
            "turn.ended",
            "turn_end",
            "response.complete",
            "response.completed",
            "turn_interrupted",
            "turn.interrupted",
            "turn.cancelled",
            "turn.error",
        )

        private fun newHandle() = "standard-${UUID.randomUUID()}"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
