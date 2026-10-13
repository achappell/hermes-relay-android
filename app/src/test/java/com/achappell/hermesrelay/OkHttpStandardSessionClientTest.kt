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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OkHttpStandardSessionClientTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
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
    private val rejectSubmit = AtomicBoolean(false)
    private val completeWithoutSubmitReply = AtomicBoolean(false)
    private var submissionStore: StandardRestartStore? = null
    private val markerAtSubmission = AtomicBoolean(false)
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

    private fun client(
        sink: AndroidAudioSink = RecordingAudioSink(),
        store: StandardRestartStore = InMemoryStandardRestartStore(),
    ) = OkHttpStandardSessionClient(
        { profiles }, credentials, store,
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
                    "prompt.submit" -> {
                        submissionStore?.let { markerAtSubmission.set(it.hasUnresolvedTurn(profile)) }
                        if (rejectSubmit.get()) {
                            webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", frame.getString("id"))
                                .put("error", JSONObject().put("code", 4007).put("message", "standard-secret")).toString())
                        } else if (completeWithoutSubmitReply.get()) {
                            webSocket.send(JSONObject().put("jsonrpc", "2.0").put("method", "event")
                                .put("params", JSONObject().put("session_id", "runtime").put("seq", 1)
                                    .put("type", "message.complete").put("payload", JSONObject())).toString())
                        } else if (!omitSubmitReply.get()) respond(webSocket, frame, JSONObject().put("status", "submitted"))
                    }
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

    @Test fun process_restart_restores_before_admission_and_requires_successful_explicit_new_conversation() {
        val directory = temporaryFolder.newFolder()
        val store = FileStandardRestartStore(directory)
        submissionStore = store
        enqueueSession()
        val original = client(store = store)
        assertTrue(original.reconnect() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        submit(original)
        assertTrue(markerAtSubmission.get())
        assertFalse(original.hasUncertainTurn())
        assertTrue(original.reconnect() is AndroidReconnectOutcome.Connected)
        assertTrue(requests.isEmpty())
        original.close()

        val restoredStore = FileStandardRestartStore(directory)
        val restored = client(store = restoredStore)
        assertTrue(restored.hasUncertainTurn())
        val before = server.requestCount
        val failure = restored.reconnect() as AndroidReconnectOutcome.Unrecoverable
        assertEquals(AndroidHomeUnavailableReason.StaleConversation, failure.reasonCode)
        assertFalse(restored.hasLiveTransport())
        val runtime = AndroidRecoveryController(restored, policy = ReconnectPolicy.unpaced(3)).recover()
        assertEquals(AndroidHomeUnavailableReason.StaleConversation,
            (runtime.connection as AndroidConnectionState.Failed).reasonCode)
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            restored.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("not replayed"))))
        assertEquals(before, server.requestCount)
        assertTrue(requests.isEmpty())

        rejectCreate.set(true)
        enqueueSession()
        assertTrue(restored.newConversation() is AndroidReconnectOutcome.Unrecoverable)
        nextMethod("session.create")
        assertTrue(restored.hasUncertainTurn())
        assertTrue(restoredStore.hasUnresolvedTurn(profile))
        val afterFailure = server.requestCount
        assertTrue(restored.reconnect() is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(afterFailure, server.requestCount)
        rejectCreate.set(false)
        enqueueSession()
        assertTrue(restored.newConversation() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertFalse(restored.hasUncertainTurn())
        assertFalse(restoredStore.hasUnresolvedTurn(profile))
        assertTrue(requests.isEmpty())
        submit(restored)
        assertTrue(restoredStore.hasUnresolvedTurn(profile))
    }

    @Test fun definitive_rejection_and_interrupt_allow_clean_fresh_client_restart() {
        val directory = temporaryFolder.newFolder()
        val store = FileStandardRestartStore(directory)
        enqueueSession()
        val original = client(store = store)
        original.reconnect()
        nextMethod("session.create")
        rejectSubmit.set(true)
        val rejected = original.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("private prompt")))
        nextMethod("prompt.submit")
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected), rejected)
        assertFalse(store.hasUnresolvedTurn(profile))
        assertFalse(original.hasUncertainTurn())
        rejectSubmit.set(false)
        val binding = submit(original)
        assertTrue(original.interruptTurn(binding))
        nextMethod("session.interrupt")
        assertFalse(store.hasUnresolvedTurn(profile))
        original.close()
        enqueueSession()
        val restored = client(store = FileStandardRestartStore(directory))
        assertFalse(restored.hasUncertainTurn())
        assertTrue(restored.reconnect() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertTrue(requests.isEmpty())
    }

    @Test fun restart_marker_is_scoped_to_profile_endpoint_and_hermes_profile() {
        val directory = temporaryFolder.newFolder()
        val store = FileStandardRestartStore(directory)
        store.markUnresolvedTurn(profile)
        val original = profile
        listOf(
            original.copy(id = "other-id"),
            original.copy(endpoint = server.url("/other/ws").toString()),
            original.copy(hermesProfile = "other-hermes-profile"),
        ).forEach { isolated ->
            profile = isolated
            profiles = RelayProfileCollection(listOf(profile), profile.id)
            credentials.putStandardCredential(profile.id, "standard-secret")
            enqueueSession()
            val isolatedClient = client(store = FileStandardRestartStore(directory))
            assertFalse(isolatedClient.hasUncertainTurn())
            assertTrue(isolatedClient.reconnect() is AndroidReconnectOutcome.Connected)
            nextMethod("session.create")
            assertTrue(requests.isEmpty())
            isolatedClient.close()
        }
        profile = original
        profiles = RelayProfileCollection(listOf(profile), profile.id)
        val restored = client(store = FileStandardRestartStore(directory))
        val before = server.requestCount
        assertTrue(restored.hasUncertainTurn())
        assertTrue(restored.reconnect() is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(before, server.requestCount)
    }

    @Test fun storage_load_and_write_failures_fail_closed_without_submission() {
        val loadFailure = FailingRestartStore().apply { failLoad = true }
        val blocked = client(store = loadFailure)
        assertTrue(blocked.hasUncertainTurn())
        assertEquals(AndroidHomeUnavailableReason.StaleConversation,
            (blocked.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode)
        assertEquals(0, server.requestCount)
        enqueueSession()
        assertTrue(blocked.newConversation() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertFalse(blocked.hasUncertainTurn())

        val writeFailure = FailingRestartStore().apply { failMark = true }
        enqueueSession()
        val ready = client(store = writeFailure)
        ready.reconnect()
        nextMethod("session.create")
        val result = ready.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("not sent")))
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain), result)
        assertTrue(ready.hasUncertainTurn())
        assertFalse(ready.hasActiveTurn())
        assertTrue(requests.isEmpty())
        assertEquals(AndroidHomeUnavailableReason.StaleConversation,
            (ready.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode)
        assertFalse(journalLines.joinToString().contains("private-storage-detail"))
    }

    @Test fun failed_resolution_persistence_never_reports_new_conversation_recovery() {
        val store = FailingRestartStore()
        store.markUnresolvedTurn(profile)
        store.failClear = true
        val restored = client(store = store)
        enqueueSession()
        assertEquals(AndroidHomeUnavailableReason.StaleConversation,
            (restored.newConversation() as AndroidReconnectOutcome.Unrecoverable).reasonCode)
        nextMethod("session.create")
        assertTrue(restored.hasUncertainTurn())
        assertFalse(restored.hasLiveTransport())
        assertTrue(store.hasUnresolvedTurn(profile))
        assertTrue(requests.isEmpty())
        store.failClear = false
        enqueueSession()
        assertTrue(restored.newConversation() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertFalse(restored.hasUncertainTurn())
        assertFalse(store.hasUnresolvedTurn(profile))
    }

    @Test fun terminal_clear_failure_blocks_admission_and_retains_restart_marker() {
        val store = FailingRestartStore()
        enqueueSession()
        val active = client(store = store)
        active.reconnect()
        nextMethod("session.create")
        val binding = submit(active)
        store.failClear = true
        val terminal = CountDownLatch(1)
        active.observeTurn(binding) { if (it is AndroidNormalizedEvent.TurnInterrupted) terminal.countDown() }
        event("message.complete", JSONObject().put("status", "interrupted"))
        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertFalse(active.hasActiveTurn())
        assertTrue(active.hasUncertainTurn())
        assertTrue(store.hasUnresolvedTurn(profile))
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            active.beginTurn(AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("not sent"))))
        assertTrue(requests.isEmpty())
    }

    @Test fun authoritative_terminal_without_submit_reply_does_not_restore_false_uncertainty() {
        val store = FileStandardRestartStore(temporaryFolder.newFolder())
        enqueueSession()
        val active = client(store = store)
        active.reconnect()
        nextMethod("session.create")
        completeWithoutSubmitReply.set(true)
        submit(active)
        assertFalse(active.hasUncertainTurn())
        assertFalse(active.hasActiveTurn())
        assertFalse(store.hasUnresolvedTurn(profile))
        assertEquals(1, journalLines.count { it == "standard prompt result=accepted" })
        assertTrue(requests.isEmpty())
    }

    @Test fun late_terminal_preserves_already_raised_uncertainty_across_restart() {
        val directory = temporaryFolder.newFolder()
        val store = FileStandardRestartStore(directory)
        enqueueSession()
        val active = client(store = store)
        active.reconnect()
        nextMethod("session.create")
        omitSubmitReply.set(true)
        val result = active.beginTurn(
            AndroidTurnRequest(AndroidProfile(profile.id, profile.displayName), AndroidTurnInput.Typed("private prompt")),
        )
        nextMethod("prompt.submit")
        assertTrue(result is AndroidInitiationResult.Uncertain)
        val disconnected = CountDownLatch(1)
        active.observeConnection { disconnected.countDown() }
        event("message.complete")
        // The close frame follows the terminal on the same socket, so this callback
        // is a receipt barrier rather than a sleep or a guessed event-processing delay.
        assertTrue(peer.get().close(1000, null))
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertTrue(active.hasUncertainTurn())
        assertTrue(store.hasUnresolvedTurn(profile))
        active.close()

        val restarted = client(store = FileStandardRestartStore(directory))
        assertTrue(restarted.hasUncertainTurn())
        val recovery = restarted.reconnect() as AndroidReconnectOutcome.Unrecoverable
        assertEquals(AndroidHomeUnavailableReason.StaleConversation, recovery.reasonCode)
        assertEquals(1, server.requestCount)
        assertTrue(requests.isEmpty())
    }

    @Test fun rejection_clear_failure_retains_marker_and_blocks_further_submission() {
        val store = FailingRestartStore()
        enqueueSession()
        val active = client(store = store)
        active.reconnect()
        nextMethod("session.create")
        store.failClear = true
        rejectSubmit.set(true)
        val request = AndroidTurnRequest(AndroidProfile(profile.id, "Standard"), AndroidTurnInput.Typed("private prompt"))
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain), active.beginTurn(request))
        nextMethod("prompt.submit")
        assertTrue(active.hasUncertainTurn())
        assertFalse(active.hasActiveTurn())
        assertTrue(store.hasUnresolvedTurn(profile))
        assertEquals(AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain), active.beginTurn(request))
        assertTrue(requests.isEmpty())
        assertFalse(journalLines.joinToString().contains("private-storage-detail"))
    }

    private class FailingRestartStore : StandardRestartStore {
        private val delegate = InMemoryStandardRestartStore()
        var failLoad = false
        var failMark = false
        var failClear = false
        override fun hasUnresolvedTurn(profile: RelayProfile): Boolean {
            check(!failLoad) { "private-storage-detail" }
            return delegate.hasUnresolvedTurn(profile)
        }
        override fun markUnresolvedTurn(profile: RelayProfile) {
            check(!failMark) { "private-storage-detail" }
            delegate.markUnresolvedTurn(profile)
        }
        override fun clearUnresolvedTurn(profile: RelayProfile) {
            check(!failClear) { "private-storage-detail" }
            delegate.clearUnresolvedTurn(profile)
        }
        override fun deleteProfile(profileId: String) = delegate.deleteProfile(profileId)
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
        val store = FileStandardRestartStore(temporaryFolder.newFolder())
        val client = client(store = store)
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
        event("message.complete", JSONObject().put("status", "interrupted"))
        assertTrue(events.poll(5, TimeUnit.SECONDS) is AndroidNormalizedEvent.TurnInterrupted)
        assertTrue(client.hasUncertainTurn())
        assertTrue(store.hasUnresolvedTurn(profile))
        assertTrue(requests.isEmpty())
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
        val directory = temporaryFolder.newFolder()
        val store = FileStandardRestartStore(directory)
        val client = client(sink, store)
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
        assertFalse(store.hasUnresolvedTurn(profile))
        assertFalse(client.hasUncertainTurn())
        val restartedDuringDrain = client(store = FileStandardRestartStore(directory))
        assertFalse(restartedDuringDrain.hasUncertainTurn())
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
        client.close()
        enqueueSession()
        assertTrue(restartedDuringDrain.reconnect() is AndroidReconnectOutcome.Connected)
        nextMethod("session.create")
        assertTrue(requests.isEmpty())
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
        val store = InMemoryStandardRestartStore()
        val client = client(store = store)
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
        assertFalse(store.hasUnresolvedTurn(profile))
        assertFalse(client.hasUncertainTurn())
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
