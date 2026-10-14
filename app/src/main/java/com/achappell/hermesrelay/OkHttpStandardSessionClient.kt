package com.achappell.hermesrelay

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Direct, unmodified Hermes transport. No Home claims, grants, or conversation APIs. */
internal class OkHttpStandardSessionClient(
    private val collection: () -> RelayProfileCollection,
    private val credentials: RelayCredentialStore,
    private val restartStore: StandardRestartStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    private val audioSink: AndroidAudioSink = RecordingAudioSink(),
    private val journal: DiagnosticsJournal = DiagnosticsJournal.None,
    private val requestTimeoutMillis: Long = 10_000,
    private val audioTimeoutMillis: Long = 120_000,
) : AndroidClientPort {
    private class Reply {
        val done = CountDownLatch(1)
        @Volatile var frame: JSONObject? = null
        fun complete(value: JSONObject?) { frame = value; done.countDown() }
        fun await(timeout: Long): JSONObject? = try {
            if (done.await(timeout, TimeUnit.MILLISECONDS)) frame else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private val operations = Any()
    private val state = Any()
    private val pending = ConcurrentHashMap<String, Reply>()
    private val audioTimer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "standard-audio-deadline").apply { isDaemon = true }
    }
    private var socket: WebSocket? = null
    private var generation = 0L
    private var ready = false
    private var closed = false
    private var identity: String? = null
    private var connectedProfile: RelayProfile? = null
    private var runtimeSession: String? = null
    private var storedSession: String? = null
    private var connectionId = ""
    private var uncertain = false
    private val restoredIdentities = mutableSetOf<String>()
    private var restartBlocked = false
    private var turn: AndroidTurnBinding? = null
    private var observationBinding: AndroidTurnBinding? = null
    private var observer: ((AndroidNormalizedEvent) -> Unit)? = null
    private var connectionObserver: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null
    private val events = ArrayDeque<AndroidNormalizedEvent>()
    private var normalizer = HermesEventNormalizer("standard", allowLegacyFrames = false)
    private var rendered = StringBuilder()
    private var stopped = false
    private var textEnded = false
    private var audioSocket: WebSocket? = null
    private var audioDeadline: ScheduledFuture<*>? = null
    private var audioStarted = false
    private var audioEnding = false
    private var audioGeneration = 0L
    private var audioFrameBytes = 2
    private var audioRemainder = ByteArray(0)

    init {
        collection().selected?.takeIf { it.mode == RelayConnectionMode.Standard }?.let(::restoreIdentity)
    }

    /** Read once per identity: our live write-ahead marker is not a process restart. */
    private fun restoreIdentity(profile: RelayProfile) {
        val selectedIdentity = identityOf(profile)
        if (identity != selectedIdentity) {
            detachTransport()
            storedSession = null
            runtimeSession = null
            identity = selectedIdentity
            connectedProfile = profile
        }
        if (!restoredIdentities.add(selectedIdentity)) return
        restartBlocked = try {
            restartStore.hasUnresolvedTurn(profile)
        } catch (_: Exception) {
            journal.record("standard restart result=load_failed")
            true
        }
        if (restartBlocked) {
            uncertain = true
            journal.record("standard restart result=blocked")
        }
    }

    private fun restartUnavailable() = unavailable(
        "The previous Standard turn may be unfinished, or its restart safety state could not be saved. Start a new conversation deliberately; reconnect will not resend it.",
        AndroidHomeUnavailableReason.StaleConversation,
    )

    /** Existing uncertainty is cleared only by a successful explicit New conversation. */
    private fun resolveMarker(explicit: Boolean = false): Boolean {
        if (uncertain && !explicit) return true
        return try {
            restartStore.clearUnresolvedTurn(checkNotNull(connectedProfile))
            true
        } catch (_: Exception) {
            uncertain = true
            restartBlocked = true
            journal.record("standard restart result=clear_failed")
            false
        }
    }

    override fun connectionMode() = RelayConnectionMode.Standard
    override fun hasUncertainTurn() = synchronized(state) { uncertain }
    override fun hasActiveTurn() = synchronized(state) { turn != null }
    override fun hasLiveTransport() = synchronized(state) {
        ready && socket != null && collection().selected?.let(::identityOf) == identity
    }
    override fun supportsInterrupt() = hasLiveTransport()
    override fun pauseOutput() = audioSink.pause()
    override fun resumeOutput() = audioSink.resume()
    override fun setOutputDucked(ducked: Boolean) = audioSink.setDucked(ducked)
    // Explicit resend/discard is not a new session and must not clear this guard.
    override fun prepareForExplicitResend() = Unit

    override fun snapshot(): AndroidClientSnapshot = synchronized(state) {
        val profile = collection().selected?.takeIf { it.mode == RelayConnectionMode.Standard }
        AndroidClientSnapshot(
            titleRes = R.string.android_mode_standard,
            descriptionRes = R.string.android_standard_limits,
            boundaryRes = R.string.android_standard_limits,
            selectedProfile = profile?.let { AndroidProfile(it.id, it.displayName) },
            authorizationState = when {
                profile == null -> AndroidAuthorizationState.NotConfigured
                runCatching { credentials.readStandardCredential(profile.id) }.getOrNull().isNullOrBlank() ->
                    AndroidAuthorizationState.Unavailable
                hasLiveTransport() -> AndroidAuthorizationState.Verified
                else -> AndroidAuthorizationState.Verifying
            },
        )
    }

    override fun reconnect(): AndroidReconnectOutcome = reconnect(requestTimeoutMillis)

    override fun reconnect(attemptTimeoutMillis: Long): AndroidReconnectOutcome = synchronized(operations) {
        connect(newSession = false, timeout = attemptTimeoutMillis)
    }

    override fun newConversation(): AndroidReconnectOutcome = synchronized(operations) {
        connect(newSession = true, timeout = requestTimeoutMillis)
    }

    private fun identityOf(profile: RelayProfile) = "${profile.id}:${profile.mode}:${profile.historyKey}"

    private fun connect(newSession: Boolean, timeout: Long): AndroidReconnectOutcome {
        val profile = collection().selected?.takeIf { it.mode == RelayConnectionMode.Standard }
            ?: return unavailable("Select a Standard profile.", AndroidHomeUnavailableReason.InvalidBinding)
        val selectedIdentity = identityOf(profile)
        synchronized(state) {
            if (closed) return unavailable("The Standard client is closed.")
            if (identity != null && identity != selectedIdentity && (turn != null || uncertain)) {
                return unavailable("Finish this conversation before switching profiles.", AndroidHomeUnavailableReason.UnresolvedTurn)
            }
            restoreIdentity(profile)
            if (!newSession && restartBlocked) return restartUnavailable()
            if (!newSession && ready && identity == selectedIdentity) return connected()
        }
        val token = runCatching { credentials.readStandardCredential(profile.id) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return unavailable("Enter the Standard credential.", AndroidHomeUnavailableReason.InvalidCredential)
        val request = privateRequest(profile, token, audio = false)
            ?: return unavailable("The Standard endpoint is invalid.", AndroidHomeUnavailableReason.InvalidBinding)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.coerceAtLeast(1))
        fun remaining() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
        synchronized(state) {
            if (newSession && turn != null && !textEnded) uncertain = true
        }
        if (!synchronized(state) { ready }) {
            val opened = CountDownLatch(1)
            var failedStatus: Int? = null
            val attempt = synchronized(state) { detachTransport(); ++generation }
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(state) {
                    if (generation != attempt || closed) { webSocket.cancel(); return@synchronized }
                    socket = webSocket
                    ready = true
                    connectionId = UUID.randomUUID().toString()
                    opened.countDown()
                }
                override fun onMessage(webSocket: WebSocket, text: String) = synchronized(state) {
                    if (generation != attempt || socket !== webSocket) return@synchronized
                    val frame = runCatching { JSONObject(text) }.getOrNull() ?: return@synchronized
                    if (frame.optString("jsonrpc") != "2.0") return@synchronized
                    val id = frame.optString("id")
                    if (id.isNotEmpty() && pending.remove(id)?.also { it.complete(frame) } != null) return@synchronized
                    receive(frame)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(state) {
                    if (generation != attempt) return@synchronized
                    failedStatus = response?.code
                    opened.countDown()
                    // A failed upgrade belongs to connect(), not live-transport recovery.
                    if (ready) lostTransport() else detachTransport()
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(state) {
                    if (generation != attempt) return@synchronized
                    webSocket.close(code, null)
                    opened.countDown()
                    lostTransport()
                }
            }
            try {
                httpClient.newWebSocket(request, listener)
                opened.await(remaining(), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Exceptions may contain the authenticated URL. Never propagate or record them.
            }
            if (!synchronized(state) { generation == attempt && ready }) {
                synchronized(state) { detachTransport() }
                journal.record("standard connect result=failed")
                return if (failedStatus == 401 || failedStatus == 403) {
                    journal.record("standard authorization result=rejected")
                    unavailable("Standard authentication was rejected.", AndroidHomeUnavailableReason.Unauthorized)
                } else retryable()
            }
        }
        val durable = synchronized(state) { if (newSession) null else storedSession }
        val method = if (durable == null) "session.create" else "session.resume"
        val params = JSONObject().put("source", "android")
            .put("profile", profile.hermesProfile.ifBlank { "default" })
        if (durable != null) params.put("session_id", durable)
        val frame = rpc(method, params, remaining())
        val result = frame?.optJSONObject("result")
        val runtime = result?.optString("session_id")?.takeIf { it.isNotBlank() }
        val stored = result?.optString("stored_session_id")?.takeIf { it.isNotBlank() } ?: durable
        synchronized(state) {
            if (frame == null || frame.has("error") || runtime == null || stored == null || !ready) {
                // A failed create cannot restore confidence in the previous conversation.
                detachTransport()
                journal.record("standard session result=failed")
                return if (frame?.has("error") == true) {
                    unavailable("Standard could not open the conversation. Start a new conversation deliberately.", AndroidHomeUnavailableReason.RequestRejected)
                } else retryable()
            }
            if (collection().selected?.let(::identityOf) != selectedIdentity) {
                detachTransport()
                return unavailable("The selected Standard profile changed.")
            }
            runtimeSession = runtime
            storedSession = stored
            connectedProfile = profile
            if (newSession) {
                if (!resolveMarker(explicit = true)) {
                    detachTransport()
                    return restartUnavailable()
                }
                restartBlocked = false
                stopAudio()
                turn?.let { emit(AndroidNormalizedEvent.TurnInterrupted(it, "A new conversation was started.")) }
                turn = null
                uncertain = false
                stopped = false
                textEnded = false
            }
            journal.record("standard session method=$method result=connected uncertain=$uncertain")
            return connected()
        }
    }

    private fun connected() = AndroidReconnectOutcome.Connected(
        connectionId = connectionId,
        unresolvedTurn = uncertain || turn != null,
    )

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult = synchronized(operations) admission@{
        val profile = collection().selected
        val text = (request.input as? AndroidTurnInput.Typed)?.text?.trim()
            ?: return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected)
        if (text.isEmpty()) return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.EmptyTypedPrompt)
        val binding: AndroidTurnBinding
        val session: String
        synchronized(state) {
            if (profile != null && profile.mode == RelayConnectionMode.Standard &&
                (identity == identityOf(profile) || (turn == null && !uncertain))) restoreIdentity(profile)
            if (profile == null || profile.mode != RelayConnectionMode.Standard || request.profile.id != profile.id ||
                identity != identityOf(profile)) {
                return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable)
            }
            if (uncertain) return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain)
            if (!ready || turn != null || runtimeSession == null) {
                return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
            }
            try {
                restartStore.markUnresolvedTurn(profile)
            } catch (_: Exception) {
                uncertain = true
                restartBlocked = true
                journal.record("standard restart result=mark_failed")
                return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain)
            }
            session = checkNotNull(runtimeSession)
            binding = AndroidTurnBinding(profile.id, checkNotNull(storedSession), connectionId, UUID.randomUUID().toString())
            stopAudio(preservePendingControls = true)
            turn = binding
            observationBinding = binding
            observer = null
            events.clear()
            normalizer.beginTurn()
            rendered = StringBuilder()
            stopped = false
            textEnded = false
        }
        val frame = rpc("prompt.submit", JSONObject().put("session_id", session).put("text", text).put("surface", "android"))
        synchronized(state) {
            if (frame == null || (!frame.has("error") && frame.optJSONObject("result") == null)) {
                if (textEnded && !uncertain) {
                    journal.record("standard prompt result=accepted")
                    return@admission AndroidInitiationResult.Accepted(binding)
                }
                uncertain = true
                journal.record("standard prompt result=uncertain")
                return@admission AndroidInitiationResult.Uncertain(request, AndroidHomeUnavailableReason.TransportUnavailable)
            }
            if (frame.has("error")) {
                stopAudio()
                turn = null
                events.clear()
                if (!resolveMarker()) {
                    return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain)
                }
                journal.record("standard prompt result=rejected")
                return@admission AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected)
            }
            journal.record("standard prompt result=accepted")
            AndroidInitiationResult.Accepted(binding)
        }
    }

    override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
        synchronized(state) {
            if (observationBinding == binding) {
                observer = onEvent
                while (events.isNotEmpty()) onEvent(events.removeFirst())
            }
        }
        return AndroidTurnObservation { synchronized(state) { if (observer === onEvent) observer = null } }
    }

    override fun observeConnection(onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit): AndroidTurnObservation {
        synchronized(state) { connectionObserver = onEvent }
        return AndroidTurnObservation { synchronized(state) { if (connectionObserver === onEvent) connectionObserver = null } }
    }

    private fun emit(event: AndroidNormalizedEvent) {
        val callback = observer
        if (callback == null) events.addLast(event) else callback(event)
    }

    private fun receive(frame: JSONObject) {
        if (frame.optString("method") != "event") return
        val binding = turn ?: return
        val params = frame.optJSONObject("params") ?: return
        if (params.optString("session_id") != runtimeSession) return
        val type = params.optString("type")
        val payload = params.optJSONObject("payload") ?: JSONObject()
        if (type == "approval.request" || type == "clarify.request" || type == "secret.request" || type == "sudo.request") {
            uncertain = true
            stopped = true
            stopAudio()
            emit(AndroidNormalizedEvent.TurnFailed(binding, "Structured prompts are unsupported in Standard mode. Start a new conversation."))
            journal.record("standard prompt result=unsupported")
            return
        }
        normalizer.normalizeStandard(type, payload, binding).forEach { event ->
            when (event) {
                is AndroidNormalizedEvent.ResponseTextDelta -> if (!stopped) { rendered.append(event.text); emit(event) }
                is AndroidNormalizedEvent.ResponseTextReplace -> if (!stopped) { rendered = StringBuilder(event.text); emit(event) }
                is AndroidNormalizedEvent.TurnCompleted -> {
                    if (textEnded) return@forEach
                    textEnded = true
                    resolveMarker()
                    if (stopped || rendered.isBlank()) {
                        finishTurn(binding, if (stopped) AndroidNormalizedEvent.TurnInterrupted(binding, "The response was stopped.") else event)
                    } else {
                        emit(AndroidNormalizedEvent.TextCompleted(binding, rendered.toString()))
                        startAudio(binding, rendered.toString())
                    }
                }
                is AndroidNormalizedEvent.TurnInterrupted, is AndroidNormalizedEvent.TurnFailed -> {
                    textEnded = true
                    resolveMarker()
                    stopAudio()
                    val terminal = if (event is AndroidNormalizedEvent.TurnInterrupted) {
                        AndroidNormalizedEvent.TurnInterrupted(binding, "Hermes ended the interrupted response.")
                    } else {
                        AndroidNormalizedEvent.TurnFailed(binding, "Standard could not finish the response.")
                    }
                    finishTurn(binding, terminal)
                }
                else -> if (!stopped) emit(event)
            }
        }
    }

    private fun finishTurn(binding: AndroidTurnBinding, terminal: AndroidNormalizedEvent) {
        if (turn != binding) return
        turn = null
        emit(terminal)
    }

    override fun interruptTurn(binding: AndroidTurnBinding): Boolean = synchronized(operations) {
        val session = synchronized(state) {
            if (turn != binding) return@synchronized null
            stopped = true
            stopAudio()
            if (textEnded) {
                finishTurn(binding, AndroidNormalizedEvent.TurnInterrupted(binding, "Response playback stopped."))
                return@synchronized null
            }
            runtimeSession
        } ?: return@synchronized synchronized(state) { textEnded && observationBinding == binding }
        val reply = rpc("session.interrupt", JSONObject().put("session_id", session))
        val acknowledged = reply != null && !reply.has("error") &&
            reply.optJSONObject("result")?.optString("status") == "interrupted"
        synchronized(state) {
            if (acknowledged) {
                textEnded = true
                resolveMarker()
                finishTurn(binding, AndroidNormalizedEvent.TurnInterrupted(binding, "Hermes confirmed the interrupt."))
            }
            journal.record("standard interrupt acknowledged=$acknowledged")
        }
        acknowledged
    }

    private fun startAudio(binding: AndroidTurnBinding, text: String) {
        val profile = connectedProfile ?: return audioFailed(binding)
        val token = runCatching { credentials.readStandardCredential(profile.id) }.getOrNull()
            ?: return audioFailed(binding)
        val request = privateRequest(profile, token, audio = true) ?: return audioFailed(binding)
        val streamGeneration = ++audioGeneration
        emit(AndroidNormalizedEvent.AudioBuffering(binding))
        audioStarted = false
        audioEnding = false
        audioRemainder = ByteArray(0)
        audioDeadline = audioTimer.schedule({ synchronized(state) { if (turn == binding) audioFailed(binding) } }, audioTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            audioSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(state) {
                    if (turn != binding || stopped || audioSocket !== webSocket) { webSocket.cancel(); return@synchronized }
                    if (!webSocket.send(JSONObject().put("text", text).toString()) ||
                        !webSocket.send(JSONObject().put("done", true).toString())) audioFailed(binding)
                }
                override fun onMessage(webSocket: WebSocket, text: String) = synchronized(state) {
                    if (turn != binding || audioSocket !== webSocket || stopped) return@synchronized
                    val frame = runCatching { JSONObject(text) }.getOrNull() ?: return@synchronized audioFailed(binding)
                    when (frame.optString("type").ifBlank { frame.optString("kind") }) {
                        "start" -> {
                            val format = AndroidAudioFormat(frame.optInt("sample_rate"), frame.optInt("channels"), 2, "pcm_s16le")
                            if (audioStarted || audioEnding || !format.isSupported || !runCatching {
                                audioSink.start(format) {
                                    synchronized(state) {
                                        if (turn == binding && !stopped && audioGeneration == streamGeneration &&
                                            (audioStarted || audioEnding)
                                        ) emit(AndroidNormalizedEvent.AudioStarted(binding, format))
                                    }
                                }
                            }.getOrDefault(false)) {
                                audioFailed(binding)
                            } else {
                                audioStarted = true
                                audioFrameBytes = bytesPerFrame(format.channels)
                            }
                        }
                        "end" -> {
                            if (!audioStarted || audioEnding || audioRemainder.isNotEmpty()) return@synchronized audioFailed(binding)
                            audioEnding = true
                            audioSocket = null
                            webSocket.close(1000, null)
                            try {
                                audioSink.finish(
                                    onDrained = { synchronized(state) {
                                        if (turn == binding && !stopped && audioGeneration == streamGeneration) {
                                            audioDeadline?.cancel(false)
                                            audioDeadline = null
                                            emit(AndroidNormalizedEvent.AudioEnded(binding))
                                            finishTurn(binding, AndroidNormalizedEvent.TurnCompleted(binding))
                                            journal.record("standard audio result=drained")
                                        }
                                    } },
                                    onFailure = { synchronized(state) {
                                        if (turn == binding && audioGeneration == streamGeneration) audioFailed(binding)
                                    } },
                                )
                            } catch (_: Exception) { audioFailed(binding) }
                        }
                        "error", "fallback", "unavailable" -> audioFailed(binding)
                        else -> audioFailed(binding)
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(state) {
                    if (turn != binding || audioSocket !== webSocket || stopped) return@synchronized
                    if (!audioStarted || audioEnding) return@synchronized audioFailed(binding)
                    val received = bytes.toByteArray()
                    val joined = if (audioRemainder.isEmpty()) received else audioRemainder + received
                    val complete = joined.size - joined.size % audioFrameBytes
                    audioRemainder = if (complete == joined.size) ByteArray(0) else joined.copyOfRange(complete, joined.size)
                    if (complete > 0) {
                        try {
                            audioSink.write(if (complete == joined.size) joined else joined.copyOf(complete))
                            emit(AndroidNormalizedEvent.AudioChunkReceived(binding))
                        } catch (_: Exception) { audioFailed(binding) }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(state) {
                    if (turn == binding && audioSocket === webSocket) audioFailed(binding)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(state) {
                    webSocket.close(code, null)
                    if (turn == binding && audioSocket === webSocket) audioFailed(binding)
                }
            })
        } catch (_: Exception) { audioFailed(binding) }
    }

    private fun audioFailed(binding: AndroidTurnBinding) {
        stopAudio()
        emit(AndroidNormalizedEvent.AudioFailed(binding, "Standard response audio is unavailable."))
        finishTurn(binding, AndroidNormalizedEvent.TurnCompleted(binding))
        journal.record("standard audio result=failed")
    }

    private fun stopAudio(preservePendingControls: Boolean = false) {
        audioGeneration++
        audioDeadline?.cancel(false)
        audioDeadline = null
        audioSocket?.cancel()
        audioSocket = null
        audioStarted = false
        audioEnding = false
        audioRemainder = ByteArray(0)
        runCatching { if (preservePendingControls) audioSink.prepareTurn() else audioSink.cancel() }
    }

    private fun rpc(method: String, params: JSONObject, timeout: Long = requestTimeoutMillis): JSONObject? {
        val id = UUID.randomUUID().toString()
        val reply = Reply()
        val transport = synchronized(state) { socket?.takeIf { ready } } ?: return null
        pending[id] = reply
        return try {
            val sent = runCatching {
                transport.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString())
            }.getOrDefault(false)
            if (sent) reply.await(timeout) else null
        } finally { pending.remove(id) }
    }

    /** Build authenticated requests privately; callers must never log the request or an exception. */
    private fun privateRequest(profile: RelayProfile, token: String, audio: Boolean): Request? = runCatching {
        val endpoint = StandardEndpoint.canonicalize(profile.endpoint).replaceFirst("wss:", "https:")
        val url = endpoint.toHttpUrl()
        val builder = url.newBuilder().addQueryParameter("token", token)
        if (audio) {
            val path = url.encodedPath.removeSuffix("/ws") + "/audio/speak-stream"
            builder.encodedPath(path).setQueryParameter("profile", profile.hermesProfile.ifBlank { "default" })
        }
        Request.Builder().url(builder.build()).build()
    }.getOrNull()

    private fun lostTransport() {
        val previous = connectionId
        if (turn != null && !textEnded) uncertain = true
        val disconnected = AndroidNormalizedEvent.Disconnected(previous, "The Standard connection was lost. Reconnect does not resend the turn.")
        detachTransport()
        if (turn != null) emit(disconnected)
        turn = null
        connectionObserver?.invoke(disconnected)
        journal.record("standard transport result=lost uncertain=$uncertain")
    }

    private fun detachTransport() {
        ++generation
        ready = false
        socket?.cancel()
        socket = null
        stopAudio()
        pending.values.forEach { it.complete(null) }
        pending.clear()
    }

    override fun closeForLifecycle() = synchronized(state) {
        if (turn != null && !textEnded) uncertain = true
        detachTransport()
        turn = null
        journal.record("standard transport result=closed uncertain=$uncertain")
    }

    override fun endSession() = closeForLifecycle()

    override fun close() {
        synchronized(state) {
            if (closed) return
            closeForLifecycle()
            closed = true
            observer = null
            connectionObserver = null
            events.clear()
        }
        audioTimer.shutdownNow()
        audioSink.close()
    }

    private fun unavailable(message: String, reason: AndroidHomeUnavailableReason = AndroidHomeUnavailableReason.TransportUnavailable) =
        AndroidReconnectOutcome.Unrecoverable(message, reason)

    private fun retryable() = AndroidReconnectOutcome.Retryable(
        "Standard is unavailable. Reconnect will not resend a turn.", AndroidHomeUnavailableReason.TransportUnavailable,
    )
}
