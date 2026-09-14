package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Home bridge transport for the Android Client.
 *
 * The endpoint-facing socket is the only live wire this class opens. Home owns
 * the Standard Hermes bearer and the Standard-side sockets. A
 * local bridge connection ID is used only to reject stale callbacks; it is not
 * a Hermes Session ID and never crosses the endpoint boundary.
 */
internal class OkHttpRelaySessionClient(
    private val collection: () -> RelayProfileCollection,
    private val credentials: RelayCredentialStore,
    private val httpClient: OkHttpClient = defaultClient(),
    private val helloTimeoutMillis: Long = DEFAULT_HELLO_TIMEOUT_MILLIS,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val audioSink: AndroidAudioSink = RecordingAudioSink(),
) : AndroidClientPort {

    private val activeSocket = AtomicReference<WebSocket?>(null)
    private val activeProfileId = AtomicReference<String?>(null)
    private val connectionId = AtomicReference<String?>(null)
    private val activeTurn = AtomicReference<AndroidTurnBinding?>(null)
    private val observer = AtomicReference<TurnObserver?>(null)
    private val normalizer = AtomicReference<HermesEventNormalizer?>(null)
    private val route = AtomicReference<AndroidRoute?>(null)
    private val capabilities = AtomicReference(AndroidHomeCapabilities())
    private val lastUnavailableReason = AtomicReference<AndroidHomeUnavailableReason?>(null)
    private val lastUnavailableProfileId = AtomicReference<String?>(null)
    private val ready = AtomicBoolean(false)
    private val audioActive = AtomicBoolean(false)
    private val interruptRequested = AtomicBoolean(false)
    private val terminalObserved = AtomicBoolean(false)
    private val audioDrainPending = AtomicBoolean(false)
    private val hasOpenedConversation = AtomicBoolean(false)
    private val reconnectRequired = AtomicBoolean(false)
    private val reconnectRequiredProfileId = AtomicReference<String?>(null)
    private val reconnectRequiredConversationHandle = AtomicReference<String?>(null)
    private val lastHandshakeProfileId = AtomicReference<String?>(null)
    private val lastHandshakeConversationHandle = AtomicReference<String?>(null)
    private val transportGeneration = AtomicLong(0)
    private val connectionObserver = AtomicReference<((AndroidNormalizedEvent.Disconnected) -> Unit)?>(null)
    private val pending = ConcurrentHashMap<String, PendingRpc>()
    private val queuedFrames = ConcurrentLinkedQueue<InboundFrame>()
    private val inboundLock = Any()
    @Volatile
    private var audioBytesPerFrame = 2
    @Volatile
    private var audioBytesRemainder = 0

    private class TurnObserver(
        val binding: AndroidTurnBinding,
        val onEvent: (AndroidNormalizedEvent) -> Unit,
    )

    private class PendingRpc {
        private val settled = CountDownLatch(1)
        private val frame = AtomicReference<JSONObject?>(null)

        fun complete(response: JSONObject) {
            frame.set(response)
            settled.countDown()
        }

        fun fail() {
            settled.countDown()
        }

        fun await(timeoutMillis: Long): JSONObject? {
            if (!settled.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null
            return frame.get()
        }
    }

    private sealed interface InboundFrame {
        data class Text(
            val value: String,
            val connectionId: String?,
            val binding: AndroidTurnBinding?,
        ) : InboundFrame

        data class Binary(
            val value: ByteString,
            val connectionId: String?,
            val binding: AndroidTurnBinding?,
        ) : InboundFrame
    }

    override fun snapshot(): AndroidClientSnapshot {
        val profile = collection().selected
        val homeBinding = profile?.homeBinding
        val homeCredential = profile?.id?.let(credentials::readHomeCredential)
        val recordedUnavailableReason = if (lastUnavailableProfileId.get() == profile?.id) {
            lastUnavailableReason.get()
        } else {
            null
        }
        val isActive = profile != null &&
            activeProfileId.get() == profile.id &&
            activeSocket.get() != null &&
            ready.get()

        val authorization = when {
            profile == null -> AndroidAuthorizationState.NotConfigured
            homeBinding == null -> AndroidAuthorizationState.NotConfigured
            homeCredential == null -> AndroidAuthorizationState.Unavailable
            recordedUnavailableReason != null -> AndroidAuthorizationState.Unavailable
            isActive -> AndroidAuthorizationState.Verified
            else -> AndroidAuthorizationState.Verifying
        }
        val bindingUnavailableReason = when {
            profile?.homeBinding == null && profile != null ->
                AndroidHomeUnavailableReason.MissingBinding
            profile != null && homeCredential == null ->
                AndroidHomeUnavailableReason.InvalidCredential
            else -> null
        }

        return AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile?.let {
                AndroidProfile(it.id, it.displayName, it.deviceId)
            },
            authorizationState = authorization,
            route = if (isActive) route.get() else null,
            capabilities = if (isActive) capabilities.get() else AndroidHomeCapabilities(),
            unavailableReason = bindingUnavailableReason ?: recordedUnavailableReason,
        )
    }

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
        val profile = collection().selected
            ?: return AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable)
        val binding = profile.homeBinding
            ?: return AndroidInitiationResult.Rejected(
                AndroidInitiationFailure.HomeBindingUnavailable,
            )
        if (request.profile.id != profile.id) {
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable)
        }
        val socket = activeSocket.get()
        val localConnection = connectionId.get()
        if (
            socket == null ||
            localConnection == null ||
            !ready.get() ||
            activeProfileId.get() != profile.id
        ) {
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
        }
        if (activeTurn.get() != null && !terminalObserved.get()) {
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
        }

        val text = when (val input = request.input) {
            is AndroidTurnInput.Typed -> input.text
            AndroidTurnInput.TapToSpeak -> return AndroidInitiationResult.Rejected(
                AndroidInitiationFailure.SessionUnavailable,
            )
        }
        if (text.isBlank()) {
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.EmptyTypedPrompt)
        }

        val normalizedRequest = request.copy(
            profile = AndroidProfile(profile.id, profile.displayName, profile.deviceId),
        )
        val requestId = rpcId("prompt")
        val response = PendingRpc()
        pending[requestId] = response
        normalizer.get()?.beginTurn()
        audioActive.set(false)
        audioDrainPending.set(false)
        synchronized(inboundLock) {
            audioBytesPerFrame = 2
            audioBytesRemainder = 0
        }
        audioSink.cancel()
        interruptRequested.set(false)
        terminalObserved.set(false)

        val sent = socket.send(
            rpcRequest(
                id = requestId,
                method = "prompt.submit",
                params = JSONObject()
                    .put("conversation_handle", binding.conversationHandle)
                    .put("text", text),
            ).toString(),
        )
        if (!sent) {
            pending.remove(requestId)
            return AndroidInitiationResult.Uncertain(
                request = normalizedRequest,
                reason = AndroidHomeUnavailableReason.TransportUnavailable,
            )
        }

        val frame = response.await(requestTimeoutMillis)
        pending.remove(requestId)
        if (frame == null) {
            return AndroidInitiationResult.Uncertain(
                request = normalizedRequest,
                reason = AndroidHomeUnavailableReason.TransportTimeout,
            )
        }

        if (!isHomeEnvelope(frame)) {
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        frame.optJSONObject("error")?.let { error ->
            return when (errorCode(error)) {
                "request_rejected" -> AndroidInitiationResult.Rejected(
                    AndroidInitiationFailure.RequestRejected,
                )
                else -> AndroidInitiationResult.Uncertain(
                    normalizedRequest,
                    unavailableReason(errorCode(error)),
                )
            }
        }
        val result = frame.optJSONObject("result")
            ?: return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ProtocolError,
            )
        if (result.optInt("schema", 0) != SCHEMA_VERSION) {
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        if (result.optString("status") != "submitted") {
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        if (result.optString("conversation_handle") != binding.conversationHandle) {
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ConversationMismatch,
            )
        }
        val turnId = result.optString("turn_id")
        if (turnId.isBlank()) {
            return AndroidInitiationResult.Uncertain(
                normalizedRequest,
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }

        val accepted = AndroidTurnBinding(
            profileId = profile.id,
            conversationHandle = binding.conversationHandle,
            connectionId = localConnection,
            turnId = turnId,
        )
        synchronized(inboundLock) {
            activeTurn.set(accepted)
        }
        return AndroidInitiationResult.Accepted(accepted)
    }

    override fun observeTurn(
        binding: AndroidTurnBinding,
        onEvent: (AndroidNormalizedEvent) -> Unit,
    ): AndroidTurnObservation {
        val registered = TurnObserver(binding, onEvent)
        synchronized(inboundLock) {
            observer.set(registered)
            flushQueued()
        }
        return AndroidTurnObservation {
            synchronized(inboundLock) {
                observer.compareAndSet(registered, null)
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

    override fun reconnect(): AndroidReconnectOutcome {
        val profile = collection().selected
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "No Android Profile is selected.",
                AndroidHomeUnavailableReason.MissingBinding,
            )
        fun unavailable(
            message: String,
            reason: AndroidHomeUnavailableReason,
        ): AndroidReconnectOutcome.Unrecoverable {
            lastUnavailableReason.set(reason)
            lastUnavailableProfileId.set(profile.id)
            return AndroidReconnectOutcome.Unrecoverable(message, reason)
        }
        val homeBinding = profile.homeBinding
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "This Profile has not completed Home pairing.",
                AndroidHomeUnavailableReason.MissingBinding,
            )
        if (
            homeBinding.conversationHandle.isBlank() ||
            homeBinding.conversationHandle.length > MAX_CONVERSATION_HANDLE_LENGTH
        ) {
            return unavailable(
                "The Home conversation binding is malformed.",
                AndroidHomeUnavailableReason.InvalidBinding,
            )
        }
        if (RelayProfileValidator.validateApprovedHomeRoute(homeBinding.approvedRoute) != null) {
            return unavailable(
                "The approved Home bridge route is malformed or not secure.",
                AndroidHomeUnavailableReason.InvalidBinding,
            )
        }
        val credential = credentials.readHomeCredential(profile.id)
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "The Home Device credential cannot be read.",
                AndroidHomeUnavailableReason.InvalidCredential,
            )
        if (!HomeCredentialValidator.isValid(credential)) {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home Device credential is malformed.",
                AndroidHomeUnavailableReason.InvalidCredential,
            )
        }

        closeTransport()
        lastUnavailableReason.set(null)
        lastUnavailableProfileId.set(null)

        val attemptGeneration = transportGeneration.get()
        val sameHandshakeBinding =
            lastHandshakeProfileId.get() == profile.id &&
                lastHandshakeConversationHandle.get() == homeBinding.conversationHandle
        val sameReconnectRequiredBinding =
            reconnectRequiredProfileId.get() == profile.id &&
                reconnectRequiredConversationHandle.get() == homeBinding.conversationHandle
        if (!sameHandshakeBinding && !sameReconnectRequiredBinding) {
            reconnectRequired.set(false)
        }
        val handshakeMethod = if (
            (hasOpenedConversation.get() && sameHandshakeBinding) ||
                (reconnectRequired.get() && sameReconnectRequiredBinding)
        ) {
            "conversation.reconnect"
        } else {
            "conversation.open"
        }

        val localConnection = "bridge-${UUID.randomUUID()}"
        val openId = rpcId("open")
        val openResponse = PendingRpc()
        pending[openId] = openResponse
        val handshakeDone = AtomicBoolean(false)
        val handshakeCommitted = AtomicBoolean(false)
        val handshakeFailure = AtomicReference<AndroidReconnectOutcome?>(null)
        val settled = CountDownLatch(1)

        val request = runCatching {
            Request.Builder()
                .url(bridgeUrl(homeBinding.approvedRoute))
                .header("Authorization", "Device $credential")
                .build()
        }.getOrElse {
            return unavailable(
                "The Home bridge route is malformed.",
                AndroidHomeUnavailableReason.InvalidBinding,
            )
        }

        val socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (transportGeneration.get() != attemptGeneration) {
                        webSocket.cancel()
                        return
                    }
                    activeSocket.set(webSocket)
                    val sent = webSocket.send(
                        rpcRequest(
                            id = openId,
                            method = handshakeMethod,
                            params = JSONObject().put(
                                "conversation_handle",
                                homeBinding.conversationHandle,
                            ),
                        ).toString(),
                    )
                    if (!sent) {
                        handshakeFailure.set(
                            AndroidReconnectOutcome.Retryable(
                                "The Home bridge refused the open request.",
                                AndroidHomeUnavailableReason.TransportUnavailable,
                            ),
                        )
                        settled.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (transportGeneration.get() != attemptGeneration) return
                    if (handshakeDone.get() && activeSocket.get() !== webSocket) return
                    if (handshakeDone.get() && !handshakeCommitted.get()) return
                    val frame = runCatching { JSONObject(text) }.getOrNull()
                    if (frame == null) {
                        if (!handshakeDone.get()) {
                            handshakeFailure.set(
                                AndroidReconnectOutcome.Unrecoverable(
                                    "The Home bridge sent invalid JSON.",
                                    AndroidHomeUnavailableReason.ProtocolError,
                                ),
                            )
                            settled.countDown()
                        } else {
                            dispatch(text)
                        }
                        return
                    }
                    val id = frame.optString("id").takeIf { it.isNotBlank() }
                    if (id == openId) {
                        openResponse.complete(frame)
                        handshakeDone.set(true)
                        settled.countDown()
                        return
                    }
                    if (id != null && pending.remove(id)?.also { it.complete(frame) } != null) {
                        return
                    }
                    dispatch(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (transportGeneration.get() != attemptGeneration) return
                    if (activeSocket.get() !== webSocket) return
                    if (!handshakeCommitted.get()) return
                    dispatch(bytes)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (transportGeneration.get() != attemptGeneration) return
                    // A non-101 HTTP response fails before onOpen, so there
                    // is no activeSocket to compare yet. Once a connection
                    // has completed its handshake, the identity check below
                    // rejects late callbacks from a superseded socket.
                    if (handshakeDone.get() && activeSocket.get() !== webSocket) return
                    if (!handshakeDone.get()) {
                        handshakeFailure.set(classify(t, response))
                        settled.countDown()
                    } else if (!handshakeCommitted.get()) {
                        handshakeFailure.set(
                            AndroidReconnectOutcome.Retryable(
                                "The Home bridge closed before readiness was committed.",
                                AndroidHomeUnavailableReason.TransportUnavailable,
                            ),
                        )
                        settled.countDown()
                    } else {
                        reportDisconnect(
                            webSocket,
                            t.message ?: "The Home bridge connection failed.",
                        )
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (transportGeneration.get() != attemptGeneration) return
                    if (handshakeDone.get() && activeSocket.get() !== webSocket) return
                    if (!handshakeDone.get()) {
                        handshakeFailure.set(
                            AndroidReconnectOutcome.Retryable(
                                reason.ifBlank { "The Home bridge closed the connection." },
                                AndroidHomeUnavailableReason.TransportUnavailable,
                            ),
                        )
                        settled.countDown()
                    } else if (!handshakeCommitted.get()) {
                        handshakeFailure.set(
                            AndroidReconnectOutcome.Retryable(
                                "The Home bridge closed before readiness was committed.",
                                AndroidHomeUnavailableReason.TransportUnavailable,
                            ),
                        )
                        settled.countDown()
                    } else {
                        reportDisconnect(
                            webSocket,
                            reason.ifBlank { "The Home bridge closed the connection." },
                        )
                    }
                }
            },
        )

        val answered = settled.await(helloTimeoutMillis, TimeUnit.MILLISECONDS)
        pending.remove(openId)
        val result = when {
            !answered -> AndroidReconnectOutcome.Retryable(
                "The Home bridge did not become ready in time.",
                AndroidHomeUnavailableReason.TransportTimeout,
            )
            handshakeFailure.get() != null -> handshakeFailure.get()!!
            else -> readOpenResult(
                frame = openResponseFrame(openResponse),
                expectedHandle = homeBinding.conversationHandle,
                connectionId = localConnection,
            )
        }

        if (transportGeneration.get() != attemptGeneration) {
            socket.cancel()
            return AndroidReconnectOutcome.Retryable(
                "The Home bridge attempt was superseded.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
        }

        if (result is AndroidReconnectOutcome.Connected) {
            synchronized(inboundLock) {
                if (handshakeFailure.get() != null) {
                    socket.cancel()
                    return AndroidReconnectOutcome.Retryable(
                        "The Home bridge closed before readiness was committed.",
                        AndroidHomeUnavailableReason.TransportUnavailable,
                    )
                }
                activeSocket.set(socket)
                activeProfileId.set(profile.id)
                connectionId.set(result.connectionId)
                route.set(result.route)
                capabilities.set(result.capabilities)
                ready.set(true)
                normalizer.set(HermesEventNormalizer(profile.id, allowLegacyFrames = false))
                hasOpenedConversation.set(true)
                reconnectRequired.set(false)
                reconnectRequiredProfileId.set(null)
                reconnectRequiredConversationHandle.set(null)
                lastHandshakeProfileId.set(profile.id)
                lastHandshakeConversationHandle.set(homeBinding.conversationHandle)
                handshakeCommitted.set(true)
                lastUnavailableReason.set(null)
                lastUnavailableProfileId.set(null)
            }
        } else {
            val resultReason = when (result) {
                is AndroidReconnectOutcome.Retryable -> result.reasonCode
                is AndroidReconnectOutcome.Unrecoverable -> result.reasonCode
                is AndroidReconnectOutcome.Connected -> null
            }
            if (resultReason == AndroidHomeUnavailableReason.ReconnectRequired) {
                reconnectRequired.set(true)
                reconnectRequiredProfileId.set(profile.id)
                reconnectRequiredConversationHandle.set(homeBinding.conversationHandle)
            } else {
                reconnectRequired.set(false)
                reconnectRequiredProfileId.set(null)
                reconnectRequiredConversationHandle.set(null)
            }
            socket.cancel()
            ready.set(false)
            activeSocket.set(null)
            lastUnavailableReason.set(
                resultReason,
            )
            lastUnavailableProfileId.set(profile.id)
        }
        return result
    }

    override fun supportsInterrupt(): Boolean = ready.get() && capabilities.get().interrupt

    override fun hasActiveTurn(): Boolean = activeTurn.get() != null

    override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
        val socket = activeSocket.get() ?: return false
        if (
            !supportsInterrupt() ||
            activeTurn.get() != binding ||
            terminalObserved.get()
        ) return false

        audioActive.set(false)
        audioDrainPending.set(false)
        synchronized(inboundLock) {
            audioBytesRemainder = 0
        }
        audioSink.cancel()
        interruptRequested.set(true)
        val requestId = rpcId("interrupt")
        val response = PendingRpc()
        pending[requestId] = response
        val sent = socket.send(
            rpcRequest(
                id = requestId,
                method = "session.interrupt",
                params = JSONObject()
                    .put("conversation_handle", binding.conversationHandle)
                    .put("turn_id", binding.turnId),
            ).toString(),
        )
        if (!sent) {
            pending.remove(requestId)
            interruptRequested.set(false)
            reportDisconnect(socket, "The Home bridge refused the interrupt request.")
        } else {
            Thread {
                val frame = response.await(requestTimeoutMillis)
                pending.remove(requestId)
                val acknowledged = frame?.let { responseFrame ->
                    isHomeEnvelope(responseFrame) &&
                        responseFrame.optJSONObject("error") == null &&
                        responseFrame.optJSONObject("result")?.let { result ->
                            result.optInt("schema", 0) == SCHEMA_VERSION &&
                                result.optString("status").lowercase() in INTERRUPT_ACK_STATUSES
                        } == true
                } == true
                if (!acknowledged) {
                    interruptRequested.set(false)
                }
            }.apply {
                name = "hermes-interrupt-await"
                isDaemon = true
            }.start()
        }
        return sent
    }

    /** Compatibility entry point used by the pre-Home live tests. */
    fun disconnect() = close()

    override fun close() {
        closeTransport()
        observer.set(null)
        activeTurn.set(null)
        terminalObserved.set(false)
        queuedFrames.clear()
        audioSink.close()
    }

    private fun closeTransport() {
        transportGeneration.incrementAndGet()
        val socket = activeSocket.getAndSet(null)
        // Lifecycle teardown is not a protocol handshake. Force cancellation
        // so a peer that ignores the close frame cannot keep the app's socket
        // and OkHttp worker alive.
        socket?.cancel()
        ready.set(false)
        activeProfileId.set(null)
        connectionId.set(null)
        route.set(null)
        capabilities.set(AndroidHomeCapabilities())
        interruptRequested.set(false)
        audioActive.set(false)
        audioDrainPending.set(false)
        synchronized(inboundLock) {
            audioBytesRemainder = 0
        }
        audioSink.cancel()
        terminalObserved.set(false)
        queuedFrames.clear()
        pending.values.forEach(PendingRpc::fail)
        pending.clear()
    }

    private fun dispatch(
        text: String,
        taggedBinding: AndroidTurnBinding? = null,
    ) {
        synchronized(inboundLock) {
            val binding = activeTurn.get()
            val currentObserver = observer.get()
            if (taggedBinding != null && taggedBinding != binding) return
            if (binding == null || currentObserver == null) {
                enqueue(InboundFrame.Text(text, connectionId.get(), taggedBinding ?: binding))
                return
            }
            if (currentObserver.binding != binding) return
            val events = normalizer.get()?.normalize(text, binding).orEmpty()
            events.forEach { event -> handleEvent(event, currentObserver) }
        }
    }

    private fun dispatch(
        bytes: ByteString,
        taggedBinding: AndroidTurnBinding? = null,
    ) {
        synchronized(inboundLock) {
            val binding = activeTurn.get()
            val currentObserver = observer.get()
            if (taggedBinding != null && taggedBinding != binding) return
            if (binding == null || currentObserver == null) {
                enqueue(InboundFrame.Binary(bytes, connectionId.get(), taggedBinding ?: binding))
                return
            }
            if (currentObserver.binding != binding) return
            if (!audioActive.get()) {
                deliver(
                    currentObserver,
                    AndroidNormalizedEvent.AudioFailed(
                        binding,
                        "The Home bridge sent audio outside an active stream.",
                    ),
                )
                return
            }
            audioSink.write(bytes.toByteArray())
            audioBytesRemainder = (audioBytesRemainder + bytes.size) % audioBytesPerFrame
            deliver(currentObserver, AndroidNormalizedEvent.AudioChunkReceived(binding))
        }
    }

    private fun handleEvent(
        event: AndroidNormalizedEvent,
        currentObserver: TurnObserver,
    ) {
        when (event) {
            is AndroidNormalizedEvent.AudioStarted -> {
                if (terminalObserved.get() || interruptRequested.get()) return
                if (audioActive.get() || audioDrainPending.get()) {
                    audioActive.set(false)
                    audioDrainPending.set(false)
                    audioSink.cancel()
                    deliver(
                        currentObserver,
                        AndroidNormalizedEvent.AudioFailed(
                            event.binding,
                            "The Home bridge started response audio twice.",
                        ),
                    )
                    return
                }
                val format = event.format
                if (
                    !capabilities.get().audio ||
                    format == null ||
                    !format.isSupported ||
                    !audioSink.start(format)
                ) {
                    audioActive.set(false)
                    audioBytesRemainder = 0
                    deliver(
                        currentObserver,
                        AndroidNormalizedEvent.AudioFailed(
                            event.binding,
                            "This device cannot play the Home response audio format.",
                        ),
                    )
                } else {
                    audioActive.set(true)
                    audioBytesPerFrame = bytesPerFrame(format.channels)
                    audioBytesRemainder = 0
                    deliver(currentObserver, event)
                }
            }
            is AndroidNormalizedEvent.AudioEnded -> {
                if (!audioActive.getAndSet(false)) {
                    deliver(
                        currentObserver,
                        AndroidNormalizedEvent.AudioFailed(
                            event.binding,
                            "The Home bridge ended response audio before a valid stream.",
                        ),
                    )
                    clearTurnIfTerminal(event.binding)
                } else if (audioBytesRemainder != 0) {
                    audioBytesRemainder = 0
                    audioSink.cancel()
                    deliver(
                        currentObserver,
                        AndroidNormalizedEvent.AudioFailed(
                            event.binding,
                            "The Home bridge ended on an incomplete PCM frame.",
                        ),
                    )
                    clearTurnIfTerminal(event.binding)
                } else {
                    audioDrainPending.set(true)
                    audioSink.finish(
                        onDrained = {
                            audioDrainPending.set(false)
                            audioBytesRemainder = 0
                            if (!interruptRequested.get()) {
                                deliverIfCurrent(currentObserver, event)
                            }
                            clearTurnIfTerminal(event.binding)
                        },
                        onFailure = { reason ->
                            audioDrainPending.set(false)
                            audioBytesRemainder = 0
                            if (!interruptRequested.get()) {
                                deliverIfCurrent(
                                    currentObserver,
                                    AndroidNormalizedEvent.AudioFailed(event.binding, reason),
                                )
                            }
                            clearTurnIfTerminal(event.binding)
                        },
                    )
                }
            }
            is AndroidNormalizedEvent.AudioFailed -> {
                audioActive.set(false)
                audioDrainPending.set(false)
                audioBytesRemainder = 0
                audioSink.cancel()
                if (!interruptRequested.get()) deliver(currentObserver, event)
                clearTurnIfTerminal(event.binding)
            }
            is AndroidNormalizedEvent.TurnCompleted -> {
                terminalObserved.set(true)
                deliver(currentObserver, event)
                if (!audioActive.get() && !audioDrainPending.get()) clearTurn(event.binding)
            }
            is AndroidNormalizedEvent.TurnFailed,
            is AndroidNormalizedEvent.TurnInterrupted,
            -> {
                audioActive.set(false)
                audioDrainPending.set(false)
                audioBytesRemainder = 0
                audioSink.cancel()
                interruptRequested.set(false)
                deliver(currentObserver, event)
                clearTurn(event.binding)
            }
            else -> deliver(currentObserver, event)
        }
    }

    private fun clearTurnIfTerminal(binding: AndroidTurnBinding?) {
        if (terminalObserved.get()) clearTurn(binding)
    }

    private fun clearTurn(binding: AndroidTurnBinding?) {
        if (binding == null) return
        val current = activeTurn.get()
        if (current == binding && activeTurn.compareAndSet(current, null)) {
            terminalObserved.set(false)
        }
    }

    private fun deliverIfCurrent(observer: TurnObserver, event: AndroidNormalizedEvent) {
        if (this.observer.get() == observer && activeTurn.get() == observer.binding) {
            deliver(observer, event)
        }
    }

    private fun deliver(observer: TurnObserver, event: AndroidNormalizedEvent) {
        if (this.observer.get() == observer) observer.onEvent(event)
    }

    private fun enqueue(frame: InboundFrame) {
        while (queuedFrames.size >= MAX_QUEUED_FRAMES) queuedFrames.poll()
        queuedFrames.add(frame)
    }

    private fun flushQueued() {
        val currentConnection = connectionId.get()
        val currentBinding = activeTurn.get()
        while (true) {
            when (val frame = queuedFrames.poll() ?: break) {
                is InboundFrame.Text -> if (
                    frame.connectionId == currentConnection &&
                    (frame.binding == null || frame.binding == currentBinding)
                ) {
                    dispatch(frame.value, frame.binding)
                }
                is InboundFrame.Binary -> if (
                    frame.connectionId == currentConnection &&
                    (
                        frame.binding == currentBinding ||
                            (frame.binding == null && audioActive.get())
                        )
                ) {
                    dispatch(frame.value, frame.binding)
                }
            }
        }
    }

    private fun reportDisconnect(socket: WebSocket, reason: String) {
        if (activeSocket.get() !== socket) return
        val oldProfile = activeProfileId.get()
        val oldConnection = connectionId.getAndSet(null)
        activeSocket.compareAndSet(socket, null)
        ready.set(false)
        activeProfileId.set(null)
        capabilities.set(AndroidHomeCapabilities())
        route.set(null)
        interruptRequested.set(false)
        audioActive.set(false)
        audioDrainPending.set(false)
        synchronized(inboundLock) {
            audioBytesRemainder = 0
        }
        audioSink.cancel()
        terminalObserved.set(false)
        queuedFrames.clear()
        pending.values.forEach(PendingRpc::fail)
        pending.clear()
        activeTurn.set(null)
        lastUnavailableReason.set(AndroidHomeUnavailableReason.TransportUnavailable)
        lastUnavailableProfileId.set(oldProfile)
        oldConnection?.let { connection ->
            val event = AndroidNormalizedEvent.Disconnected(connection, reason)
            observer.get()?.onEvent(event)
            connectionObserver.get()?.invoke(event)
        }
    }

    private fun readOpenResult(
        frame: JSONObject?,
        expectedHandle: String,
        connectionId: String,
    ): AndroidReconnectOutcome {
        if (frame == null) {
            return AndroidReconnectOutcome.Retryable(
                "The Home bridge did not acknowledge conversation.open.",
                AndroidHomeUnavailableReason.TransportTimeout,
            )
        }
        if (!isHomeEnvelope(frame)) {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge returned an invalid JSON-RPC response.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        frame.optJSONObject("error")?.let { error ->
            val code = errorCode(error)
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge could not open this conversation.",
                unavailableReason(code),
            )
        }
        val result = frame.optJSONObject("result")
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge returned no conversation state.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        if (result.optInt("schema", 0) != SCHEMA_VERSION) {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge returned an invalid conversation state.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        if (result.optString("conversation_handle") != expectedHandle) {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge returned a different conversation.",
                AndroidHomeUnavailableReason.ConversationMismatch,
            )
        }
        if (result.optString("status") != "ready") {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home conversation is unavailable.",
                unavailableReason(result.optString("reason")),
            )
        }

        val routeJson = result.optJSONObject("route")
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge omitted its approved route.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        val routeClass = routeJson.optString("class")
        val routeId = routeJson.optString("id")
        if (routeClass !in ROUTE_CLASSES || routeId.isBlank()) {
            return AndroidReconnectOutcome.Unrecoverable(
                "The Home bridge returned an invalid route.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }
        return AndroidReconnectOutcome.Connected(
            connectionId = connectionId,
            route = AndroidRoute(routeClass, routeId),
            capabilities = parseCapabilities(result.optJSONObject("capabilities")),
            unresolvedTurn = result.optJSONObject("unresolved_turn") != null ||
                result.optBoolean("unresolved_turn", false),
        )
    }

    private fun openResponseFrame(response: PendingRpc): JSONObject? = response.await(0)

    private fun parseCapabilities(json: JSONObject?): AndroidHomeCapabilities {
        if (json == null) return AndroidHomeCapabilities()
        val commands = buildSet {
            val values = json.optJSONArray("commands")
            for (index in 0 until (values?.length() ?: 0)) {
                values?.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return AndroidHomeCapabilities(
            heartbeat = json.optBoolean("heartbeat", false),
            timing = json.optString("timing").ifBlank { "absent" },
            commands = commands,
            interrupt = json.optBoolean("interrupt", false),
            audio = json.optBoolean("audio", false),
        )
    }

    private fun rpcRequest(id: String, method: String, params: JSONObject): JSONObject =
        JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

    private fun isHomeEnvelope(frame: JSONObject): Boolean =
        frame.optInt("schema", 0) == SCHEMA_VERSION &&
            frame.optString("jsonrpc") == "2.0"

    private fun bridgeUrl(route: String): String {
        val normalized = route.trim().trimEnd('/')
        val uri = URI(normalized)
        require(uri.scheme.equals("wss", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        require(uri.userInfo == null)
        require(uri.rawQuery == null && uri.rawFragment == null)
        val basePath = uri.path.orEmpty().trimEnd('/')
        val path = if (basePath.endsWith(BRIDGE_PATH)) {
            basePath
        } else {
            basePath + BRIDGE_PATH
        }
        return URI(
            "wss",
            null,
            uri.host,
            uri.port,
            path,
            null,
            null,
        ).toString()
    }

    private fun rpcId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private fun errorCode(error: JSONObject): String =
        error.optJSONObject("data")?.optString("code")?.takeIf { it.isNotBlank() }
            ?: error.optString("code").takeIf { it.isNotBlank() }
            ?: "protocol_error"

    private fun unavailableReason(code: String?): AndroidHomeUnavailableReason = when (code) {
        "authorization_unavailable" -> AndroidHomeUnavailableReason.AuthorizationUnavailable
        "unauthorized" -> AndroidHomeUnavailableReason.Unauthorized
        "route_unavailable" -> AndroidHomeUnavailableReason.TransportUnavailable
        "route_unauthorized" -> AndroidHomeUnavailableReason.Unauthorized
        "route_identity_mismatch" -> AndroidHomeUnavailableReason.InvalidBinding
        "route_timeout" -> AndroidHomeUnavailableReason.TransportTimeout
        "stale_conversation" -> AndroidHomeUnavailableReason.StaleConversation
        "reconnect_required" -> AndroidHomeUnavailableReason.ReconnectRequired
        "conversation_mismatch" -> AndroidHomeUnavailableReason.ConversationMismatch
        "hermes_timeout" -> AndroidHomeUnavailableReason.HermesTimeout
        "transport_timeout" -> AndroidHomeUnavailableReason.TransportTimeout
        "hermes_unavailable" -> AndroidHomeUnavailableReason.HermesUnavailable
        "transport_unavailable" -> AndroidHomeUnavailableReason.TransportUnavailable
        "invalid_request" -> AndroidHomeUnavailableReason.ProtocolError
        "request_rejected" -> AndroidHomeUnavailableReason.RequestRejected
        "capability_unavailable" -> AndroidHomeUnavailableReason.CapabilityUnavailable
        "protocol_error" -> AndroidHomeUnavailableReason.ProtocolError
        else -> AndroidHomeUnavailableReason.AuthorizationUnavailable
    }

    private fun classify(t: Throwable, response: Response?): AndroidReconnectOutcome {
        response?.code?.let { code ->
            if (code == 401 || code == 403) {
                return AndroidReconnectOutcome.Unrecoverable(
                    "The Home bridge rejected this Device credential.",
                    AndroidHomeUnavailableReason.Unauthorized,
                )
            }
        }
        return when (t) {
            is UnknownHostException -> AndroidReconnectOutcome.Unrecoverable(
                "The approved Home route could not be found.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
            is SSLException -> AndroidReconnectOutcome.Unrecoverable(
                "The approved Home route's certificate could not be verified.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
            is SocketTimeoutException -> AndroidReconnectOutcome.Retryable(
                "The approved Home route timed out.",
                AndroidHomeUnavailableReason.TransportTimeout,
            )
            is ConnectException,
            is SocketException,
            is EOFException,
            -> AndroidReconnectOutcome.Retryable(
                "The approved Home route is unreachable.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
            else -> AndroidReconnectOutcome.Retryable(
                "The Home bridge connection failed.",
                AndroidHomeUnavailableReason.TransportUnavailable,
            )
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val BRIDGE_PATH = "/api/v1/bridge/ws"
        const val DEFAULT_HELLO_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        const val NORMAL_CLOSURE = 1000
        const val MAX_QUEUED_FRAMES = 256
        const val MAX_CONVERSATION_HANDLE_LENGTH = 512
        val ROUTE_CLASSES = setOf("home", "tailscale", "public")
        val INTERRUPT_ACK_STATUSES = setOf(
            "accepted",
            "acknowledged",
            "ok",
            "requested",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
