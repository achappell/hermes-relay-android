package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OkHttpRelaySessionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates

    @Before
    fun setUp() {
        server = MockWebServer()
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
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
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun a_home_handshake_uses_the_versioned_path_and_device_authentication() {
        val openSeen = CountDownLatch(1)
        val openFrame = AtomicReference<String?>(null)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = JSONObject(text)
                        if (frame.optString("method") != "conversation.open") return
                        openFrame.set(text)
                        openSeen.countDown()
                        webSocket.send(readyResponse(frame.getString("id")))
                    }
                },
            ),
        )

        val client = client()
        val outcome = client.reconnect()

        assertTrue(openSeen.await(5, TimeUnit.SECONDS))
        assertTrue(outcome is AndroidReconnectOutcome.Connected)
        val connected = outcome as AndroidReconnectOutcome.Connected
        assertTrue(connected.connectionId.startsWith("bridge-"))
        assertEquals(AndroidRoute("home", "route-home"), connected.route)
        assertTrue(connected.capabilities.interrupt)
        assertTrue(connected.capabilities.audio)

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/api/v1/bridge/ws", request!!.path)
        assertEquals("Device $VALID_HOME_CREDENTIAL", request.getHeader("Authorization"))

        val open = JSONObject(openFrame.get()!!)
        assertEquals(1, open.getInt("schema"))
        assertEquals("2.0", open.getString("jsonrpc"))
        assertEquals("conversation.open", open.getString("method"))
        assertEquals(
            CONVERSATION_HANDLE,
            open.getJSONObject("params").getString("conversation_handle"),
        )
        assertFalse(open.toString().contains(PROFILE_ID))
        assertFalse(open.toString().contains(VALID_HOME_CREDENTIAL))

        client.close()
    }

    @Test
    fun a_profile_is_not_ready_from_credential_presence_alone() {
        val noProfile = OkHttpRelaySessionClient(
            collection = { RelayProfileCollection() },
            credentials = InMemoryRelayCredentialStore(),
        )
        assertEquals(AndroidAuthorizationState.NotConfigured, noProfile.snapshot().authorizationState)

        val noBinding = OkHttpRelaySessionClient(
            collection = { collectionFor(homeBinding = null) },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(PROFILE_ID to VALID_HOME_CREDENTIAL),
            ),
        )
        assertEquals(AndroidAuthorizationState.NotConfigured, noBinding.snapshot().authorizationState)
        assertEquals(AndroidHomeUnavailableReason.MissingBinding, noBinding.snapshot().unavailableReason)

        val noCredential = OkHttpRelaySessionClient(
            collection = { collectionFor() },
            credentials = InMemoryRelayCredentialStore(),
        )
        assertEquals(AndroidAuthorizationState.Unavailable, noCredential.snapshot().authorizationState)
        assertEquals(AndroidHomeUnavailableReason.InvalidCredential, noCredential.snapshot().unavailableReason)

        val configured = client()
        assertEquals(AndroidAuthorizationState.Verifying, configured.snapshot().authorizationState)
        assertEquals(PROFILE_ID, configured.snapshot().selectedProfile?.id)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun malformed_home_credentials_fail_closed_without_opening_a_socket() {
        val client = OkHttpRelaySessionClient(
            collection = { collectionFor() },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(PROFILE_ID to "not-a-device-credential"),
            ),
        )

        val outcome = client.reconnect()

        assertEquals(
            AndroidReconnectOutcome.Unrecoverable(
                "The Home Device credential cannot be read.",
                AndroidHomeUnavailableReason.InvalidCredential,
            ),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun an_insecure_home_binding_fails_before_opening_a_socket() {
        val client = client(
            homeBinding = RelayHomeBinding(
                approvedRoute = "http://home.example",
                conversationHandle = CONVERSATION_HANDLE,
            ),
        )

        val outcome = client.reconnect()

        assertEquals(
            AndroidReconnectOutcome.Unrecoverable(
                "The approved Home bridge route is malformed or not secure.",
                AndroidHomeUnavailableReason.InvalidBinding,
            ),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun an_http_401_is_an_unavailable_home_binding_not_a_fork_fallback() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val client = client()
        val outcome = client.reconnect()

        assertTrue("unexpected Home outcome: $outcome", outcome is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(
            AndroidHomeUnavailableReason.Unauthorized,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        assertEquals(AndroidAuthorizationState.Unavailable, client.snapshot().authorizationState)
        assertEquals(AndroidHomeUnavailableReason.Unauthorized, client.snapshot().unavailableReason)
        assertEquals("/api/v1/bridge/ws", server.takeRequest()!!.path)
        client.close()
    }

    @Test
    fun home_404_is_retryable_without_an_active_socket() {
        server.enqueue(MockResponse().setResponseCode(404))

        val client = client()
        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Retryable)
        assertEquals(
            AndroidHomeUnavailableReason.TransportUnavailable,
            (outcome as AndroidReconnectOutcome.Retryable).reasonCode,
        )
        assertFalse(client.hasActiveTurn())
        assertEquals(null, client.snapshot().route)
        client.close()
    }

    @Test
    fun readiness_rejects_malformed_home_state_without_a_turn() {
        val malformed = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("result").remove("unresolved_turn") },
            { it.getJSONObject("result").put("unresolved_turn", "true") },
            { it.getJSONObject("result").remove("route") },
            { it.getJSONObject("result").getJSONObject("route").put("class", "private") },
            { it.getJSONObject("result").remove("capabilities") },
            { it.getJSONObject("result").getJSONObject("capabilities").put("timing", "wall") },
            { it.getJSONObject("result").getJSONObject("capabilities").put("commands", JSONArray().put("shell")) },
        )

        malformed.forEach { mutate ->
            val client = client()
            val frame = JSONObject(readyResponse("open-1"))
            mutate(frame)

            val outcome = client.readOpenResult(frame, "open-1", CONVERSATION_HANDLE, "bridge-1")

            assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
            val reason = (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode
            assertTrue(
                reason == AndroidHomeUnavailableReason.ProtocolError ||
                    reason == AndroidHomeUnavailableReason.CapabilityShapeInvalid,
            )
            assertFalse(client.hasActiveTurn())
            assertEquals(null, client.snapshot().route)
            client.close()
        }

        val unresolvedClient = client()
        val unresolvedFrame = JSONObject(readyResponse("open-2"))
        unresolvedFrame.getJSONObject("result").put("unresolved_turn", true)
        val unresolvedOutcome = unresolvedClient.readOpenResult(
            unresolvedFrame,
            "open-2",
            CONVERSATION_HANDLE,
            "bridge-2",
        )
        assertTrue(unresolvedOutcome is AndroidReconnectOutcome.Connected)
        assertTrue((unresolvedOutcome as AndroidReconnectOutcome.Connected).unresolvedTurn)
        val strict = LiveHomeReadiness.assertNewTurnReadiness(unresolvedOutcome)
        assertEquals(AndroidHomeUnavailableReason.UnresolvedTurn, strict.reason)
        assertEquals(0, unresolvedClient.snapshotRequestTelemetry().promptSubmitCount)
        assertEquals(0, unresolvedClient.snapshotRequestTelemetry().interruptRequestCount)
        unresolvedClient.close()
        assertTrue(unresolvedFrame.getJSONObject("result").getBoolean("unresolved_turn"))

        val resumedClient = client()
        val resumedFrame = JSONObject(readyResponse("open-3"))
        resumedFrame.getJSONObject("result").put(
            "unresolved_turn",
            JSONObject()
                .put("schema", 1)
                .put("conversation_handle", CONVERSATION_HANDLE)
                .put("turn_id", "home-turn-resumed")
                .put("status", "submitted"),
        )
        val resumedOutcome = resumedClient.readOpenResult(
            resumedFrame,
            "open-3",
            CONVERSATION_HANDLE,
            "bridge-3",
            PROFILE_ID,
        ) as AndroidReconnectOutcome.Connected
        assertTrue(resumedOutcome.unresolvedTurn)
        assertEquals("home-turn-resumed", resumedOutcome.unresolvedTurnId)
        assertEquals(
            AndroidTurnBinding(
                PROFILE_ID,
                CONVERSATION_HANDLE,
                "bridge-3",
                "home-turn-resumed",
            ),
            resumedOutcome.unresolvedTurnBinding,
        )
        assertEquals(
            0,
            resumedClient.snapshotRequestTelemetry().promptSubmitCount,
        )
        resumedClient.close()
    }

    @Test
    fun a_stale_conversation_is_reported_without_submitting_a_turn() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        webSocket.send(
                            rpcResult(
                                request.getString("id"),
                                JSONObject()
                                    .put("schema", 1)
                                    .put("status", "unavailable")
                                    .put("conversation_handle", CONVERSATION_HANDLE)
                                    .put("reason", "stale_conversation"),
                            ),
                        )
                    }
                },
            ),
        )

        val client = client()
        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(
            AndroidHomeUnavailableReason.StaleConversation,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        assertEquals(1, server.requestCount)
        client.close()
    }

    @Test
    fun reconnect_required_from_open_selects_the_reconnect_method_next() {
        val methods = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        methods += request.getString("method")
                        webSocket.send(
                            rpcResult(
                                request.getString("id"),
                                JSONObject()
                                    .put("schema", 1)
                                    .put("status", "unavailable")
                                    .put("conversation_handle", CONVERSATION_HANDLE)
                                    .put("reason", "reconnect_required"),
                            ),
                        )
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        methods += request.getString("method")
                        webSocket.send(readyResponse(request.getString("id"), audio = false))
                    }
                },
            ),
        )

        val client = client()
        val first = client.reconnect()
        assertEquals(AndroidHomeUnavailableReason.ReconnectRequired, (first as AndroidReconnectOutcome.Unrecoverable).reasonCode)

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        assertEquals(listOf("conversation.open", "conversation.reconnect"), methods)
        client.close()
    }

    @Test
    fun an_invalid_json_rpc_readiness_response_fails_closed() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        webSocket.send(
                            JSONObject()
                                .put("schema", 2)
                                .put("jsonrpc", "2.0")
                                .put("id", request.getString("id"))
                                .put("result", JSONObject())
                                .toString(),
                        )
                    }
                },
            ),
        )

        val client = client()
        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(
            AndroidHomeUnavailableReason.ProtocolError,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    @Test
    fun a_silent_home_bridge_times_out_as_retryable() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )

        val client = client(helloTimeoutMillis = 250)
        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Retryable)
        assertEquals(
            AndroidHomeUnavailableReason.TransportTimeout,
            (outcome as AndroidReconnectOutcome.Retryable).reasonCode,
        )
        client.close()
    }

    @Test
    fun a_typed_prompt_keeps_home_opaque_and_projects_text_and_pcm() {
        val events = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())
        val completed = CountDownLatch(1)
        val promptSeen = CountDownLatch(1)
        val promptFrame = AtomicReference<String?>(null)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id")))
                            "prompt.submit" -> {
                                promptFrame.set(text)
                                promptSeen.countDown()
                                val turnId = "home-turn-1"
                                webSocket.send(
                                    rpcResult(
                                        request.getString("id"),
                                        JSONObject()
                                            .put("schema", 1)
                                            .put("conversation_handle", CONVERSATION_HANDLE)
                                            .put("turn_id", turnId)
                                            .put("status", "submitted"),
                                    ),
                                )
                                webSocket.send(eventFrame(turnId, "message.start", JSONObject()))
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.delta",
                                        JSONObject().put("rendered", "Rain"),
                                    ),
                                )
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.delta",
                                        JSONObject().put("rendered", "Rain later"),
                                    ),
                                )
                                webSocket.send(audioStartFrame(turnId))
                                webSocket.send(ByteString.of(*byteArrayOf(1, 0, 2, 0)))
                                webSocket.send(audioEndFrame(turnId))
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.complete",
                                        JSONObject()
                                            .put("rendered", "Rain later")
                                            .put("status", "complete"),
                                    ),
                                )
                            }
                        }
                    }
                },
            ),
        )

        val sink = RecordingAudioSink()
        val client = client(audioSink = sink)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda", "android"),
                AndroidTurnInput.Typed("What is the weather?"),
            ),
        )
        assertTrue(result is AndroidInitiationResult.Accepted)
        val binding = (result as AndroidInitiationResult.Accepted).binding
        assertEquals(CONVERSATION_HANDLE, binding.conversationHandle)
        assertEquals("home-turn-1", binding.turnId)
        assertTrue(promptSeen.await(5, TimeUnit.SECONDS))

        val observation = client.observeTurn(binding) { event ->
            events += event
            if (event is AndroidNormalizedEvent.TurnCompleted) completed.countDown()
        }
        assertTrue("Home never completed the turn", completed.await(5, TimeUnit.SECONDS))
        observation.cancel()

        val prompt = JSONObject(promptFrame.get()!!)
        assertEquals(1, prompt.getInt("schema"))
        assertEquals("prompt.submit", prompt.getString("method"))
        assertEquals(
            setOf("conversation_handle", "text"),
            prompt.getJSONObject("params").keys().asSequence().toSet(),
        )
        assertFalse(prompt.toString().contains(PROFILE_ID))
        assertFalse(prompt.toString().contains(VALID_HOME_CREDENTIAL))

        assertTrue(events.contains(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain")))
        assertTrue(events.contains(AndroidNormalizedEvent.ResponseTextDelta(binding, " later")))
        assertTrue(events.contains(AndroidNormalizedEvent.AudioStarted(binding, RELAY_FORMAT)))
        assertEquals("events=$events", 4, sink.bytesWritten)
        assertEquals("events=$events", 1, sink.chunks)

        var state = AndroidTurnState.awaitingEvents(binding)
        events.forEach { state = AndroidTurnStateReducer.reduce(state, it) }
        assertEquals("Rain later", state.responseText)
        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
        client.close()
    }

    @Test
    fun events_received_before_prompt_ack_are_delivered_after_binding_is_known() {
        val events = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())
        val completed = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" ->
                                webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> {
                                val turnId = "home-turn-early-event"
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.delta",
                                        JSONObject().put("rendered", "Early answer"),
                                    ),
                                )
                                webSocket.send(
                                    rpcResult(
                                        request.getString("id"),
                                        JSONObject()
                                            .put("schema", 1)
                                            .put("conversation_handle", CONVERSATION_HANDLE)
                                            .put("turn_id", turnId)
                                            .put("status", "submitted"),
                                    ),
                                )
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.complete",
                                        JSONObject()
                                            .put("rendered", "Early answer")
                                            .put("status", "complete"),
                                    ),
                                )
                            }
                        }
                    }
                },
            ),
        )

        val client = client()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda"),
                AndroidTurnInput.Typed("send it"),
            ),
        ) as AndroidInitiationResult.Accepted
        val observation = client.observeTurn(result.binding) { event ->
            events += event
            if (event is AndroidNormalizedEvent.TurnCompleted) completed.countDown()
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(
            events.contains(
                AndroidNormalizedEvent.ResponseTextDelta(result.binding, "Early answer"),
            ),
        )
        observation.cancel()
        client.close()
    }

    @Test
    fun audio_fallback_keeps_text_visible_without_claiming_speaking() {
        val failed = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id"), audio = true))
                            "prompt.submit" -> {
                                val turnId = "home-turn-audio-fallback"
                                webSocket.send(
                                    rpcResult(
                                        request.getString("id"),
                                        JSONObject()
                                            .put("schema", 1)
                                            .put("conversation_handle", CONVERSATION_HANDLE)
                                            .put("turn_id", turnId)
                                            .put("status", "submitted"),
                                    ),
                                )
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.delta",
                                        JSONObject().put("text", "Text survives"),
                                    ),
                                )
                                webSocket.send(audioFallbackFrame(turnId, "audio_unavailable"))
                                webSocket.send(
                                    eventFrame(
                                        turnId,
                                        "message.complete",
                                        JSONObject()
                                            .put("rendered", "Text survives")
                                            .put("status", "complete"),
                                    ),
                                )
                            }
                        }
                    }
                },
            ),
        )

        val client = client()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda"),
                AndroidTurnInput.Typed("say it"),
            ),
        ) as AndroidInitiationResult.Accepted
        val state = AtomicReference(AndroidTurnState.awaitingEvents(result.binding))
        val observation = client.observeTurn(result.binding) { event ->
            events += event
            state.set(AndroidTurnStateReducer.reduce(state.get(), event))
            if (event is AndroidNormalizedEvent.AudioFailed) failed.countDown()
            if (event is AndroidNormalizedEvent.TurnCompleted) completed.countDown()
        }

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        observation.cancel()
        assertEquals("Text survives", state.get().responseText)
        assertEquals(AndroidTurnPhase.Unavailable, state.get().phase)
        assertEquals(AndroidAudioDelivery.Unavailable, state.get().audio)
        assertFalse(events.any { it is AndroidNormalizedEvent.AudioStarted })
        client.close()
    }

    @Test
    fun interrupt_acknowledgement_does_not_settle_the_turn() {
        val socket = AtomicReference<WebSocket?>(null)
        val interruptSeen = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val interruptFrame = AtomicReference<String?>(null)
        val events = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket.set(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id"), interrupt = true, audio = false))
                            "prompt.submit" -> webSocket.send(
                                rpcResult(
                                    request.getString("id"),
                                    JSONObject()
                                        .put("schema", 1)
                                        .put("conversation_handle", CONVERSATION_HANDLE)
                                        .put("turn_id", "home-turn-interrupt")
                                        .put("status", "submitted"),
                                ),
                            )
                            "session.interrupt" -> {
                                interruptFrame.set(text)
                                interruptSeen.countDown()
                                webSocket.send(
                                    rpcResult(
                                        request.getString("id"),
                                        JSONObject().put("schema", 1).put("status", "accepted"),
                                    ),
                                )
                            }
                        }
                    }
                },
            ),
        )

        val client = client()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val binding = (
            client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(PROFILE_ID, "Amanda"),
                    AndroidTurnInput.Typed("stop me"),
                ),
            ) as AndroidInitiationResult.Accepted
            ).binding
        val state = AtomicReference(AndroidTurnState.awaitingEvents(binding))
        val observation = client.observeTurn(binding) { event ->
            events += event
            state.set(AndroidTurnStateReducer.reduce(state.get(), event))
            if (event is AndroidNormalizedEvent.TurnInterrupted) interrupted.countDown()
        }

        assertTrue(client.interruptTurn(binding))
        assertTrue(interruptSeen.await(5, TimeUnit.SECONDS))
        assertFalse("an interrupt ack is not a terminal event", state.get().isTerminal)
        val sent = JSONObject(interruptFrame.get()!!)
        assertEquals(
            setOf("conversation_handle", "turn_id"),
            sent.getJSONObject("params").keys().asSequence().toSet(),
        )
        assertFalse(sent.toString().contains(PROFILE_ID))
        assertFalse(sent.toString().contains(VALID_HOME_CREDENTIAL))

        socket.get()!!.send(
            eventFrame(
                binding.turnId,
                "session.interrupted",
                JSONObject().put("reason", "user interrupted"),
            ),
        )
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        assertEquals(AndroidTurnPhase.Interrupted, state.get().phase)
        observation.cancel()
        client.close()
    }

    @Test
    fun an_interrupt_is_refused_when_home_does_not_advertise_it() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        if (request.optString("method") == "conversation.open") {
                            webSocket.send(readyResponse(request.getString("id"), interrupt = false, audio = false))
                        }
                    }
                },
            ),
        )

        val client = client()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        assertFalse(client.supportsInterrupt())
        assertFalse(
            client.interruptTurn(
                AndroidTurnBinding(PROFILE_ID, CONVERSATION_HANDLE, "bridge-not-active", "turn-1"),
            ),
        )
        client.close()
    }

    @Test
    fun a_prompt_timeout_is_uncertain_and_not_a_known_rejection() {
        val promptSeen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> promptSeen.countDown()
                        }
                    }
                },
            ),
        )

        val client = client(requestTimeoutMillis = 250)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val request = AndroidTurnRequest(
            AndroidProfile(PROFILE_ID, "Amanda", "android"),
            AndroidTurnInput.Typed("may have arrived"),
        )
        val result = client.beginTurn(request)

        assertTrue(promptSeen.await(5, TimeUnit.SECONDS))
        assertTrue(result is AndroidInitiationResult.Uncertain)
        assertEquals(request, (result as AndroidInitiationResult.Uncertain).request)
        assertEquals(AndroidHomeUnavailableReason.TransportTimeout, result.reason)
        client.close()
    }

    @Test
    fun a_second_prompt_is_rejected_while_first_acknowledgement_is_pending() {
        val promptCount = AtomicInteger(0)
        val firstPromptSeen = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val firstResult = AtomicReference<AndroidInitiationResult?>(null)
        val firstDone = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" ->
                                webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> {
                                val count = promptCount.incrementAndGet()
                                val requestId = request.getString("id")
                                if (count == 1) {
                                    firstPromptSeen.countDown()
                                    Thread {
                                        if (releaseFirstResponse.await(5, TimeUnit.SECONDS)) {
                                            webSocket.send(
                                                rpcResult(
                                                    requestId,
                                                    JSONObject()
                                                        .put("schema", 1)
                                                        .put("conversation_handle", CONVERSATION_HANDLE)
                                                        .put("turn_id", "home-turn-first")
                                                        .put("status", "submitted"),
                                                ),
                                            )
                                        }
                                    }.apply { isDaemon = true }.start()
                                } else {
                                    webSocket.send(
                                        rpcResult(
                                            requestId,
                                            JSONObject()
                                                .put("schema", 1)
                                                .put("conversation_handle", CONVERSATION_HANDLE)
                                                .put("turn_id", "home-turn-second")
                                                .put("status", "submitted"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                },
            ),
        )

        val client = client(requestTimeoutMillis = 2_000)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val firstRequest = AndroidTurnRequest(
            AndroidProfile(PROFILE_ID, "Amanda"),
            AndroidTurnInput.Typed("first prompt"),
        )
        val firstThread = Thread {
            try {
                firstResult.set(client.beginTurn(firstRequest))
            } finally {
                firstDone.countDown()
            }
        }.apply { isDaemon = true }
        firstThread.start()

        assertTrue(firstPromptSeen.await(5, TimeUnit.SECONDS))
        val second = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda"),
                AndroidTurnInput.Typed("second prompt"),
            ),
        )
        releaseFirstResponse.countDown()

        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        assertTrue(firstResult.get() is AndroidInitiationResult.Accepted)
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            second,
        )
        assertEquals(1, promptCount.get())
        client.close()
    }

    @Test
    fun an_uncertain_prompt_blocks_new_turns_until_explicit_resend_is_prepared() {
        val promptCount = AtomicInteger(0)
        val firstPromptSeen = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" ->
                                webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> {
                                if (promptCount.incrementAndGet() == 1) {
                                    firstPromptSeen.countDown()
                                } else {
                                    webSocket.send(
                                        rpcResult(
                                            request.getString("id"),
                                            JSONObject()
                                                .put("schema", 1)
                                                .put("conversation_handle", CONVERSATION_HANDLE)
                                                .put("turn_id", "home-turn-explicit")
                                                .put("status", "submitted"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                },
            ),
        )

        val client = client(requestTimeoutMillis = 250)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val request = AndroidTurnRequest(
            AndroidProfile(PROFILE_ID, "Amanda"),
            AndroidTurnInput.Typed("may have arrived"),
        )
        val uncertain = client.beginTurn(request)

        assertTrue(firstPromptSeen.await(5, TimeUnit.SECONDS))
        assertTrue(uncertain is AndroidInitiationResult.Uncertain)
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(PROFILE_ID, "Amanda"),
                    AndroidTurnInput.Typed("do not send yet"),
                ),
            ),
        )
        assertEquals(1, promptCount.get())

        client.prepareForExplicitResend()
        assertTrue(client.beginTurn(request) is AndroidInitiationResult.Accepted)
        assertEquals(2, promptCount.get())
        client.close()
    }

    @Test
    fun an_explicit_home_request_rejection_is_known_non_delivery() {
        val promptSeen = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> {
                                promptSeen.countDown()
                                webSocket.send(
                                    JSONObject()
                                        .put("schema", 1)
                                        .put("jsonrpc", "2.0")
                                        .put("id", request.getString("id"))
                                        .put(
                                            "error",
                                            JSONObject()
                                                .put("code", -32000)
                                                .put("message", "Prompt rejected")
                                                .put("data", JSONObject().put("code", "request_rejected")),
                                        )
                                        .toString(),
                                )
                            }
                        }
                    }
                },
            ),
        )

        val client = client(requestTimeoutMillis = 2_000)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda"),
                AndroidTurnInput.Typed("reject this"),
            ),
        )

        assertTrue(promptSeen.await(5, TimeUnit.SECONDS))
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected),
            result,
        )
        client.close()
    }

    @Test
    fun reconnect_uses_conversation_reconnect_without_prompt_replay() {
        val firstPromptSeen = CountDownLatch(1)
        val secondMethods = Collections.synchronizedList(mutableListOf<String>())

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        when (request.optString("method")) {
                            "conversation.open", "conversation.reconnect" -> webSocket.send(readyResponse(request.getString("id"), audio = false))
                            "prompt.submit" -> {
                                firstPromptSeen.countDown()
                                webSocket.close(1000, "delivery uncertain")
                            }
                        }
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = JSONObject(text)
                        secondMethods += request.optString("method")
                        if (request.optString("method") == "conversation.reconnect") {
                            webSocket.send(readyResponse(request.getString("id"), audio = false))
                        }
                    }
                },
            ),
        )

        val client = client(requestTimeoutMillis = 2_000)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val request = AndroidTurnRequest(
            AndroidProfile(PROFILE_ID, "Amanda"),
            AndroidTurnInput.Typed("send once"),
        )
        val result = client.beginTurn(request)
        assertTrue(firstPromptSeen.await(5, TimeUnit.SECONDS))
        assertTrue(result is AndroidInitiationResult.Uncertain)

        val recovered = client.reconnect()
        assertTrue(recovered is AndroidReconnectOutcome.Connected)
        assertEquals(listOf("conversation.reconnect"), secondMethods)
        client.close()
    }

    @Test
    fun a_turn_is_refused_before_home_readiness() {
        val client = client()

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(PROFILE_ID, "Amanda"),
                AndroidTurnInput.Typed("too early"),
            ),
        )

        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            result,
        )
        client.close()
    }

    private fun client(
        homeBinding: RelayHomeBinding? = RelayHomeBinding(
            approvedRoute = bridgeRoute(),
            conversationHandle = CONVERSATION_HANDLE,
        ),
        credential: String = VALID_HOME_CREDENTIAL,
        helloTimeoutMillis: Long = 5_000,
        requestTimeoutMillis: Long = 5_000,
        audioSink: AndroidAudioSink = RecordingAudioSink(),
    ): OkHttpRelaySessionClient = OkHttpRelaySessionClient(
        collection = { collectionFor(homeBinding = homeBinding) },
        credentials = InMemoryRelayCredentialStore(
            homeCredentials = mapOf(PROFILE_ID to credential),
        ),
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build(),
        helloTimeoutMillis = helloTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
        audioSink = audioSink,
    )

    private fun collectionFor(homeBinding: RelayHomeBinding? = RelayHomeBinding(
        approvedRoute = bridgeRoute(),
        conversationHandle = CONVERSATION_HANDLE,
    )) = RelayProfileCollection(
        profiles = listOf(
            RelayProfile(
                id = PROFILE_ID,
                endpoint = "wss://legacy.invalid/voice-session",
                clientId = "android-client",
                deviceId = "android",
                displayName = "Amanda",
                homeBinding = homeBinding,
            ),
        ),
        selectedId = PROFILE_ID,
    )

    private fun bridgeRoute(): String = server.url("/").toString()
        .replaceFirst("https://", "wss://")

    private fun readyResponse(
        id: String,
        interrupt: Boolean = true,
        audio: Boolean = true,
    ): String = rpcResult(
        id,
        JSONObject()
            .put("schema", 1)
            .put("status", "ready")
            .put("unresolved_turn", false)
            .put("conversation_handle", CONVERSATION_HANDLE)
            .put("route", JSONObject().put("class", "home").put("id", "route-home"))
            .put(
                "capabilities",
                JSONObject()
                    .put("heartbeat", true)
                    .put("timing", "absent")
                    .put("commands", JSONArray())
                    .put("interrupt", interrupt)
                    .put("audio", audio),
            ),
    )

    private fun rpcResult(id: String, result: JSONObject): String = JSONObject()
        .put("schema", 1)
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)
        .toString()

    private fun eventFrame(turnId: String, type: String, payload: JSONObject): String = JSONObject()
        .put("schema", 1)
        .put("jsonrpc", "2.0")
        .put("method", "event")
        .put(
            "params",
            JSONObject()
                .put("schema", 1)
                .put("conversation_handle", CONVERSATION_HANDLE)
                .put("turn_id", turnId)
                .put("event", JSONObject().put("type", type).put("payload", payload)),
        )
        .toString()

    private fun audioStartFrame(turnId: String): String = audioFrame(
        turnId = turnId,
        kind = "start",
        extra = JSONObject()
            .put("sample_rate", RELAY_FORMAT.sampleRate)
            .put("channels", RELAY_FORMAT.channels)
            .put("sample_width", RELAY_FORMAT.sampleWidth)
            .put("byte_order", "little"),
    )

    private fun audioEndFrame(turnId: String): String = audioFrame(turnId, "end")

    private fun audioFallbackFrame(turnId: String, reason: String): String =
        audioFrame(turnId, "fallback", JSONObject().put("reason", reason))

    private fun audioFrame(
        turnId: String,
        kind: String,
        extra: JSONObject = JSONObject(),
    ): String {
        val frame = JSONObject().put("kind", kind)
        extra.keys().forEach { key -> frame.put(key, extra.get(key)) }
        val params = JSONObject()
            .put("schema", 1)
            .put("conversation_handle", CONVERSATION_HANDLE)
            .put("turn_id", turnId)
            .put("frame", frame)
        return JSONObject()
            .put("schema", 1)
            .put("jsonrpc", "2.0")
            .put("method", "audio.frame")
            .put("params", params)
            .toString()
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        const val CONVERSATION_HANDLE = "opaque-conversation-1"
        const val VALID_HOME_CREDENTIAL = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val RELAY_FORMAT = AndroidAudioFormat(24_000, 1, 2, "pcm_s16le")
    }
}
