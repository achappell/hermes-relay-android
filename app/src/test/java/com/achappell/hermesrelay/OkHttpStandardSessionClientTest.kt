package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OkHttpStandardSessionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates
    private lateinit var profile: RelayProfile
    private lateinit var profiles: RelayProfileCollection
    private lateinit var credentials: InMemoryRelayCredentialStore
    private val clients = mutableListOf<OkHttpStandardSessionClient>()
    private val requests = LinkedBlockingQueue<JSONObject>()
    private val peer = AtomicReference<WebSocket>()
    private val rejectCreate = AtomicBoolean(false)
    private val omitSubmitReply = AtomicBoolean(false)
    private val holdInterrupt = AtomicBoolean(false)
    private val interruptSeen = CountDownLatch(1)
    private val interruptRequest = AtomicReference<JSONObject>()
    private val journalLines = Collections.synchronizedList(mutableListOf<String>())
    private val journal = object : DiagnosticsJournal {
        override fun record(event: String) { journalLines.add(event) }
        override fun snapshot() = emptyList<JournalEntry>()
    }

    @Before fun setup() {
        server = MockWebServer()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.start()
        profile = RelayProfile("standard-id", server.url("/api/ws").toString(), "", "", "Standard",
            mode = RelayConnectionMode.Standard, hermesProfile = "amanda")
        profiles = RelayProfileCollection(listOf(profile), profile.id)
        credentials = InMemoryRelayCredentialStore()
        credentials.putStandardCredential(profile.id, "standard-secret")
        credentials.put(profile.id, "rollback-secret")
        credentials.putHomeCredential(profile.id, "home-secret")
    }

    @After fun teardown() {
        clients.forEach { it.close() }
        server.shutdown()
    }

    private fun client(sink: AndroidAudioSink = RecordingAudioSink()) = OkHttpStandardSessionClient(
        { profiles }, credentials,
        OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build(),
        sink, journal, requestTimeoutMillis = 1_000,
    ).also { clients.add(it) }

    private fun enqueueSession() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                peer.set(webSocket)
                val frame = JSONObject(text)
                requests.add(frame)
                when (frame.getString("method")) {
                    "session.create" -> if (rejectCreate.get()) {
                        webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", frame.getString("id"))
                            .put("error", JSONObject().put("code", 4007).put("message", "standard-secret")).toString())
                    } else respond(webSocket, frame, JSONObject().put("session_id", "runtime").put("stored_session_id", "durable"))
                    "session.resume" -> respond(webSocket, frame, JSONObject().put("session_id", "runtime-resumed"))
                    "prompt.submit" -> if (!omitSubmitReply.get()) respond(webSocket, frame, JSONObject().put("status", "submitted"))
                    "session.interrupt" -> {
                        interruptRequest.set(frame)
                        if (!holdInterrupt.get()) respond(webSocket, frame, JSONObject().put("status", "interrupted"))
                        interruptSeen.countDown()
                    }
                }
            }
        }))
    }

    private fun respond(socket: WebSocket, request: JSONObject, result: JSONObject) {
        socket.send(JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id")).put("result", result).toString())
    }

    private fun nextMethod(expected: String): JSONObject {
        val frame = requests.poll(5, TimeUnit.SECONDS)
        assertNotNull("Expected $expected", frame)
        assertEquals(expected, frame!!.getString("method"))
        return frame
    }

    private fun submit(client: OkHttpStandardSessionClient): AndroidTurnBinding {
        val result = client.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, profile.displayName), AndroidTurnInput.Typed("private prompt")))
        assertTrue(result is AndroidInitiationResult.Accepted)
        nextMethod("prompt.submit")
        return (result as AndroidInitiationResult.Accepted).binding
    }

    private fun event(type: String, payload: JSONObject = JSONObject(), session: String = "runtime") {
        assertTrue(peer.get().send(JSONObject().put("jsonrpc", "2.0").put("method", "event")
            .put("params", JSONObject().put("session_id", session).put("seq", 1).put("type", type).put("payload", payload)).toString()))
    }

    @Test fun http_401_and_403_remain_nonretryable_authorization_failures_until_manual_recovery() {
        listOf(401, 403).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("standard-secret"))
            val client = client()
            val controller = AndroidRecoveryController(client, policy = ReconnectPolicy.unpaced(3))
            val before = server.requestCount

            val failed = controller.recover()

            assertEquals(before + 1, server.requestCount)
            assertEquals(AndroidHomeUnavailableReason.Unauthorized, (failed.connection as AndroidConnectionState.Failed).reasonCode)
            assertEquals(AndroidAuthorizationState.Verifying, client.snapshot().authorizationState)
            assertEquals(AndroidAvailabilityBlock.Authorization, resolveAndroidAvailabilityBlock(client.snapshot(), failed.connection))
            assertFalse(client.hasLiveTransport())
            assertTrue(requests.isEmpty())
            assertFalse(failed.connection.reason.contains("standard-secret"))

            enqueueSession()
            val recovered = controller.recover()

            nextMethod("session.create")
            assertEquals(AndroidConnectionState.Connected, recovered.connection)
            assertNull(resolveAndroidAvailabilityBlock(client.snapshot(), recovered.connection))
            assertTrue(requests.isEmpty())
            client.close()
        }
    }

    @Test fun session_rpc_refusal_is_connection_guidance_not_credential_repair_and_does_not_replay() {
        rejectCreate.set(true)
        enqueueSession()
        val client = client()
        val controller = AndroidRecoveryController(client, policy = ReconnectPolicy.unpaced(3))

        val failed = controller.recover()

        nextMethod("session.create")
        assertEquals(1, server.requestCount)
        assertEquals(AndroidHomeUnavailableReason.RequestRejected, (failed.connection as AndroidConnectionState.Failed).reasonCode)
        assertEquals(AndroidAuthorizationState.Verifying, client.snapshot().authorizationState)
        assertEquals(AndroidAvailabilityBlock.Connection, resolveAndroidAvailabilityBlock(client.snapshot(), failed.connection))
        assertTrue(requests.isEmpty())
        assertFalse(failed.connection.reason.contains("standard-secret"))

        rejectCreate.set(false)
        enqueueSession()
        val recovered = controller.recover()

        nextMethod("session.create")
        assertEquals(AndroidConnectionState.Connected, recovered.connection)
        assertNull(resolveAndroidAvailabilityBlock(client.snapshot(), recovered.connection))
        assertTrue(requests.isEmpty())
        assertFalse(journalLines.joinToString().contains("standard-secret"))
    }

    @Test fun direct_handshake_uses_only_standard_slot_and_has_no_home_capabilities() {
        enqueueSession()
        val client = client()
        val outcome = client.reconnect() as AndroidReconnectOutcome.Connected
        val frame = nextMethod("session.create")
        assertEquals("android", frame.getJSONObject("params").getString("source"))
        assertEquals("amanda", frame.getJSONObject("params").getString("profile"))
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/ws", upgrade.requestUrl!!.encodedPath)
        assertEquals("standard-secret", upgrade.requestUrl!!.queryParameter("token"))
        assertNull(upgrade.getHeader("Authorization"))
        assertNull(outcome.route)
        assertEquals(AndroidHomeCapabilities(), outcome.capabilities)
        assertEquals(RelayConnectionMode.Standard, client.connectionMode())
        assertTrue(client.supportsInterrupt())
        assertFalse((client as Any) is AndroidHomeConversations)
        assertFalse(journalLines.joinToString().contains("secret"))
        assertTrue(journalLines.contains("standard session method=session.create result=connected uncertain=false"))
    }

    @Test fun absent_standard_slot_never_falls_back_to_home_or_rollback() {
        credentials.deleteStandardCredential(profile.id)
        assertTrue(client().reconnect() is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(0, server.requestCount)
    }

    @Test fun disconnect_resume_uses_durable_reference_does_not_replay_and_preserves_uncertainty() {
        enqueueSession()
        val client = client()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        submit(client)
        val disconnected = CountDownLatch(1)
        client.observeConnection { disconnected.countDown() }
        peer.get().close(1000, "private server detail")
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertTrue(client.hasUncertainTurn())
        enqueueSession()
        val connected = client.reconnect() as AndroidReconnectOutcome.Connected
        val resume = nextMethod("session.resume")
        assertEquals("durable", resume.getJSONObject("params").getString("session_id"))
        assertTrue(connected.unresolvedTurn)
        assertTrue(client.hasUncertainTurn())
        client.prepareForExplicitResend()
        assertTrue(client.hasUncertainTurn())
        val rejected = client.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("do not replay")))
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain), rejected)
        assertTrue(requests.isEmpty())
        assertFalse(journalLines.joinToString().contains("private"))
    }

    @Test fun failed_new_conversation_preserves_guard_success_alone_clears_it() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        omitSubmitReply.set(true)
        val uncertain = client.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("private prompt")))
        nextMethod("prompt.submit")
        assertTrue(uncertain is AndroidInitiationResult.Uncertain)
        rejectCreate.set(true)
        assertTrue(client.newConversation() is AndroidReconnectOutcome.Unrecoverable)
        nextMethod("session.create")
        assertTrue(client.hasUncertainTurn())
        rejectCreate.set(false)
        enqueueSession()
        assertTrue(client.newConversation() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertFalse(client.hasUncertainTurn())
        assertFalse(client.hasActiveTurn())
        assertFalse(journalLines.joinToString().contains("standard-secret"))
    }

    @Test fun active_or_uncertain_identity_cannot_switch_but_idle_identity_gets_fresh_session() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val other = profile.copy(id = "second", hermesProfile = "other")
        credentials.putStandardCredential(other.id, "second-secret")
        profiles = RelayProfileCollection(listOf(profile, other), other.id)
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Unrecoverable)
        assertTrue(requests.isEmpty())
        assertFalse(client.hasLiveTransport())
        profiles = RelayProfileCollection(listOf(profile), profile.id)
        assertTrue(client.interruptTurn(binding))
        nextMethod("session.interrupt")
        profiles = RelayProfileCollection(listOf(profile, other), other.id)
        enqueueSession()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val fresh = nextMethod("session.create")
        assertEquals("other", fresh.getJSONObject("params").getString("profile"))
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("second-secret", server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("token"))
    }

    @Test fun unsupported_prompt_is_visible_failure_not_approval_or_response_text() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val events = LinkedBlockingQueue<AndroidNormalizedEvent>()
        client.observeTurn(binding) { events.add(it) }
        event("secret.request", JSONObject().put("prompt", "sensitive content"))
        val failure = events.poll(5, TimeUnit.SECONDS)
        assertTrue(failure is AndroidNormalizedEvent.TurnFailed)
        assertTrue((failure as AndroidNormalizedEvent.TurnFailed).reason.contains("unsupported"))
        assertTrue(client.hasUncertainTurn())
        assertTrue(client.hasActiveTurn())
        assertFalse(journalLines.joinToString().contains("sensitive"))
    }

    @Test fun interrupt_stops_local_audio_immediately_but_busy_waits_for_acknowledgement() {
        enqueueSession()
        val sink = DrainSink()
        val client = client(sink)
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        holdInterrupt.set(true)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(false)
        val cancellations = sink.cancels
        val worker = Thread { result.set(client.interruptTurn(binding)); finished.countDown() }
        worker.start()
        assertTrue(interruptSeen.await(5, TimeUnit.SECONDS))
        nextMethod("session.interrupt")
        assertTrue(sink.cancels > cancellations)
        assertTrue(client.hasActiveTurn())
        respond(peer.get(), interruptRequest.get(), JSONObject().put("status", "interrupted"))
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(result.get())
        assertFalse(client.hasActiveTurn())
        worker.join()
    }

    @Test fun streamed_text_and_pcm_keep_turn_busy_until_sink_drain() {
        profile = profile.copy(endpoint = server.url("/proxy/api/ws?region=west").toString())
        profiles = RelayProfileCollection(listOf(profile), profile.id)
        enqueueSession()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optBoolean("done")) {
                    webSocket.send(JSONObject().put("type", "start").put("sample_rate", 24000).put("channels", 1).toString())
                    webSocket.send(byteArrayOf(1, 2, 3).toByteString())
                    webSocket.send(byteArrayOf(4).toByteString())
                    webSocket.send(JSONObject().put("type", "end").toString())
                } else assertEquals("hello world", frame.getString("text"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        val sink = DrainSink()
        val client = client(sink)
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val observed = Collections.synchronizedList(mutableListOf<AndroidNormalizedEvent>())
        val completed = CountDownLatch(1)
        client.observeTurn(binding) { observed.add(it); if (it is AndroidNormalizedEvent.TurnCompleted) completed.countDown() }
        event("message.delta", JSONObject().put("text", "hello "))
        event("message.delta", JSONObject().put("text", "world"))
        event("message.complete", JSONObject().put("text", "hello world"))
        assertTrue(sink.finishCalled.await(5, TimeUnit.SECONDS))
        assertTrue(client.hasActiveTurn())
        assertEquals(4, sink.bytes)
        assertEquals(AndroidAudioFormat(24000, 1, 2, "pcm_s16le"), sink.format)
        assertTrue(observed.any { it is AndroidNormalizedEvent.TextCompleted })
        assertFalse(observed.any { it is AndroidNormalizedEvent.TurnCompleted })
        sink.drained!!.invoke()
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertFalse(client.hasActiveTurn())
        assertTrue(observed.any { it is AndroidNormalizedEvent.AudioEnded })
        val chat = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("/proxy/api/ws", chat.encodedPath)
        assertEquals("west", chat.queryParameter("region"))
        val audio = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("/proxy/api/audio/speak-stream", audio.encodedPath)
        assertEquals("west", audio.queryParameter("region"))
        assertEquals("amanda", audio.queryParameter("profile"))
        assertEquals("standard-secret", audio.queryParameter("token"))
        assertTrue(journalLines.contains("standard audio result=drained"))
    }

    @Test fun sidecar_failure_is_sanitized_and_releases_completed_text_turn() {
        enqueueSession()
        server.enqueue(MockResponse().setResponseCode(401).setBody("standard-secret"))
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val completed = CountDownLatch(1)
        val failures = LinkedBlockingQueue<AndroidNormalizedEvent.AudioFailed>()
        client.observeTurn(binding) {
            if (it is AndroidNormalizedEvent.AudioFailed) failures.add(it)
            if (it is AndroidNormalizedEvent.TurnCompleted) completed.countDown()
        }
        event("message.complete", JSONObject().put("text", "private reply"))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertFalse(client.hasActiveTurn())
        assertEquals("Standard response audio is unavailable.", failures.poll()!!.reason)
        assertFalse(journalLines.joinToString().contains("secret"))
    }

    @Test fun interrupted_terminal_is_not_success_and_never_opens_audio() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val terminal = LinkedBlockingQueue<AndroidNormalizedEvent>()
        client.observeTurn(binding) {
            if (it is AndroidNormalizedEvent.TurnInterrupted || it is AndroidNormalizedEvent.TurnCompleted) terminal.add(it)
        }
        event("message.complete", JSONObject().put("status", "interrupted").put("text", "partial"))
        assertTrue(terminal.poll(5, TimeUnit.SECONDS) is AndroidNormalizedEvent.TurnInterrupted)
        assertFalse(client.hasActiveTurn())
        assertEquals(1, server.requestCount)
    }

    @Test fun lifecycle_close_preserves_uncertainty_and_resume_remains_reusable() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        submit(client)
        client.closeForLifecycle()
        assertTrue(client.hasUncertainTurn())
        assertFalse(client.hasLiveTransport())
        enqueueSession()
        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        val resume = nextMethod("session.resume").getJSONObject("params")
        assertEquals("durable", resume.getString("session_id"))
        assertEquals("amanda", resume.getString("profile"))
        assertEquals("android", resume.getString("source"))
        assertTrue(client.hasUncertainTurn())
        assertTrue(requests.isEmpty())
    }

    @Test fun foreign_session_events_cannot_render_or_finish_current_turn() {
        enqueueSession()
        val client = client()
        client.reconnect()
        nextMethod("session.create")
        val binding = submit(client)
        val observed = LinkedBlockingQueue<AndroidNormalizedEvent>()
        client.observeTurn(binding) { observed.add(it) }
        event("message.delta", JSONObject().put("text", "foreign text"), "other-runtime")
        event("message.complete", JSONObject().put("text", "foreign text"), "other-runtime")
        event("message.delta", JSONObject().put("text", "own text"))
        val own = observed.poll(5, TimeUnit.SECONDS)
        assertTrue(own is AndroidNormalizedEvent.ResponseTextDelta)
        assertEquals("own text", (own as AndroidNormalizedEvent.ResponseTextDelta).text)
        assertTrue(client.hasActiveTurn())
        assertTrue(observed.isEmpty())
    }

    private class DrainSink : AndroidAudioSink {
        val finishCalled = CountDownLatch(1)
        @Volatile var drained: (() -> Unit)? = null
        @Volatile var format: AndroidAudioFormat? = null
        @Volatile var bytes = 0
        @Volatile var cancels = 0
        override fun start(format: AndroidAudioFormat): Boolean { this.format = format; return true }
        override fun write(bytes: ByteArray) { this.bytes += bytes.size }
        override fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit) { drained = onDrained; finishCalled.countDown() }
        override fun cancel() { cancels++ }
    }
}
