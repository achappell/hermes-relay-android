package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpRelaySessionClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun a_successful_handshake_adopts_the_relay_session_identity() {
        val received = CountDownLatch(1)
        var helloFrame: String? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        helloFrame = text
                        received.countDown()
                        webSocket.send(
                            JSONObject()
                                .put("type", "hello_ack")
                                .put("protocol_version", 1)
                                .put("session_id", "relay-session-9")
                                .toString(),
                        )
                    }
                },
            ),
        )

        val outcome = client().reconnect()

        assertTrue(received.await(5, TimeUnit.SECONDS))
        assertEquals(AndroidReconnectOutcome.Connected("relay-session-9"), outcome)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer relay-token", request.getHeader("Authorization"))

        val hello = JSONObject(helloFrame!!)
        assertEquals("hello", hello.getString("type"))
        assertEquals(1, hello.getInt("protocol_version"))
        assertEquals("amanda-laptop", hello.getString("client_id"))
        assertEquals("android", hello.getString("device_id"))
        assertTrue(hello.getString("session_id").isNotBlank())
    }

    @Test
    fun a_rejected_credential_is_unrecoverable() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val outcome = client().reconnect()

        assertTrue(
            "expected Unrecoverable, got $outcome",
            outcome is AndroidReconnectOutcome.Unrecoverable,
        )
    }

    @Test
    fun a_forbidden_credential_is_unrecoverable() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        val outcome = client().reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
    }

    @Test
    fun an_unknown_host_reports_the_tailnet_rather_than_a_credential_failure() {
        val client = OkHttpRelaySessionClient(
            collection = { collectionFor("wss://relay.invalid.example/voice-session") },
            credentials = InMemoryRelayCredentialStore(mapOf(PROFILE_ID to "relay-token")),
            httpClient = OkHttpClient(),
            helloTimeoutMillis = 5_000,
        )

        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertTrue(
            "expected tailnet guidance, got ${(outcome as AndroidReconnectOutcome.Unrecoverable).reason}",
            outcome.reason.contains("Tailscale"),
        )
    }

    @Test
    fun a_protocol_mismatch_is_unrecoverable() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "hello_ack")
                                .put("protocol_version", 99)
                                .put("session_id", "relay-session-9")
                                .toString(),
                        )
                    }
                },
            ),
        )

        val outcome = client().reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertTrue((outcome as AndroidReconnectOutcome.Unrecoverable).reason.contains("99"))
    }

    @Test
    fun a_relay_error_frame_is_unrecoverable() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "error")
                                .put("message", "client_id is not allowed")
                                .toString(),
                        )
                    }
                },
            ),
        )

        val outcome = client().reconnect()

        assertEquals(
            AndroidReconnectOutcome.Unrecoverable("client_id is not allowed"),
            outcome,
        )
    }

    @Test
    fun a_refused_connection_is_retryable() {
        val port = server.port
        server.shutdown()

        val client = OkHttpRelaySessionClient(
            collection = { collectionFor("wss://127.0.0.1:$port/voice-session") },
            credentials = InMemoryRelayCredentialStore(mapOf(PROFILE_ID to "relay-token")),
            httpClient = OkHttpClient(),
            helloTimeoutMillis = 5_000,
        )

        val outcome = client.reconnect()

        assertTrue(
            "expected Retryable, got $outcome",
            outcome is AndroidReconnectOutcome.Retryable,
        )
    }

    @Test
    fun a_silent_relay_times_out_as_retryable() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}),
        )

        val outcome = client(helloTimeoutMillis = 400).reconnect()

        assertTrue(
            "expected Retryable, got $outcome",
            outcome is AndroidReconnectOutcome.Retryable,
        )
    }

    @Test
    fun a_disconnected_socket_is_retryable() {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )

        val outcome = client(helloTimeoutMillis = 3_000).reconnect()

        assertTrue(
            "expected Retryable, got $outcome",
            outcome is AndroidReconnectOutcome.Retryable,
        )
    }

    @Test
    fun an_unselected_profile_is_unrecoverable_without_opening_a_socket() {
        val client = OkHttpRelaySessionClient(
            collection = { RelayProfileCollection() },
            credentials = InMemoryRelayCredentialStore(),
        )

        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_missing_credential_is_unrecoverable_without_opening_a_socket() {
        val client = OkHttpRelaySessionClient(
            collection = { collectionFor(server.url("/voice-session").toString()) },
            credentials = InMemoryRelayCredentialStore(),
        )

        val outcome = client.reconnect()

        assertTrue(outcome is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_snapshot_is_unconfigured_until_a_profile_and_credential_exist() {
        val noProfile = OkHttpRelaySessionClient(
            collection = { RelayProfileCollection() },
            credentials = InMemoryRelayCredentialStore(),
        )
        assertEquals(
            AndroidAuthorizationState.NotConfigured,
            noProfile.snapshot().authorizationState,
        )

        val noToken = OkHttpRelaySessionClient(
            collection = { collectionFor("wss://relay.example/voice-session") },
            credentials = InMemoryRelayCredentialStore(),
        )
        assertEquals(
            AndroidAuthorizationState.NotConfigured,
            noToken.snapshot().authorizationState,
        )

        val configured = OkHttpRelaySessionClient(
            collection = { collectionFor("wss://relay.example/voice-session") },
            credentials = InMemoryRelayCredentialStore(mapOf(PROFILE_ID to "relay-token")),
        )
        val snapshot = configured.snapshot()
        assertEquals(AndroidAuthorizationState.Verified, snapshot.authorizationState)
        assertEquals("amanda-laptop", snapshot.selectedProfile?.id)
    }

    @Test
    fun a_typed_turn_is_submitted_and_its_response_is_projected_end_to_end() {
        val turnFrame = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val relay = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        val turnReceived = CountDownLatch(1)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = JSONObject(text)
                        when (frame.getString("type")) {
                            "hello" -> {
                                relay.set(webSocket)
                                webSocket.send(
                                    JSONObject()
                                        .put("type", "hello_ack")
                                        .put("protocol_version", 1)
                                        .put("session_id", "relay-session-1")
                                        .toString(),
                                )
                            }

                            "turn" -> {
                                turnFrame.set(text)
                                turnReceived.countDown()
                            }
                        }
                    }
                },
            ),
        )

        val client = client()
        assertEquals(
            AndroidReconnectOutcome.Connected("relay-session-1"),
            client.reconnect(),
        )

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile("amanda-laptop", "Amanda"),
                AndroidTurnInput.Typed("What is the weather?"),
            ),
        )
        assertTrue(result is AndroidInitiationResult.Accepted)
        val binding = (result as AndroidInitiationResult.Accepted).binding
        assertEquals("relay-session-1", binding.sessionId)

        assertTrue(turnReceived.await(5, TimeUnit.SECONDS))
        val sent = JSONObject(turnFrame.get()!!)
        assertEquals("turn", sent.getString("type"))
        assertEquals("What is the weather?", sent.getString("text"))
        assertEquals("relay-session-1", sent.getString("session_id"))
        assertEquals(binding.turnId, sent.getString("turn_id"))

        val events = java.util.Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())
        val completed = CountDownLatch(1)
        val observation = client.observeTurn(binding) { event ->
            events += event
            if (event is AndroidNormalizedEvent.TurnCompleted) completed.countDown()
        }

        val socket = relay.get()!!
        fun relaySend(vararg pairs: Pair<String, Any>) {
            socket.send(
                JSONObject(
                    mapOf(
                        "session_id" to binding.sessionId,
                        "turn_id" to binding.turnId,
                    ) + pairs,
                ).toString(),
            )
        }

        relaySend("type" to "status", "status" to "thinking")
        relaySend("type" to "text_delta", "text" to "Rain ")
        relaySend("type" to "text_delta", "text" to "later")
        relaySend("type" to "audio_start")
        relaySend("type" to "audio_end")
        relaySend("type" to "text_final", "text" to "Rain later")
        relaySend("type" to "turn_end")

        assertTrue("turn never completed", completed.await(5, TimeUnit.SECONDS))
        observation.cancel()

        // Project the stream through the A-2 reducer exactly as the UI does.
        var state = AndroidTurnState.awaitingEvents(binding)
        events.forEach { state = AndroidTurnStateReducer.reduce(state, it) }

        assertEquals("Rain later", state.responseText)
        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
    }

    @Test
    fun a_replacement_session_ignores_late_frames_from_the_superseded_socket() {
        val sockets = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())
        val connected = CountDownLatch(2)

        repeat(2) { index ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (JSONObject(text).getString("type") != "hello") return
                            sockets += webSocket
                            webSocket.send(
                                JSONObject()
                                    .put("type", "hello_ack")
                                    .put("protocol_version", 1)
                                    .put("session_id", "relay-session-${index + 1}")
                                    .toString(),
                            )
                            connected.countDown()
                        }
                    },
                ),
            )
        }

        val client = client()
        assertEquals(
            AndroidReconnectOutcome.Connected("relay-session-1"),
            client.reconnect(),
        )
        // A fresh connect must negotiate a genuinely different Session.
        assertEquals(
            AndroidReconnectOutcome.Connected("relay-session-2"),
            client.reconnect(),
        )
        assertTrue(connected.await(5, TimeUnit.SECONDS))

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile("amanda-laptop", "Amanda"),
                AndroidTurnInput.Typed("after recovery"),
            ),
        )
        val binding = (result as AndroidInitiationResult.Accepted).binding
        assertEquals("relay-session-2", binding.sessionId)

        var state = AndroidTurnState.awaitingEvents(binding)
        val observation = client.observeTurn(binding) { event ->
            state = AndroidTurnStateReducer.reduce(state, event)
        }

        // The superseded socket speaks after it was replaced.
        sockets.first().send(
            JSONObject()
                .put("type", "text_final")
                .put("text", "answer from the dead session")
                .put("session_id", "relay-session-1")
                .put("turn_id", binding.turnId)
                .toString(),
        )
        Thread.sleep(300)
        observation.cancel()

        assertEquals("", state.responseText)
        assertEquals(AndroidTurnPhase.Idle, state.phase)
    }

    @Test
    fun a_turn_is_refused_before_a_session_exists() {
        val client = client()

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile("amanda-laptop", "Amanda"),
                AndroidTurnInput.Typed("too early"),
            ),
        )

        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            result,
        )
    }

    private fun client(helloTimeoutMillis: Long = 5_000): OkHttpRelaySessionClient =
        OkHttpRelaySessionClient(
            collection = { collectionFor(server.url("/voice-session").toString()) },
            credentials = InMemoryRelayCredentialStore(mapOf(PROFILE_ID to "relay-token")),
            httpClient = OkHttpClient(),
            helloTimeoutMillis = helloTimeoutMillis,
        )

    private fun collectionFor(endpoint: String) = RelayProfileCollection(
        profiles = listOf(
            RelayProfile(
                id = PROFILE_ID,
                endpoint = endpoint,
                clientId = "amanda-laptop",
                deviceId = "android",
                displayName = "Amanda",
            ),
        ),
        selectedId = PROFILE_ID,
    )

    private companion object {
        const val PROFILE_ID = "profile-1"
    }
}
