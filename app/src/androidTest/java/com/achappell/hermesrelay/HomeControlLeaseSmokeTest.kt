package com.achappell.hermesrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-runtime fixture evidence, not live Home evidence. The production client
 * talks only to a TLS loopback server; no Activity, Application runtime, persisted
 * profile, owner configuration, or owner credentials are read or changed.
 */
@RunWith(AndroidJUnit4::class)
class HomeControlLeaseSmokeTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private var client: OkHttpRelaySessionClient? = null
    private val peer = AtomicReference<WebSocket>()
    private val submissions = AtomicInteger()
    private val acceptedByServer = CountDownLatch(1)
    private val socketEnded = CountDownLatch(1)
    private val releaseDeadline = CountDownLatch(1)
    private var scheduler: ScheduledThreadPoolExecutor? = null

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        http = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                when (request.optString("method")) {
                    "conversation.open" -> webSocket.send(result(request.getString("id"), ready()))
                    "prompt.submit" -> {
                        submissions.incrementAndGet()
                        webSocket.send(result(request.getString("id"), JSONObject()
                            .put("schema", 1)
                            .put("conversation_handle", HANDLE)
                            .put("turn_id", TURN)
                            .put("status", "submitted")))
                        acceptedByServer.countDown()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                socketEnded.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketEnded.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketEnded.countDown()
            }
        }))
    }

    @After
    fun tearDown() {
        releaseDeadline.countDown()
        client?.close()
        scheduler?.shutdownNow()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    @Test
    fun tls_upgrade_and_scoped_keepalives_deliver_one_real_terminal() {
        val transport = createClient()
        connectAndAssertUpgrade(transport)
        val accepted = accept(transport)
        val events = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())
        val terminal = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val observation = transport.observeTurn(accepted.binding) {
            events.add(it)
            if (it is AndroidNormalizedEvent.TurnCompleted) terminal.countDown()
        }
        val connection = transport.observeConnection { disconnected.countDown() }
        try {
            send(event("turn.alive", JSONObject().put("phase", "running")))
            send(event("turn.alive", JSONObject().put("phase", "not-a-phase")))
            send(event("turn.alive", JSONObject().put("phase", "running"), turn = "other-turn"))
            send(event("turn.alive", JSONObject().put("phase", "running"), handle = "other-handle"))
            send(event("message.delta", JSONObject().put("text", "Fixture answer")))
            val completed = event("message.complete", JSONObject()
                .put("rendered", "Fixture answer").put("status", "complete"))
            send(completed)
            send(completed)
            assertTrue("real response terminal missing", terminal.await(10, TimeUnit.SECONDS))
            // The ordered close is a receive barrier for all preceding frames,
            // including the duplicate terminal: no timing-based negative assertion.
            assertTrue(peer.get().close(1000, "fixture complete"))
            assertTrue("ordered socket close missing", disconnected.await(10, TimeUnit.SECONDS))
            val received = synchronized(events) { events.toList() }
            assertEquals("heartbeat must never reach observers", 2, received.size)
            assertTrue(received[0] is AndroidNormalizedEvent.ResponseTextDelta)
            assertTrue(received[1] is AndroidNormalizedEvent.TurnCompleted)
            assertEquals("Fixture answer", (received[0] as AndroidNormalizedEvent.ResponseTextDelta).text)
            assertEquals(1, submissions.get())
            assertEquals(1, transport.snapshotRequestTelemetry().promptSubmitCount)
            assertFalse(transport.hasActiveTurn())
        } finally {
            observation.cancel()
            connection.cancel()
        }
    }

    @Test
    fun silent_accepted_turn_expires_disconnects_and_never_resubmits() {
        val deadlineReady = CountDownLatch(1)
        // Let the real short deadline become due, but gate execution until the
        // observer is attached. This removes acceptance/observer scheduling races.
        val executor = object : ScheduledThreadPoolExecutor(1) {
            override fun beforeExecute(thread: Thread, task: Runnable) {
                super.beforeExecute(thread, task)
                deadlineReady.countDown()
                check(releaseDeadline.await(10, TimeUnit.SECONDS)) { "deadline gate not released" }
            }
        }
        val transport = createClient(
            executor,
            HomeTurnDeadlines(controlIdleMillis = 100, controlTerminalMillis = 200, controlBackstopMillis = 1_000),
        )
        connectAndAssertUpgrade(transport)
        val disconnected = CountDownLatch(1)
        val disconnectCount = AtomicInteger()
        val connection = transport.observeConnection {
            disconnectCount.incrementAndGet()
            disconnected.countDown()
        }
        try {
            accept(transport)
            assertTrue("control deadline was not scheduled", deadlineReady.await(10, TimeUnit.SECONDS))
            releaseDeadline.countDown()
            assertTrue("expiry did not report Disconnected", disconnected.await(10, TimeUnit.SECONDS))
            assertTrue("expiry did not close the socket", socketEnded.await(10, TimeUnit.SECONDS))
            assertEquals(1, disconnectCount.get())
            assertEquals("server saw exactly one prompt.submit", 1, submissions.get())
            assertEquals("client never replayed the prompt", 1, transport.snapshotRequestTelemetry().promptSubmitCount)
            assertFalse(transport.hasActiveTurn())
        } finally {
            connection.cancel()
        }
    }

    private fun createClient(
        executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1),
        deadlines: HomeTurnDeadlines = HomeTurnDeadlines(),
    ): OkHttpRelaySessionClient {
        scheduler = executor
        val profiles = RelayProfileCollection(
            profiles = listOf(RelayProfile(
                id = PROFILE,
                endpoint = server.url("/").toString().replaceFirst("https://", "wss://"),
                clientId = "fixture-client",
                deviceId = "fixture-device",
                displayName = "Loopback fixture",
                homeBinding = RelayHomeBinding(
                    approvedRoute = server.url("/").toString().replaceFirst("https://", "wss://"),
                    conversationHandle = HANDLE,
                ),
            )),
            selectedId = PROFILE,
        )
        return OkHttpRelaySessionClient(
            collection = { profiles },
            credentials = InMemoryRelayCredentialStore(homeCredentials = mapOf(PROFILE to CREDENTIAL)),
            httpClient = http,
            helloTimeoutMillis = 10_000,
            requestTimeoutMillis = 10_000,
            controlScheduler = executor,
            controlDeadlines = deadlines,
        ).also { client = it }
    }

    private fun connectAndAssertUpgrade(transport: OkHttpRelaySessionClient) {
        val connected = transport.reconnect() as AndroidReconnectOutcome.Connected
        assertTrue(connected.capabilities.turnKeepalive)
        val upgrade = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        assertEquals("/api/v1/bridge/ws", upgrade.path)
        assertEquals(listOf("turn_keepalive"), upgrade.headers.values("X-Hermes-Home-Client-Features"))
        assertEquals("Device $CREDENTIAL", upgrade.getHeader("Authorization"))
    }

    private fun accept(transport: OkHttpRelaySessionClient): AndroidInitiationResult.Accepted {
        val accepted = transport.beginTurn(AndroidTurnRequest(
            AndroidProfile(PROFILE, "Loopback fixture"), AndroidTurnInput.Typed("fixture prompt"),
        )) as AndroidInitiationResult.Accepted
        assertTrue("server did not accept prompt", acceptedByServer.await(10, TimeUnit.SECONDS))
        return accepted
    }

    private fun send(frame: String) = assertTrue("fixture send rejected", peer.get().send(frame))

    private fun ready() = JSONObject()
        .put("schema", 1).put("status", "ready").put("unresolved_turn", false)
        .put("conversation_handle", HANDLE)
        .put("route", JSONObject().put("class", "home").put("id", "fixture-route"))
        .put("capabilities", JSONObject().put("heartbeat", true).put("timing", "absent")
            .put("commands", JSONArray()).put("interrupt", true).put("audio", false)
            .put("turn_keepalive", true))

    private fun result(id: String, body: JSONObject) = JSONObject()
        .put("schema", 1).put("jsonrpc", "2.0").put("id", id).put("result", body).toString()

    private fun event(type: String, payload: JSONObject, turn: String = TURN, handle: String = HANDLE) = JSONObject()
        .put("schema", 1).put("jsonrpc", "2.0").put("method", "event")
        .put("params", JSONObject().put("schema", 1).put("conversation_handle", handle)
            .put("turn_id", turn).put("correlation_id", "fixture-correlation")
            .put("event", JSONObject().put("type", type).put("payload", payload))).toString()

    private companion object {
        const val PROFILE = "control-lease-fixture"
        const val HANDLE = "control-lease-conversation"
        const val TURN = "control-lease-turn"
        const val CREDENTIAL = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
