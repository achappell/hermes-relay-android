package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `ANDROID-STD-01` slice 1: the direct Standard `/api/ws` adapter against a
 * scripted gateway over TLS. Nothing sleeps: waits are latches and queues the
 * code under test signals; the two timeout tests wait on the code's own
 * (short, injected) deadline.
 */
class OkHttpStandardSessionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates
    private val collection = AtomicReference<RelayProfileCollection>()

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
        collection.set(collectionFor(profile()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- connect and auth --------------------------------------------------

    @Test
    fun the_upgrade_carries_token_and_profile_in_the_query_and_no_authorization_header() {
        val gateway = gateway()
        val client = client()

        val outcome = client.reconnect()

        assertTrue(outcome.toString(), outcome is AndroidReconnectOutcome.Connected)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/ws", request.requestUrl!!.encodedPath)
        assertEquals(setOf("token", "profile"), request.requestUrl!!.queryParameterNames)
        assertEquals(TOKEN, request.requestUrl!!.queryParameter("token"))
        assertEquals("default", request.requestUrl!!.queryParameter("profile"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Sec-WebSocket-Protocol"))
        val create = gateway.requests.single()
        assertEquals("session.create", create.getString("method"))
        assertEquals("2.0", create.getString("jsonrpc"))
        assertEquals(
            JSONObject().put("source", "android").put("profile", "default").toString(),
            create.getJSONObject("params").toString(),
        )
        client.close()
    }

    @Test
    fun no_request_is_sent_until_gateway_ready_arrives() {
        val gateway = gateway(sendReadyOnOpen = false)
        val client = client()
        val outcome = AtomicReference<AndroidReconnectOutcome>()
        val done = CountDownLatch(1)
        Thread {
            outcome.set(client.reconnect())
            done.countDown()
        }.start()

        assertTrue(gateway.opened.await(5, TimeUnit.SECONDS))
        gateway.sendReady()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertTrue(outcome.get() is AndroidReconnectOutcome.Connected)
        assertFalse("a request preceded gateway.ready", gateway.sentBeforeReady)
        assertEquals(listOf("session.create"), gateway.methods())
        client.close()
    }

    @Test
    fun a_gateway_that_never_becomes_ready_times_out_with_no_request() {
        val gateway = gateway(sendReadyOnOpen = false)
        val client = client(readyTimeoutMillis = 300)

        val outcome = client.reconnect()

        assertEquals(
            AndroidReconnectOutcome.Retryable(
                "The Standard gateway did not answer in time.",
                AndroidHomeUnavailableReason.TransportTimeout,
            ),
            outcome,
        )
        assertTrue(gateway.requests.isEmpty())
        client.close()
    }

    @Test
    fun an_event_before_gateway_ready_is_a_protocol_error() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(eventText("message.delta", JSONObject().put("text", "hi"), "runtime-1"))
                }
            }),
        )
        val client = client()

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.ProtocolError,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    @Test
    fun a_second_gateway_ready_is_a_protocol_violation_after_connect() {
        val gateway = gateway()
        val client = client()
        client.reconnect()
        val dropped = connectionEvents(client)

        gateway.send(eventText(StandardWire.READY, JSONObject(), null))

        assertNotNull(dropped.poll(5, TimeUnit.SECONDS))
        assertEquals(AndroidAuthorizationState.Unavailable, client.snapshot().authorizationState)
        client.close()
    }

    @Test
    fun an_upgrade_refusal_with_401_is_unauthorized_and_is_not_retried() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = client()

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.Unauthorized,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    @Test
    fun an_rpc_auth_error_on_create_is_unauthorized_and_an_other_error_is_rejected() {
        val authRefused = gateway()
        authRefused.script = { socket, request ->
            if (request.getString("method") == "session.create") {
                socket.send(errorText(request.getString("id"), 401, "unauthorized"))
                true
            } else {
                false
            }
        }
        val client = client()
        assertEquals(
            AndroidHomeUnavailableReason.Unauthorized,
            (client.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )

        val refused = gateway()
        refused.script = { socket, request ->
            socket.send(errorText(request.getString("id"), -32000, "no thanks"))
            true
        }
        assertEquals(
            AndroidHomeUnavailableReason.RequestRejected,
            (client.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    @Test
    fun an_unreachable_endpoint_is_a_retryable_transport_failure() {
        collection.set(collectionFor(profile(endpoint = "wss://localhost:1/api/ws")))
        val client = client()

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.TransportUnavailable,
            (outcome as AndroidReconnectOutcome.Retryable).reasonCode,
        )
        val snapshot = client.snapshot()
        assertEquals(AndroidAuthorizationState.Unavailable, snapshot.authorizationState)
        assertEquals(AndroidHomeUnavailableReason.TransportUnavailable, snapshot.unavailableReason)
        client.close()
    }

    @Test
    fun a_missing_credential_and_a_missing_profile_never_touch_the_network() {
        val noCredential = client(credentials = InMemoryRelayCredentialStore())
        assertEquals(
            AndroidHomeUnavailableReason.InvalidCredential,
            (noCredential.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )

        collection.set(RelayProfileCollection())
        val noProfile = client()
        assertEquals(
            AndroidHomeUnavailableReason.MissingBinding,
            (noProfile.reconnect() as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_standard_client_reads_only_the_standard_credential_slot() {
        val client = client(
            credentials = InMemoryRelayCredentialStore(
                initial = mapOf(PROFILE_ID to "rollback-bearer"),
                homeCredentials = mapOf(PROFILE_ID to "A".repeat(43)),
                homeAdminCredentials = mapOf(PROFILE_ID to "admin-secret"),
            ),
        )

        val snapshot = client.snapshot()
        val outcome = client.reconnect()

        assertEquals(AndroidAuthorizationState.Unavailable, snapshot.authorizationState)
        assertEquals(AndroidHomeUnavailableReason.InvalidCredential, snapshot.unavailableReason)
        assertEquals(
            AndroidHomeUnavailableReason.InvalidCredential,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reconnect_is_idempotent_and_single_flight() {
        val gateway = gateway()
        val client = client()
        val first = AtomicReference<AndroidReconnectOutcome>()
        val second = AtomicReference<AndroidReconnectOutcome>()
        val done = CountDownLatch(2)
        listOf(first, second).forEach { slot ->
            Thread {
                slot.set(client.reconnect())
                done.countDown()
            }.start()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        val a = first.get() as AndroidReconnectOutcome.Connected
        val b = second.get() as AndroidReconnectOutcome.Connected
        assertEquals(a.connectionId, b.connectionId)
        assertTrue(a.sessionStartedFresh || b.sessionStartedFresh)
        val third = client.reconnect() as AndroidReconnectOutcome.Connected
        assertEquals(a.connectionId, third.connectionId)
        assertEquals(1, gateway.count("session.create"))
        assertEquals(1, server.requestCount)
        client.close()
    }

    // ---- recovery ----------------------------------------------------------

    @Test
    fun a_dropped_socket_resumes_the_same_durable_session_and_never_sends_a_prompt() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        val client = client()
        val created = client.reconnect() as AndroidReconnectOutcome.Connected
        assertTrue(created.sessionStartedFresh)
        val dropped = connectionEvents(client)

        first.close()

        val lost = dropped.poll(5, TimeUnit.SECONDS)!!
        assertEquals(created.connectionId, lost.connectionId)
        assertEquals(AndroidAuthorizationState.Unavailable, client.snapshot().authorizationState)
        assertFalse(client.supportsInterrupt())

        val resumed = client.reconnect() as AndroidReconnectOutcome.Connected
        assertFalse(resumed.sessionStartedFresh)
        assertNotEquals(created.connectionId, resumed.connectionId)
        assertEquals(listOf("session.resume"), second.methods())
        assertEquals(
            "durable-1",
            second.requests.single().getJSONObject("params").getString("session_id"),
        )
        assertEquals(0, first.count("prompt.submit") + second.count("prompt.submit"))
        assertEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
        client.close()
    }

    @Test
    fun a_resume_the_gateway_refuses_is_a_stale_conversation_and_never_creates() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        second.script = { socket, request ->
            socket.send(errorText(request.getString("id"), -32004, "unknown session"))
            true
        }
        val client = client()
        client.reconnect()
        val dropped = connectionEvents(client)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.StaleConversation,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        assertEquals(listOf("session.resume"), second.methods())
        assertEquals(AndroidHomeUnavailableReason.StaleConversation, client.snapshot().unavailableReason)
        client.close()
    }

    @Test
    fun a_resume_answered_with_a_different_durable_id_is_a_conversation_mismatch() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        second.script = { socket, request ->
            socket.send(
                resultText(
                    request.getString("id"),
                    JSONObject().put("session_id", "runtime-2").put("stored_session_id", "durable-other"),
                ),
            )
            true
        }
        val client = client()
        client.reconnect()
        val dropped = connectionEvents(client)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.ConversationMismatch,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    @Test
    fun a_resume_that_loses_the_socket_is_retryable() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        second.script = { socket, _ ->
            socket.close(1011, "restart")
            true
        }
        val client = client()
        client.reconnect()
        val dropped = connectionEvents(client)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.TransportUnavailable,
            (outcome as AndroidReconnectOutcome.Retryable).reasonCode,
        )
        client.close()
    }

    @Test
    fun a_session_reply_with_a_profile_echo_for_another_profile_is_rejected() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            socket.send(
                resultText(
                    request.getString("id"),
                    JSONObject().put("session_id", "runtime-1").put("info", JSONObject().put("profile", "work")),
                ),
            )
            true
        }
        val client = client()

        val outcome = client.reconnect()

        assertEquals(
            AndroidHomeUnavailableReason.ConversationMismatch,
            (outcome as AndroidReconnectOutcome.Unrecoverable).reasonCode,
        )
        client.close()
    }

    // ---- typed streaming ---------------------------------------------------

    @Test
    fun a_typed_turn_streams_deltas_and_completes_text_only() {
        val gateway = gateway()
        val client = connected(client())
        val result = client.beginTurn(typed("hello hermes"))
        val binding = (result as AndroidInitiationResult.Accepted).binding
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit("message.start")
        gateway.emit("message.delta", JSONObject().put("text", "Rain "))
        gateway.emit("message.delta", JSONObject().put("rendered", "Rain later"))
        gateway.emit("message.complete", JSONObject().put("status", "complete"))
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(
            listOf(
                AndroidNormalizedEvent.Thinking(binding),
                AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain "),
                AndroidNormalizedEvent.ResponseTextDelta(binding, "later"),
                AndroidNormalizedEvent.TurnCompleted(binding, textOnly = true),
            ),
            events,
        )
        val state = events.fold(AndroidTurnState(binding = binding), AndroidTurnStateReducer::reduce)
        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals("Rain later", state.responseText)
        assertEquals("turn-1", binding.turnId)
        assertTrue(binding.conversationHandle.startsWith("standard-"))
        assertFalse(binding.conversationHandle.contains("runtime-1"))
        assertFalse(binding.conversationHandle.contains("durable-1"))
        assertFalse(client.hasActiveTurn())
        val prompt = gateway.requests.single { it.getString("method") == "prompt.submit" }
        assertEquals(
            JSONObject().put("session_id", "runtime-1").put("text", "hello hermes").toString(),
            prompt.getJSONObject("params").toString(),
        )
        client.close()
    }

    @Test
    fun a_final_text_that_repeats_the_stream_does_not_render_twice() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit("message.delta", JSONObject().put("text", "Done."))
        gateway.emit("message.complete", JSONObject().put("text", "Done."))
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(1, events.count { it is AndroidNormalizedEvent.ResponseTextDelta })
        client.close()
    }

    @Test
    fun events_that_arrive_with_the_reply_are_delivered_in_order_once_observed() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.send(
                    resultText(request.getString("id"), JSONObject().put("accepted", true).put("turn_id", "turn-1")),
                )
                gateway.emit("message.delta", JSONObject().put("text", "a"))
                gateway.emit("message.delta", JSONObject().put("text", "b"))
                gateway.emit("message.complete")
                true
            } else {
                false
            }
        }
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()

        client.observeTurn(binding, sink::accept)
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(
            listOf(
                AndroidNormalizedEvent.ResponseTextDelta(binding, "a"),
                AndroidNormalizedEvent.ResponseTextDelta(binding, "b"),
                AndroidNormalizedEvent.TurnCompleted(binding, textOnly = true),
            ),
            events,
        )
        client.close()
    }

    @Test
    fun foreign_sessions_foreign_turns_and_stale_sequences_are_ignored() {
        val journal = RecordingJournal()
        val gateway = gateway()
        val client = connected(client(journal = journal))
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit("message.delta", JSONObject().put("text", "OTHER-SESSION"), session = "runtime-other")
        gateway.emit("message.delta", JSONObject().put("text", "OTHER-TURN"), turn = "turn-other")
        gateway.emit("message.delta", JSONObject().put("text", "A"), seq = 1)
        gateway.emit("message.delta", JSONObject().put("text", "STALE"), seq = 1)
        gateway.emit("message.delta", JSONObject().put("text", "B"), seq = 2)
        gateway.emit("message.complete", seq = 3)
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(
            listOf("A", "B"),
            events.filterIsInstance<AndroidNormalizedEvent.ResponseTextDelta>().map { it.text },
        )
        assertTrue(journal.lines.contains("standard event ignored reason=foreign_turn"))
        client.close()
    }

    @Test
    fun an_event_naming_the_session_in_its_payload_only_still_belongs_to_it() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.send(
            eventText(
                "message.delta",
                JSONObject().put("text", "inner").put("session_id", "runtime-1"),
                session = null,
                turn = "turn-1",
            ),
        )
        gateway.emit("message.complete")
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(AndroidNormalizedEvent.ResponseTextDelta(binding, "inner"), events.first())
        client.close()
    }

    @Test
    fun conflicting_event_session_identities_are_a_protocol_violation() {
        val gateway = gateway()
        val client = connected(client())
        val dropped = connectionEvents(client)

        gateway.send(
            eventText(
                "message.delta",
                JSONObject().put("text", "x").put("session_id", "runtime-other"),
                session = "runtime-1",
            ),
        )

        assertNotNull(dropped.poll(5, TimeUnit.SECONDS))
        client.close()
    }

    @Test
    fun a_binary_frame_is_counted_and_never_delivered() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.sendBinary("RIFF-not-audio".encodeUtf8())
        gateway.emit("message.delta", JSONObject().put("text", "text"))
        gateway.emit("message.complete")
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertTrue(events.none { it is AndroidNormalizedEvent.AudioChunkReceived })
        assertEquals(1, client.ignoredBinaryFrameCount)
        client.close()
    }

    @Test
    fun a_tool_event_is_unknown_and_never_fails_the_turn() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit("tool.start", JSONObject().put("name", "search"))
        gateway.emit("message.complete")
        val events = sink.until { it is AndroidNormalizedEvent.TurnCompleted }

        assertEquals(AndroidNormalizedEvent.Unknown(binding, "tool.start"), events.first())
        client.close()
    }

    @Test
    fun an_error_event_fails_the_turn_and_frees_the_session_for_the_next_one() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit("error", JSONObject().put("code", "model_error"))
        val events = sink.until { it is AndroidNormalizedEvent.TurnFailed }

        assertEquals(AndroidNormalizedEvent.TurnFailed(binding, "model_error"), events.last())
        assertFalse(client.hasActiveTurn())
        assertTrue(client.beginTurn(typed("again")) is AndroidInitiationResult.Accepted)
        client.close()
    }

    @Test
    fun terminal_observers_see_the_turn_released_and_its_outcome_already_journaled() {
        for ((type, outcome) in listOf(
            "message.complete" to "completed",
            "error" to "failed",
            "session.interrupted" to "interrupted",
        )) {
            val journal = RecordingJournal()
            val gateway = gateway()
            val client = connected(client(journal = journal))
            val binding = accepted(client.beginTurn(typed("hi")))
            val observed = CountDownLatch(1)
            val callbackFailure = AtomicReference<AssertionError>()
            client.observeTurn(binding) { event ->
                if (event is AndroidNormalizedEvent.TurnCompleted ||
                    event is AndroidNormalizedEvent.TurnFailed ||
                    event is AndroidNormalizedEvent.TurnInterrupted
                ) {
                    try {
                        assertFalse("$type published before releasing the turn", client.hasActiveTurn())
                        assertTrue(journal.lines.contains("standard turn terminal outcome=$outcome"))
                        when (outcome) {
                            "completed" -> assertTrue(event is AndroidNormalizedEvent.TurnCompleted)
                            "failed" -> assertTrue(event is AndroidNormalizedEvent.TurnFailed)
                            "interrupted" -> assertTrue(event is AndroidNormalizedEvent.TurnInterrupted)
                        }
                    } catch (failure: AssertionError) {
                        callbackFailure.set(failure)
                    } finally {
                        observed.countDown()
                    }
                }
            }
            gateway.emit(type)
            assertTrue("no terminal callback for $type", observed.await(5, TimeUnit.SECONDS))
            callbackFailure.get()?.let { throw it }
            assertTrue(client.beginTurn(typed("again")) is AndroidInitiationResult.Accepted)
            client.close()
        }
    }

    @Test
    fun an_unsupported_prompt_is_delivered_cancelled_with_session_interrupt_and_never_text() {
        val gateway = gateway()
        val client = connected(client(remoteInterruptVerified = false))
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.emit(
            "approval.request",
            JSONObject().put("request_id", "prompt-1").put("command", "rm -rf /"),
        )
        val events = sink.until { it is AndroidNormalizedEvent.StructuredPrompt }

        assertEquals(
            AndroidNormalizedEvent.StructuredPrompt(binding, "approval.request", "prompt-1", false, 0),
            events.last(),
        )
        assertTrue(gateway.interruptSeen.await(5, TimeUnit.SECONDS))
        val interrupt = gateway.requests.single { it.getString("method") == "session.interrupt" }
        assertEquals("runtime-1", interrupt.getJSONObject("params").getString("session_id"))
        assertTrue(events.none { it is AndroidNormalizedEvent.ResponseTextDelta })
        assertEquals(1, gateway.count("prompt.submit"))
        client.close()
    }

    // ---- admission ---------------------------------------------------------

    @Test
    fun admission_rejects_unavailable_empty_voice_and_foreign_profile_requests() {
        val client = client()
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            client.beginTurn(typed("hi")),
        )
        gateway()
        connected(client)
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.EmptyTypedPrompt),
            client.beginTurn(typed("   ")),
        )
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            client.beginTurn(
                AndroidTurnRequest(AndroidProfile(PROFILE_ID, "Household"), AndroidTurnInput.TapToSpeak),
            ),
        )
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable),
            client.beginTurn(
                AndroidTurnRequest(AndroidProfile("someone-else", "Other"), AndroidTurnInput.Typed("hi")),
            ),
        )
        collection.set(collectionFor(profile(mode = RelayProfileMode.HomeBridge)))
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.ProfileUnavailable),
            client.beginTurn(typed("hi")),
        )
        client.close()
    }

    @Test
    fun a_second_turn_is_rejected_while_the_first_is_active() {
        gateway()
        val client = connected(client())
        accepted(client.beginTurn(typed("one")))

        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
            client.beginTurn(typed("two")),
        )
        client.close()
    }

    @Test
    fun concurrent_begin_turn_admits_exactly_one_prompt() {
        val gateway = gateway()
        val client = connected(client())
        val results = Collections.synchronizedList(mutableListOf<AndroidInitiationResult>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) { index ->
            Thread {
                start.await()
                results += client.beginTurn(typed("prompt $index"))
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(1, results.count { it is AndroidInitiationResult.Accepted })
        assertEquals(
            1,
            results.count {
                it == AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)
            },
        )
        assertEquals(1, gateway.count("prompt.submit"))
        client.close()
    }

    // ---- uncertainty -------------------------------------------------------

    @Test
    fun an_rpc_error_reply_is_a_rejection_and_never_uncertain() {
        val gateway = gateway()
        var first = true
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit" && first) {
                first = false
                socket.send(errorText(request.getString("id"), -32001, "busy"))
                true
            } else {
                false
            }
        }
        val client = connected(client())

        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.RequestRejected),
            client.beginTurn(typed("hi")),
        )

        assertFalse(client.hasUncertainTurn())
        assertFalse(client.hasActiveTurn())
        assertTrue(client.beginTurn(typed("hi again")) is AndroidInitiationResult.Accepted)
        client.close()
    }

    @Test
    fun a_reply_timeout_is_uncertain_and_blocks_the_next_prompt() {
        val gateway = gateway()
        gateway.script = { _, request -> request.getString("method") == "prompt.submit" }
        val client = connected(client(requestTimeoutMillis = 250))

        val result = client.beginTurn(typed("hi"))

        assertEquals(
            AndroidHomeUnavailableReason.TransportTimeout,
            (result as AndroidInitiationResult.Uncertain).reason,
        )
        assertEquals("hi", (result.request.input as AndroidTurnInput.Typed).text)
        assertTrue(client.hasUncertainTurn())
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            client.beginTurn(typed("again")),
        )
        client.close()
    }

    @Test
    fun a_socket_lost_before_the_reply_is_uncertain() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.close(1001, "going away")
                true
            } else {
                false
            }
        }
        val client = connected(client())

        val result = client.beginTurn(typed("hi"))

        assertEquals(
            AndroidHomeUnavailableReason.TransportUnavailable,
            (result as AndroidInitiationResult.Uncertain).reason,
        )
        assertTrue(client.hasUncertainTurn())
        client.close()
    }

    @Test
    fun a_reply_without_acceptance_proof_is_uncertain_as_a_protocol_error() {
        listOf(
            JSONObject(),
            JSONObject().put("status", "weird"),
            JSONObject().put("accepted", true).put("turn_id", ""),
            JSONObject().put("accepted", "yes"),
        ).forEach { result ->
            val gateway = gateway()
            gateway.script = { socket, request ->
                if (request.getString("method") == "prompt.submit") {
                    socket.send(resultText(request.getString("id"), result))
                    true
                } else {
                    false
                }
            }
            val client = connected(client())

            val outcome = client.beginTurn(typed("hi"))

            assertEquals(
                result.toString(),
                AndroidHomeUnavailableReason.ProtocolError,
                (outcome as AndroidInitiationResult.Uncertain).reason,
            )
            assertTrue(client.hasUncertainTurn())
            client.close()
        }
    }

    @Test
    fun a_reply_carrying_result_and_error_is_a_protocol_error() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.send(
                    JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
                        .put("result", JSONObject().put("accepted", true))
                        .put("error", JSONObject().put("code", 1)).toString(),
                )
                true
            } else {
                false
            }
        }
        val client = connected(client())

        val outcome = client.beginTurn(typed("hi"))

        assertEquals(
            AndroidHomeUnavailableReason.ProtocolError,
            (outcome as AndroidInitiationResult.Uncertain).reason,
        )
        client.close()
    }

    @Test
    fun a_drop_mid_turn_is_disconnected_and_uncertainty_survives_a_successful_reconnect() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val dropped = LinkedBlockingQueue<AndroidNormalizedEvent.Disconnected>()
        client.observeConnection {
            order += "connection"
            dropped.add(it)
        }
        first.emit("message.delta", JSONObject().put("text", "partial"))

        first.close()
        val turnEvents = sink.until { it is AndroidNormalizedEvent.Disconnected }
        val lost = dropped.poll(5, TimeUnit.SECONDS)!!

        assertEquals(binding.connectionId, lost.connectionId)
        assertEquals(lost, turnEvents.last())
        val state = turnEvents.fold(AndroidTurnState(binding = binding), AndroidTurnStateReducer::reduce)
        assertEquals(AndroidTurnPhase.Disconnected, state.phase)
        assertEquals("partial", state.responseText)
        assertTrue(client.hasUncertainTurn())
        assertFalse(client.hasActiveTurn())

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        assertTrue(client.hasUncertainTurn())
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            client.beginTurn(typed("again")),
        )
        assertEquals(0, second.count("prompt.submit"))
        client.close()
    }

    @Test
    fun a_disconnect_with_no_turn_in_flight_is_not_uncertain() {
        val first = gateway()
        gateway(runtimeId = "runtime-2")
        val client = connected(client())
        val dropped = connectionEvents(client)

        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        assertFalse(client.hasUncertainTurn())
        client.close()
    }

    @Test
    fun a_non_json_frame_after_ready_drops_the_socket_and_marks_the_turn_uncertain() {
        val gateway = gateway()
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)

        gateway.send("this is not json")
        sink.until { it is AndroidNormalizedEvent.Disconnected }

        assertTrue(client.hasUncertainTurn())
        client.close()
    }

    @Test
    fun explicit_resend_preparation_never_clears_uncertainty() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.send(resultText(request.getString("id"), JSONObject()))
                true
            } else {
                false
            }
        }
        val client = connected(client())
        client.beginTurn(typed("hi"))

        client.prepareForExplicitResend()

        assertTrue(client.hasUncertainTurn())
        client.close()
    }

    // ---- new conversation --------------------------------------------------

    @Test
    fun a_new_conversation_clears_uncertainty_and_starts_a_fresh_session_on_the_open_socket() {
        val gateway = gateway()
        var uncertainOnce = true
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit" && uncertainOnce) {
                uncertainOnce = false
                socket.send(resultText(request.getString("id"), JSONObject()))
                true
            } else if (request.getString("method") == "session.create" && gateway.count("session.create") == 2) {
                socket.send(
                    resultText(
                        request.getString("id"),
                        JSONObject().put("session_id", "runtime-9").put("stored_session_id", "durable-9"),
                    ),
                )
                true
            } else {
                false
            }
        }
        val client = connected(client())
        val before = client.reconnect() as AndroidReconnectOutcome.Connected
        client.beginTurn(typed("hi"))
        assertTrue(client.hasUncertainTurn())

        assertEquals(AndroidNewConversationResult.Created, client.newConversation())

        assertFalse(client.hasUncertainTurn())
        assertEquals(2, gateway.count("session.create"))
        val after = client.reconnect() as AndroidReconnectOutcome.Connected
        assertEquals(before.connectionId, after.connectionId)
        val binding = accepted(client.beginTurn(typed("fresh")))
        assertTrue(binding.conversationHandle.startsWith("standard-"))
        val prompt = gateway.requests.last { it.getString("method") == "prompt.submit" }
        assertEquals("runtime-9", prompt.getJSONObject("params").getString("session_id"))
        client.close()
    }

    @Test
    fun a_failed_new_conversation_leaves_uncertainty_and_the_session_as_they_were() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            when {
                request.getString("method") == "prompt.submit" -> {
                    socket.send(resultText(request.getString("id"), JSONObject()))
                    true
                }
                request.getString("method") == "session.create" && gateway.count("session.create") == 2 -> {
                    socket.send(errorText(request.getString("id"), -32000, "no"))
                    true
                }
                else -> false
            }
        }
        val client = connected(client())
        client.beginTurn(typed("hi"))
        assertTrue(client.hasUncertainTurn())

        assertEquals(
            AndroidNewConversationResult.Failed(AndroidHomeUnavailableReason.RequestRejected),
            client.newConversation(),
        )

        assertTrue(client.hasUncertainTurn())
        assertEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            client.beginTurn(typed("again")),
        )
        client.close()
    }

    @Test
    fun a_new_conversation_with_a_refused_upgrade_preserves_the_same_identity_session() {
        val first = gateway()
        server.enqueue(MockResponse().setResponseCode(503))
        val second = gateway(runtimeId = "runtime-2")
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        client.observeTurn(binding) {}
        val dropped = connectionEvents(client)
        first.close()
        assertNotNull(dropped.poll(5, TimeUnit.SECONDS))
        assertTrue(client.hasUncertainTurn())

        assertEquals(
            AndroidNewConversationResult.Failed(AndroidHomeUnavailableReason.TransportUnavailable),
            client.newConversation(),
        )

        assertTrue(client.hasUncertainTurn())
        val resumed = client.reconnect() as AndroidReconnectOutcome.Connected
        assertFalse("the held durable session survived the failed attempt", resumed.sessionStartedFresh)
        assertEquals(listOf("session.resume"), second.methods())
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain),
            client.beginTurn(typed("again")),
        )
        client.close()
    }

    @Test
    fun a_new_conversation_opens_the_socket_when_it_is_down_and_creates_rather_than_resumes() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2", durableId = "durable-2")
        val client = connected(client())
        val dropped = connectionEvents(client)
        first.close()
        val lost = dropped.poll(5, TimeUnit.SECONDS)!!

        assertEquals(AndroidNewConversationResult.Created, client.newConversation())

        assertEquals(listOf("session.create"), second.methods())
        val connected = client.reconnect() as AndroidReconnectOutcome.Connected
        assertNotEquals(lost.connectionId, connected.connectionId)
        assertFalse(connected.sessionStartedFresh)
        assertEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
        client.close()
    }

    @Test
    fun a_new_conversation_that_opens_the_socket_and_then_fails_leaves_the_client_down() {
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        second.script = { socket, request ->
            socket.send(errorText(request.getString("id"), -32000, "no"))
            true
        }
        val client = connected(client())
        val dropped = connectionEvents(client)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        assertEquals(
            AndroidNewConversationResult.Failed(AndroidHomeUnavailableReason.RequestRejected),
            client.newConversation(),
        )

        assertFalse(client.supportsInterrupt())
        assertNotEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
        client.close()
    }

    // ---- stop and finishing ------------------------------------------------

    @Test
    fun an_unverified_stop_settles_locally_never_sends_interrupt_and_blocks_until_hermes_ends_the_turn() {
        val gateway = gateway()
        val client = connected(client(remoteInterruptVerified = false))
        val binding = accepted(client.beginTurn(typed("hi")))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)
        val finishing = LinkedBlockingQueue<Boolean>()
        client.observeFinishing { finishing.add(it) }
        assertTrue(client.supportsInterrupt())

        assertTrue(client.interruptTurn(binding))

        assertEquals(true, finishing.poll(5, TimeUnit.SECONDS))
        assertTrue(client.isFinishingPreviousResponse())
        assertFalse(client.hasActiveTurn())
        val events = sink.until { it is AndroidNormalizedEvent.TurnInterrupted }
        assertEquals(AndroidNormalizedEvent.TurnInterrupted(binding, "stopped"), events.last())
        assertEquals(
            AndroidTurnPhase.Interrupted,
            events.fold(AndroidTurnState(binding = binding), AndroidTurnStateReducer::reduce).phase,
        )
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.PreviousResponseFinishing),
            client.beginTurn(typed("again")),
        )
        assertFalse("a second stop is not an active turn", client.interruptTurn(binding))

        gateway.emit("message.delta", JSONObject().put("text", "late"))
        gateway.emit("message.complete")
        assertEquals(false, finishing.poll(5, TimeUnit.SECONDS))
        assertFalse(client.isFinishingPreviousResponse())
        assertTrue(sink.drainNow().isEmpty())

        assertTrue(client.beginTurn(typed("next")) is AndroidInitiationResult.Accepted)
        assertEquals(
            listOf("session.create", "prompt.submit", "prompt.submit"),
            gateway.methods(),
        )
        client.close()
    }

    @Test
    fun a_verified_stop_also_sends_session_interrupt_for_the_runtime_session() {
        val gateway = gateway()
        val client = connected(client(remoteInterruptVerified = true))
        val binding = accepted(client.beginTurn(typed("hi")))
        client.observeTurn(binding) {}

        assertTrue(client.interruptTurn(binding))

        assertTrue(gateway.interruptSeen.await(5, TimeUnit.SECONDS))
        val interrupt = gateway.requests.single { it.getString("method") == "session.interrupt" }
        assertEquals(
            JSONObject().put("session_id", "runtime-1").toString(),
            interrupt.getJSONObject("params").toString(),
        )
        assertTrue(client.isFinishingPreviousResponse())
        client.close()
    }

    @Test
    fun a_rejected_remote_interrupt_leaves_the_client_finishing() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "session.interrupt") {
                gateway.interruptSeen.countDown()
                socket.send(errorText(request.getString("id"), -32000, "not supported"))
                true
            } else {
                false
            }
        }
        val client = connected(client(remoteInterruptVerified = true))
        val binding = accepted(client.beginTurn(typed("hi")))

        assertTrue(client.interruptTurn(binding))
        assertTrue(gateway.interruptSeen.await(5, TimeUnit.SECONDS))

        assertTrue(client.isFinishingPreviousResponse())
        client.close()
    }

    @Test
    fun finishing_survives_a_reconnect_and_only_a_new_conversation_or_a_turn_end_clears_it() {
        val first = gateway()
        gateway(runtimeId = "runtime-2")
        gateway(runtimeId = "runtime-3", durableId = "durable-3")
        val client = connected(client(remoteInterruptVerified = false))
        val binding = accepted(client.beginTurn(typed("hi")))
        client.observeTurn(binding) {}
        val finishing = LinkedBlockingQueue<Boolean>()
        client.observeFinishing { finishing.add(it) }
        val dropped = connectionEvents(client)
        client.interruptTurn(binding)
        finishing.poll(5, TimeUnit.SECONDS)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        assertTrue(client.isFinishingPreviousResponse())
        assertEquals(
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.PreviousResponseFinishing),
            client.beginTurn(typed("again")),
        )

        assertEquals(AndroidNewConversationResult.Created, client.newConversation())
        assertEquals(false, finishing.poll(5, TimeUnit.SECONDS))
        assertFalse(client.isFinishingPreviousResponse())
        client.close()
    }

    // ---- snapshot ----------------------------------------------------------

    @Test
    fun the_snapshot_reports_mode_authorization_and_capabilities() {
        collection.set(RelayProfileCollection())
        assertEquals(AndroidAuthorizationState.NotConfigured, client().snapshot().authorizationState)
        assertNull(client().snapshot().mode)

        collection.set(collectionFor(profile(mode = RelayProfileMode.HomeBridge)))
        val home = client().snapshot()
        assertEquals(AndroidAuthorizationState.NotConfigured, home.authorizationState)
        assertNull(home.selectedProfile)
        assertEquals(RelayProfileMode.HomeBridge, home.mode)

        collection.set(collectionFor(profile()))
        val gateway = gateway()
        val client = client()
        val verifying = client.snapshot()
        assertEquals(AndroidAuthorizationState.Verifying, verifying.authorizationState)
        assertEquals(RelayProfileMode.Standard, verifying.mode)
        assertEquals(AndroidProfile(PROFILE_ID, "Household", null), verifying.selectedProfile)

        client.reconnect()
        val verified = client.snapshot()
        assertEquals(AndroidAuthorizationState.Verified, verified.authorizationState)
        assertNull(verified.unavailableReason)
        assertNull(verified.route)
        assertEquals(AndroidHomeCapabilities(), verified.capabilities)
        assertFalse(verified.capabilities.audio)
        assertFalse(verified.capabilities.interrupt)

        val dropped = connectionEvents(client)
        gateway.close()
        dropped.poll(5, TimeUnit.SECONDS)
        val lost = client.snapshot()
        assertEquals(AndroidAuthorizationState.Unavailable, lost.authorizationState)
        assertEquals(AndroidHomeUnavailableReason.TransportUnavailable, lost.unavailableReason)
        client.close()
    }

    // ---- teardown ----------------------------------------------------------

    @Test
    fun end_session_closes_the_socket_without_a_disconnect_event_and_resumes_the_held_session() {
        gateway()
        val second = gateway(runtimeId = "runtime-2")
        val client = connected(client())
        val binding = accepted(client.beginTurn(typed("hi")))
        client.observeTurn(binding) {}
        val dropped = connectionEvents(client)

        client.endSession()

        assertFalse(client.supportsInterrupt())
        assertFalse(client.hasActiveTurn())
        assertTrue(dropped.isEmpty())
        val resumed = client.reconnect() as AndroidReconnectOutcome.Connected
        assertFalse(resumed.sessionStartedFresh)
        assertEquals(listOf("session.resume"), second.methods())
        client.close()
    }

    @Test
    fun end_session_does_not_clear_an_uncertain_turn_but_close_drops_everything() {
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.send(resultText(request.getString("id"), JSONObject()))
                true
            } else {
                false
            }
        }
        gateway(runtimeId = "runtime-2")
        val client = connected(client())
        client.beginTurn(typed("hi"))
        assertTrue(client.hasUncertainTurn())

        client.endSession()
        assertTrue(client.hasUncertainTurn())

        client.close()
        assertFalse(client.hasUncertainTurn())
        assertFalse(client.isFinishingPreviousResponse())
    }

    // ---- journal -----------------------------------------------------------

    @Test
    fun the_journal_records_connection_request_stop_and_conversation_decisions_content_free() {
        val journal = RecordingJournal()
        val first = gateway()
        val second = gateway(runtimeId = "runtime-2")
        val client = connected(client(journal = journal, remoteInterruptVerified = false))
        val binding = accepted(client.beginTurn(typed(PROMPT_TEXT)))
        val sink = Sink()
        client.observeTurn(binding, sink::accept)
        first.emit("message.delta", JSONObject().put("text", REPLY_TEXT))
        first.emit("message.complete")
        sink.until { it is AndroidNormalizedEvent.TurnCompleted }
        val stopBinding = accepted(client.beginTurn(typed(PROMPT_TEXT)))
        client.observeTurn(stopBinding) {}
        client.interruptTurn(stopBinding)
        val dropped = connectionEvents(client)
        first.close()
        dropped.poll(5, TimeUnit.SECONDS)
        client.reconnect()
        client.newConversation()
        client.close()

        val lines = journal.lines
        assertTrue(lines.toString(), lines.any { it.startsWith("standard connect result=connected reason=none duration_ms=") })
        assertTrue(lines.toString(), lines.any { it.startsWith("standard connect result=resumed reason=none duration_ms=") })
        assertTrue(lines.toString(), lines.any { it.startsWith("standard request completed method=prompt.submit duration_ms=") })
        assertTrue(lines.contains("standard turn terminal outcome=completed"))
        assertTrue(lines.contains("standard stop local=true remote=not_verified"))
        assertTrue(lines.contains("standard finishing state=true"))
        assertTrue(lines.contains("standard transport lost"))
        assertTrue(lines.contains("standard new_conversation result=created reason=none"))
        assertTrue(lines.contains("standard finishing state=false"))
        assertEquals(0, second.count("prompt.submit"))
        assertContentFree(lines)
    }

    @Test
    fun an_uncertain_prompt_and_a_failed_connect_are_journaled_by_enum_only() {
        val journal = RecordingJournal()
        val gateway = gateway()
        gateway.script = { socket, request ->
            if (request.getString("method") == "prompt.submit") {
                socket.send(resultText(request.getString("id"), JSONObject()))
                true
            } else {
                false
            }
        }
        val client = connected(client(journal = journal))
        client.beginTurn(typed(PROMPT_TEXT))
        collection.set(collectionFor(profile(endpoint = "wss://localhost:1/api/ws")))
        client.endSession()
        client.reconnect()
        client.close()

        val lines = journal.lines
        assertTrue(
            lines.toString(),
            lines.any {
                it.startsWith("standard request failed method=prompt.submit reason=ProtocolError uncertain=true duration_ms=")
            },
        )
        assertTrue(
            lines.toString(),
            lines.any { it.startsWith("standard connect result=failed reason=TransportUnavailable duration_ms=") },
        )
        assertContentFree(lines)
    }

    // ---- checker -----------------------------------------------------------

    @Test
    fun the_checker_verifies_a_gateway_with_session_create() {
        val gateway = gateway()

        val result = checker().check(endpoint(), "default", TOKEN)

        assertEquals(StandardCheckResult.Verified, result)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(TOKEN, request.requestUrl!!.queryParameter("token"))
        assertEquals("default", request.requestUrl!!.queryParameter("profile"))
        assertNull(request.getHeader("Authorization"))
        assertEquals(listOf("session.create"), gateway.methods())
    }

    @Test
    fun the_checker_maps_each_failure_without_the_token_or_url() {
        val results = mutableListOf<Pair<StandardCheckResult, AndroidHomeUnavailableReason>>()

        server.enqueue(MockResponse().setResponseCode(403))
        results += checker().check(endpoint(), "default", TOKEN) to AndroidHomeUnavailableReason.Unauthorized

        results += checker().check("wss://localhost:1/api/ws", "default", TOKEN) to
            AndroidHomeUnavailableReason.TransportUnavailable

        results += OkHttpStandardConnectionChecker(OkHttpClient(), 5_000, 5_000)
            .check(endpoint(), "default", TOKEN) to AndroidHomeUnavailableReason.TransportUnavailable

        gateway(sendReadyOnOpen = false)
        results += checker(readyTimeoutMillis = 250).check(endpoint(), "default", TOKEN) to
            AndroidHomeUnavailableReason.TransportTimeout

        gateway().script = { socket, request ->
            socket.send(errorText(request.getString("id"), -32000, "nope"))
            true
        }
        results += checker().check(endpoint(), "default", TOKEN) to AndroidHomeUnavailableReason.RequestRejected

        gateway().script = { socket, request ->
            socket.send(errorText(request.getString("id"), 401, "unauthorized"))
            true
        }
        results += checker().check(endpoint(), "default", TOKEN) to AndroidHomeUnavailableReason.Unauthorized

        listOf(
            JSONObject(),
            JSONObject().put("session_id", "a").put("runtime_session_id", "b"),
            JSONObject().put("session_id", "a").put("stored_session_id", "x").put("session_key", "y"),
            JSONObject().put("session_id", "a").put("profile", "work"),
            JSONObject().put("session_id", " "),
        ).forEach { reply ->
            gateway().script = { socket, request ->
                socket.send(resultText(request.getString("id"), reply))
                true
            }
            results += checker().check(endpoint(), "default", TOKEN) to
                AndroidHomeUnavailableReason.ProtocolError
        }

        results.forEach { (actual, reason) ->
            assertEquals(StandardCheckResult.Failed(reason), actual)
            assertFalse(actual.toString().contains(TOKEN))
            assertFalse(actual.toString().contains("localhost"))
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun assertContentFree(lines: List<String>) {
        val text = lines.joinToString("\n")
        listOf(
            TOKEN, "wss://", "https://", "localhost", "runtime-", "durable-", "standard-",
            PROMPT_TEXT, REPLY_TEXT, "turn-1",
        ).forEach { forbidden ->
            assertFalse("a journal line leaked $forbidden: $text", text.contains(forbidden))
        }
    }

    private fun accepted(result: AndroidInitiationResult): AndroidTurnBinding {
        assertTrue(result.toString(), result is AndroidInitiationResult.Accepted)
        return (result as AndroidInitiationResult.Accepted).binding
    }

    @Test
    fun same_id_endpoint_edit_rejects_old_prompts_and_reconnects_without_resuming() {
        assertIdentityChangeStartsFresh(profile(endpoint = endpoint() + "/other"))
    }

    @Test
    fun same_id_hermes_profile_edit_rejects_old_prompts_and_reconnects_without_resuming() {
        assertIdentityChangeStartsFresh(profile().copy(hermesProfile = "other"))
    }

    private fun assertIdentityChangeStartsFresh(selected: RelayProfile) {
        for (state in listOf("idle", "finishing", "uncertain")) {
            collection.set(collectionFor(profile()))
            val first = gateway()
            val second = gateway(runtimeId = "runtime-2", durableId = "durable-2")
            val client = connected(client())
            val firstUpgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/ws", firstUpgrade.requestUrl!!.encodedPath)
            var oldHandle: String? = null
            if (state != "idle") {
                val accepted = client.beginTurn(typed("old")) as AndroidInitiationResult.Accepted
                oldHandle = accepted.binding.conversationHandle
                if (state == "finishing") {
                    assertTrue(client.interruptTurn(accepted.binding))
                    assertTrue(client.isFinishingPreviousResponse())
                } else {
                    val dropped = connectionEvents(client)
                    first.close()
                    assertNotNull(dropped.poll(5, TimeUnit.SECONDS))
                    assertTrue(client.hasUncertainTurn())
                }
            }
            collection.set(collectionFor(selected))
            assertNotEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
            assertEquals(
                AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
                client.beginTurn(typed("must not reach the old socket")),
            )

            val outcome = client.reconnect() as AndroidReconnectOutcome.Connected
            assertTrue(outcome.sessionStartedFresh)
            assertFalse(client.hasUncertainTurn())
            assertFalse(client.isFinishingPreviousResponse())
            assertEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
            assertEquals(listOf("session.create"), second.methods())
            assertFalse(second.requests.single().getJSONObject("params").has("session_id"))
            val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(if (selected.endpoint == endpoint()) "/api/ws" else "/api/ws/other",
                upgrade.requestUrl!!.encodedPath)
            assertEquals(selected.hermesProfile, upgrade.requestUrl!!.queryParameter("profile"))
            val accepted = client.beginTurn(typed("new")) as AndroidInitiationResult.Accepted
            assertNotEquals(oldHandle, accepted.binding.conversationHandle)
            assertEquals("runtime-2", second.requests.last().getJSONObject("params").getString("session_id"))
            assertEquals(if (state == "idle") 0 else 1, first.count("prompt.submit"))
            client.close()
        }
    }

    @Test
    fun identity_edit_while_waiting_for_ready_prevents_session_request() {
        for (changed in listOf(profile(endpoint = endpoint() + "/other"), profile().copy(hermesProfile = "other"))) {
            collection.set(collectionFor(profile()))
            val first = gateway(sendReadyOnOpen = false)
            val client = client()
            val outcome = AtomicReference<AndroidReconnectOutcome>()
            val done = CountDownLatch(1)
            Thread {
                outcome.set(client.reconnect())
                done.countDown()
            }.start()
            assertTrue(first.opened.await(5, TimeUnit.SECONDS))
            collection.set(collectionFor(changed))
            first.sendReady()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(outcome.get() is AndroidReconnectOutcome.Retryable)
            assertTrue(first.requests.isEmpty())
            assertNotEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
            val second = gateway(runtimeId = "runtime-2", durableId = "durable-2")
            assertTrue((client.reconnect() as AndroidReconnectOutcome.Connected).sessionStartedFresh)
            assertEquals(listOf("session.create"), second.methods())
            client.close()
        }
    }

    @Test
    fun identity_edit_while_session_reply_is_pending_prevents_stale_commit() {
        for (changed in listOf(profile(endpoint = endpoint() + "/other"), profile().copy(hermesProfile = "other"))) {
            collection.set(collectionFor(profile()))
            val first = gateway()
            val pending = LinkedBlockingQueue<JSONObject>()
            first.script = { _, request -> pending.add(request); true }
            val client = client()
            val outcome = AtomicReference<AndroidReconnectOutcome>()
            val done = CountDownLatch(1)
            Thread {
                outcome.set(client.reconnect())
                done.countDown()
            }.start()
            val request = pending.poll(5, TimeUnit.SECONDS)!!
            collection.set(collectionFor(changed))
            first.send(resultText(request.getString("id"),
                JSONObject().put("session_id", "old-runtime").put("stored_session_id", "old-durable")))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(outcome.get() is AndroidReconnectOutcome.Retryable)
            assertNotEquals(AndroidAuthorizationState.Verified, client.snapshot().authorizationState)
            assertEquals(
                AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
                client.beginTurn(typed("must not use the pending session")),
            )
            val second = gateway(runtimeId = "runtime-2", durableId = "durable-2")
            assertTrue((client.reconnect() as AndroidReconnectOutcome.Connected).sessionStartedFresh)
            assertEquals(listOf("session.create"), second.methods())
            assertFalse(second.requests.single().getJSONObject("params").has("session_id"))
            client.close()
        }
    }

    private fun typed(text: String) =
        AndroidTurnRequest(AndroidProfile(PROFILE_ID, "Household"), AndroidTurnInput.Typed(text))

    private fun connected(client: OkHttpStandardSessionClient): OkHttpStandardSessionClient {
        val outcome = client.reconnect()
        assertTrue(outcome.toString(), outcome is AndroidReconnectOutcome.Connected)
        return client
    }

    private fun connectionEvents(client: OkHttpStandardSessionClient) =
        LinkedBlockingQueue<AndroidNormalizedEvent.Disconnected>().also { queue ->
            client.observeConnection { queue.add(it) }
        }

    private fun gateway(
        runtimeId: String = "runtime-1",
        durableId: String? = "durable-1",
        sendReadyOnOpen: Boolean = true,
    ): Gateway = Gateway(runtimeId, durableId, sendReadyOnOpen).also {
        server.enqueue(MockResponse().withWebSocketUpgrade(it))
    }

    private fun endpoint(): String =
        server.url("/api/ws").toString().replaceFirst("https://", "wss://")

    private fun profile(
        endpoint: String = endpoint(),
        mode: RelayProfileMode = RelayProfileMode.Standard,
    ) = RelayProfile(
        id = PROFILE_ID,
        endpoint = endpoint,
        clientId = "android",
        deviceId = "",
        displayName = "Household",
        mode = mode,
        hermesProfile = "default",
    )

    private fun collectionFor(profile: RelayProfile) =
        RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()

    private fun checker(readyTimeoutMillis: Long = 5_000) =
        OkHttpStandardConnectionChecker(httpClient(), readyTimeoutMillis, 5_000)

    private fun client(
        credentials: RelayCredentialStore = InMemoryRelayCredentialStore(
            standardCredentials = mapOf(PROFILE_ID to TOKEN),
        ),
        readyTimeoutMillis: Long = 5_000,
        requestTimeoutMillis: Long = 5_000,
        remoteInterruptVerified: Boolean = false,
        journal: DiagnosticsJournal = DiagnosticsJournal.None,
    ) = OkHttpStandardSessionClient(
        collection = { collection.get() },
        credentials = credentials,
        httpClient = httpClient(),
        readyTimeoutMillis = readyTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
        remoteInterruptVerified = remoteInterruptVerified,
        journal = journal,
    )

    private class Sink {
        private val queue = LinkedBlockingQueue<AndroidNormalizedEvent>()

        fun accept(event: AndroidNormalizedEvent) {
            queue.add(event)
        }

        /** Everything up to and including the first event matching [predicate]. */
        fun until(predicate: (AndroidNormalizedEvent) -> Boolean): List<AndroidNormalizedEvent> {
            val collected = mutableListOf<AndroidNormalizedEvent>()
            while (true) {
                val event = queue.poll(5, TimeUnit.SECONDS)
                    ?: throw AssertionError("no matching event; saw $collected")
                collected += event
                if (predicate(event)) return collected
            }
        }

        fun drainNow(): List<AndroidNormalizedEvent> =
            generateSequence { queue.poll() }.toList()
    }

    /** One scripted gateway socket. */
    private class Gateway(
        val runtimeId: String,
        val durableId: String?,
        private val sendReadyOnOpen: Boolean,
    ) : WebSocketListener() {
        val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val opened = CountDownLatch(1)
        val interruptSeen = CountDownLatch(1)

        @Volatile
        var socket: WebSocket? = null

        @Volatile
        var sentBeforeReady = false

        @Volatile
        private var readySent = false

        /** Return true to take over a request; false for the default behaviour. */
        @Volatile
        var script: (WebSocket, JSONObject) -> Boolean = { _, _ -> false }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
            if (sendReadyOnOpen) sendReady()
        }

        fun sendReady() {
            readySent = true
            send(eventText(StandardWire.READY, JSONObject().put("skin", "default"), null))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!readySent) sentBeforeReady = true
            val request = JSONObject(text)
            requests += request
            if (request.getString("method") == "session.interrupt") interruptSeen.countDown()
            if (script(webSocket, request)) return
            val id = request.getString("id")
            val params = request.getJSONObject("params")
            when (request.getString("method")) {
                "session.create" -> webSocket.send(
                    resultText(id, session(durableId)),
                )
                "session.resume" -> webSocket.send(
                    resultText(id, session(params.getString("session_id"))),
                )
                "prompt.submit" -> webSocket.send(
                    resultText(id, JSONObject().put("accepted", true).put("turn_id", "turn-1")),
                )
                "session.interrupt" -> webSocket.send(
                    resultText(id, JSONObject().put("accepted", true)),
                )
            }
        }

        private fun session(durable: String?): JSONObject {
            val result = JSONObject().put("session_id", runtimeId)
            if (durable != null) result.put("stored_session_id", durable)
            return result
        }

        fun send(text: String) {
            socket!!.send(text)
        }

        fun sendBinary(bytes: ByteString) {
            socket!!.send(bytes)
        }

        fun close() {
            socket!!.close(1001, "going away")
        }

        fun methods(): List<String> =
            synchronized(requests) { requests.map { it.getString("method") } }

        fun count(method: String): Int = methods().count { it == method }

        fun emit(
            type: String,
            payload: JSONObject = JSONObject(),
            session: String? = runtimeId,
            turn: String? = "turn-1",
            seq: Int? = null,
        ) = send(eventText(type, payload, session, turn, seq))
    }

    private companion object {
        const val PROFILE_ID = "standard-profile-1"
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
            seq: Int? = null,
        ): String {
            val params = JSONObject().put("type", type).put("payload", payload)
            if (session != null) params.put("session_id", session)
            if (turn != null) params.put("turn_id", turn)
            if (seq != null) params.put("seq", seq)
            return JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "event")
                .put("params", params)
                .toString()
        }
    }
}
