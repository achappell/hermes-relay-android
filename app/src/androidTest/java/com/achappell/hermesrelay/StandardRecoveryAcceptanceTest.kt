package com.achappell.hermesrelay

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native UI/runtime/transport acceptance against isolated TLS peers, not a live
 * Hermes or physical voice acceptance. Only speech recognition is synthetic;
 * every connection, session operation and submission uses the production client.
 */
@RunWith(AndroidJUnit4::class)
class StandardRecoveryAcceptanceTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val notifications = turnFixtureNotifications()

    private lateinit var directory: File
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var profile: RelayProfile
    private lateinit var restartStore: FileStandardRestartStore
    private lateinit var historyStore: FileAndroidHistoryStore
    private lateinit var client: OkHttpStandardSessionClient
    private lateinit var runtime: HomeRuntime
    private lateinit var configuration: RelayConfigurationController
    private val requests = CopyOnWriteArrayList<PeerRequest>()
    private val rejectNextCreate = AtomicBoolean(false)
    private val markerAtSubmit = CopyOnWriteArrayList<Boolean>()
    private val showScreen = mutableStateOf(true)
    private val speechInput = FakeSpeechInput()
    private val grantedMicrophone = object : RuntimePermissionSource {
        override fun isGranted() = true
        override fun hasAsked() = true
        override fun shouldShowRationale() = false
        override fun markAsked() = Unit
    }

    @Before
    fun setUp() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "standard-recovery-acceptance-${System.nanoTime()}",
        ).apply { check(mkdirs()) }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            start()
        }
        http = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
        profile = RelayProfile(
            id = "recovery-fixture",
            endpoint = server.url("/api/ws").toString().replaceFirst("https://", "wss://"),
            clientId = "",
            deviceId = "",
            displayName = "Isolated Standard recovery",
            mode = RelayConnectionMode.Standard,
        )
        val profiles = InMemoryRelayProfileStore(RelayProfileCollection(listOf(profile), profile.id))
        val credentials = InMemoryRelayCredentialStore().apply {
            putStandardCredential(profile.id, "synthetic-recovery-credential")
        }
        restartStore = FileStandardRestartStore(File(directory, "restart"))
        historyStore = FileAndroidHistoryStore(directory)
        configuration = RelayConfigurationController(profiles = profiles, credentials = credentials)
        client = OkHttpStandardSessionClient(
            collection = { profiles.load() },
            credentials = credentials,
            restartStore = restartStore,
            httpClient = http,
            requestTimeoutMillis = 10_000,
        )
    }

    @After
    fun tearDown() {
        // Dispose the test-scoped runtime before closing its transport or files.
        if (::runtime.isInitialized) {
            composeRule.runOnIdle { showScreen.value = false }
            composeRule.waitForIdle()
        }
        if (::client.isInitialized) client.close()
        if (::http.isInitialized) {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
        if (::server.isInitialized) server.shutdown()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun accepted_midturn_loss_never_replays_failed_new_stays_blocked_successful_new_allows_one_send() {
        val initial = enqueuePeer(1)
        val resumed = enqueuePeer(2)
        val fresh = enqueuePeer(3)
        composeRule.setContent {
            if (showScreen.value) {
                HermesRelayTheme {
                    AndroidClientScreen(
                        clientPort = client,
                        configuration = configuration,
                        historyStore = historyStore,
                        speechInput = speechInput,
                        microphonePermissionSource = grantedMicrophone,
                        onRuntime = { runtime = it },
                    )
                }
            }
        }
        waitForConnected()
        assertMethods("1:session.create")
        composeRule.submitTypedPrompt(FIRST_PROMPT) { runtime.hasAcceptedTurn && !runtime.initiationInFlight }
        assertMethods("1:session.create", "1:prompt.submit")
        assertEquals(listOf(true), markerAtSubmit.toList())
        initial.event("message.delta", JSONObject().put("text", PARTIAL_RESPONSE))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle { runtime.turnState.responseText == PARTIAL_RESPONSE }
        }
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertTextEquals(PARTIAL_RESPONSE)

        // The remote peer closes with an internal error after UI acceptance,
        // without a terminal turn event. This is a controlled close, not an
        // abrupt socket failure: MockWebServer's server WebSocket has no Call
        // for cancel(). No platform connectivity or production endpoint changes.
        assertTrue("remote peer must accept the error close", initial.socket.get().close(1011, "Controlled mid-turn loss"))
        waitForConnected(expectedResume = true)
        assertMethods("1:session.create", "1:prompt.submit", "2:session.resume")
        assertEquals("durable-1", requests.last().frame.getJSONObject("params").getString("session_id"))
        assertUncertaintyAndBlockedSending()
        assertHistory(
            AndroidTranscriptRole.User to FIRST_PROMPT,
            AndroidTranscriptRole.Assistant to PARTIAL_RESPONSE,
        )

        // Draft editing remains usable even though neither typing nor reconnect
        // is permission to submit an uncertain turn again.
        composeRule.onNodeWithTag("android_typed_prompt").performScrollTo()
            .assertIsEnabled().performTextInput(NEXT_PROMPT)
        closeSoftKeyboard()
        composeRule.onNodeWithText("Start typed turn").performScrollTo().assertIsNotEnabled()
        assertEquals(NEXT_PROMPT, persistedHistory().draft)

        rejectNextCreate.set(true)
        clickNew()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle {
                !runtime.initiationInFlight && runtime.recoveryState.connection is AndroidConnectionState.Failed
            }
        }
        assertTrue("failed New must close its unusable transport", resumed.ended.await(10, TimeUnit.SECONDS))
        assertMethods("1:session.create", "1:prompt.submit", "2:session.resume", "2:session.create")
        assertUncertaintyAndBlockedSending()
        composeRule.runOnIdle {
            val failure = runtime.recoveryState.connection as AndroidConnectionState.Failed
            assertEquals(AndroidHomeUnavailableReason.RequestRejected, failure.reasonCode)
        }
        assertHistory(
            AndroidTranscriptRole.User to FIRST_PROMPT,
            AndroidTranscriptRole.Assistant to PARTIAL_RESPONSE,
        )
        assertEquals(NEXT_PROMPT, persistedHistory().draft)
        captureCheckpoint("standard-recovery-failed-new.png")

        // The rejected session.create above is an ordered outgoing-frame
        // barrier on the resumed socket; its close is the final receive barrier.
        // Thus the exact request list proves zero replay, not a timed absence.
        clickNew()
        waitForConnected()
        assertMethods(
            "1:session.create", "1:prompt.submit", "2:session.resume", "2:session.create", "3:session.create",
        )
        composeRule.runOnIdle {
            assertFalse(client.hasUncertainTurn())
            assertFalse(runtime.recoveryState.hasUnconfirmedTurn)
            assertFalse(runtime.recoveryState.unresolvedHomeTurn)
            assertTrue(runtime.canReconfigure)
        }
        assertFalse(FileStandardRestartStore(File(directory, "restart")).hasUnresolvedTurn(profile))
        assertHistory(
            AndroidTranscriptRole.User to FIRST_PROMPT,
            AndroidTranscriptRole.Assistant to PARTIAL_RESPONSE,
            AndroidTranscriptRole.Divider to "",
        )
        composeRule.onNodeWithTag("android_typed_prompt").performScrollTo().assertIsEnabled()
            .assertTextContains(NEXT_PROMPT)
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled()
        captureCheckpoint("standard-recovery-deliberate-new-ready.png")
        closeSoftKeyboard()
        composeRule.onNodeWithText("Start typed turn").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle { runtime.hasAcceptedTurn && !runtime.initiationInFlight }
        }
        assertMethods(
            "1:session.create", "1:prompt.submit", "2:session.resume", "2:session.create", "3:session.create",
            "3:prompt.submit",
        )
        val submissions = requests.filter { it.frame.getString("method") == "prompt.submit" }
        assertEquals(listOf(FIRST_PROMPT, NEXT_PROMPT), submissions.map { it.frame.getJSONObject("params").getString("text") })
        assertEquals(listOf("runtime-1", "runtime-3"), submissions.map { it.frame.getJSONObject("params").getString("session_id") })
        assertEquals(listOf(true, true), markerAtSubmit.toList())
        assertHistory(
            AndroidTranscriptRole.User to FIRST_PROMPT,
            AndroidTranscriptRole.Assistant to PARTIAL_RESPONSE,
            AndroidTranscriptRole.Divider to "",
            AndroidTranscriptRole.User to NEXT_PROMPT,
        )

        // Empty terminal text intentionally avoids opening a TTS connection;
        // this acceptance concerns control-transport recovery, not voice output.
        fresh.event("message.complete", JSONObject())
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.runOnIdle { !runtime.hasAcceptedTurn && !client.hasActiveTurn() }
        }
        assertFalse(FileStandardRestartStore(File(directory, "restart")).hasUnresolvedTurn(profile))
        assertEquals(2, requests.count { it.frame.getString("method") == "prompt.submit" })
        assertEquals(3, server.requestCount)
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled()
    }

    private fun waitForConnected(expectedResume: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.runOnIdle {
                ::runtime.isInitialized && !runtime.initiationInFlight &&
                    runtime.recoveryState.connection == AndroidConnectionState.Connected &&
                    client.hasLiveTransport() && (!expectedResume || requests.any { it.frame.optString("method") == "session.resume" })
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertUncertaintyAndBlockedSending() {
        composeRule.runOnIdle {
            assertTrue(client.hasUncertainTurn())
            assertTrue(runtime.recoveryState.hasUnconfirmedTurn)
            assertFalse(runtime.canReconfigure)
        }
        assertTrue(FileStandardRestartStore(File(directory, "restart")).hasUnresolvedTurn(profile))
        composeRule.onNodeWithText("Start typed turn").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("android_typed_prompt").performScrollTo().assertIsEnabled()
    }

    private fun clickNew() {
        composeRule.scrollToConversationTag("android_standard_new_conversation")
        composeRule.onNodeWithTag("android_standard_new_conversation").assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun persistedHistory() = FileAndroidHistoryStore(directory).load(profile.historyKey)

    private fun assertHistory(vararg expected: Pair<AndroidTranscriptRole, String>) {
        composeRule.runOnIdle {
            assertEquals(expected.toList(), persistedHistory().entries.map {
                it.role to if (it.role == AndroidTranscriptRole.Divider) "" else it.text
            })
        }
    }

    private fun assertMethods(vararg expected: String) {
        assertEquals(expected.toList(), requests.map { "${it.peer}:${it.frame.getString("method")}" })
    }

    private fun captureCheckpoint(filename: String) {
        composeRule.scrollToConversationTag("android_doorway_state")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, filename).outputStream().use { output ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun enqueuePeer(ordinal: Int): Peer {
        val peer = Peer(ordinal)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer.socket.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                requests.add(PeerRequest(ordinal, frame))
                when (frame.getString("method")) {
                    "session.create" -> if (rejectNextCreate.getAndSet(false)) {
                        webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", frame.getString("id"))
                            .put("error", JSONObject().put("code", 4007).put("message", "Controlled session refusal")).toString())
                    } else reply(webSocket, frame, JSONObject().put("session_id", "runtime-$ordinal")
                        .put("stored_session_id", "durable-$ordinal"))
                    "session.resume" -> reply(webSocket, frame, JSONObject().put("session_id", "runtime-$ordinal"))
                    "prompt.submit" -> {
                        markerAtSubmit.add(FileStandardRestartStore(File(directory, "restart")).hasUnresolvedTurn(profile))
                        reply(webSocket, frame, JSONObject().put("status", "submitted"))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                peer.ended.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                peer.ended.countDown()
            }
        }))
        return peer
    }

    private fun reply(socket: WebSocket, request: JSONObject, result: JSONObject) {
        check(socket.send(JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id")).put("result", result).toString()))
    }

    private data class PeerRequest(val peer: Int, val frame: JSONObject)

    private class Peer(private val ordinal: Int) {
        val socket = AtomicReference<WebSocket>()
        val ended = CountDownLatch(1)

        fun event(type: String, payload: JSONObject) {
            assertTrue(socket.get().send(JSONObject().put("jsonrpc", "2.0").put("method", "event")
                .put("params", JSONObject().put("session_id", "runtime-$ordinal").put("seq", 1)
                    .put("type", type).put("payload", payload)).toString()))
        }
    }

    private companion object {
        const val FIRST_PROMPT = "Synthetic accepted request before transport loss"
        const val PARTIAL_RESPONSE = "Synthetic partial response retained across recovery"
        const val NEXT_PROMPT = "One deliberate request after successful New"
    }
}
