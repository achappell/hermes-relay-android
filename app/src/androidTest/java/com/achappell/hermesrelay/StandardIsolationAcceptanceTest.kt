package com.achappell.hermesrelay

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disposable TLS-peer acceptance, not evidence of a deployed Home or upstream release.
 * UI actions drive the production runtime, both real transports, setup connection
 * checks, file stores and Android Keystore. Nothing reads the Application's stores.
 */
@RunWith(AndroidJUnit4::class)
class StandardIsolationAcceptanceTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val notifications = turnFixtureNotifications()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var preferencesName: String
    private lateinit var credentials: KeystoreRelayCredentialStore
    private lateinit var profiles: FileRelayProfileStore
    private lateinit var history: FileAndroidHistoryStore
    private lateinit var restart: FileStandardRestartStore
    private lateinit var configuration: RelayConfigurationController
    private lateinit var http: OkHttpClient
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var runtime: HomeRuntime
    private var port: ModeAwareClientPort? = null
    private val peers = mutableListOf<TlsPeer>()
    private val showScreen = mutableStateOf(true)

    @Before
    fun setUp() {
        val id = UUID.randomUUID().toString()
        directory = File(context.filesDir, "standard-isolation-$id").apply { mkdirs() }
        preferencesName = "standard-isolation-$id"
        credentials = freshCredentials()
        profiles = FileRelayProfileStore(File(directory, "profiles.json"))
        history = FileAndroidHistoryStore(directory)
        restart = FileStandardRestartStore(File(directory, "restart"))
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    @After
    fun tearDown() {
        if (::runtime.isInitialized) {
            composeRule.runOnIdle { showScreen.value = false }
            composeRule.waitForIdle()
        }
        port?.close()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
        peers.forEach { it.server.shutdown() }
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }

    @Test
    fun endpoint_editor_requires_a_fresh_token_and_keeps_history_and_sessions_at_their_origin() {
        val originalPeer = peer("original", ORIGINAL_TOKEN)
        val newPeer = peer("new-endpoint", NEW_TOKEN)
        val original = standardProfile("standard", originalPeer)
        launch(listOf(original))
        awaitReady(original.id)
        submit(originalPeer, "original endpoint private prompt")
        val originalHistory = freshHistory(original)

        editEndpoint(newPeer.endpoint)
        composeRule.onNodeWithTag("android_standard_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("android_standard_setup_result")
            .assertTextEquals(context.getString(R.string.android_standard_invalid))
        screenshot("standard-isolation-endpoint-required.png")
        assertEquals("blank editor must not authenticate with the saved token", 0, newPeer.upgrades.size)
        assertEquals(original, profiles.load().selected)
        assertEquals(ORIGINAL_TOKEN, freshCredentials().readStandardCredential(original.id))
        assertEquals(originalHistory, freshHistory(original))

        saveEditor(NEW_TOKEN)
        val changed = original.copy(endpoint = newPeer.endpoint)
        awaitReady(changed.id, changed.endpoint)
        assertEquals(changed, profiles.load().selected)
        assertEquals(NEW_TOKEN, freshCredentials().readStandardCredential(changed.id))
        assertNotEquals(original.historyKey, changed.historyKey)
        assertTrue(freshHistory(changed).entries.isEmpty())
        assertEquals(originalHistory, freshHistory(original))
        assertHistoryEmpty()
        submit(newPeer, "new endpoint only prompt")
        assertHistoryContains("new endpoint only prompt", "original endpoint private prompt", newPeer, originalPeer)

        // Both the setup check and the actual runtime must create a new session.
        assertEquals(listOf("session.create", "session.create", "prompt.submit"), newPeer.methods())
        assertPeerIsolation(newPeer, NEW_TOKEN, listOf(ORIGINAL_TOKEN, "original-runtime", "original-stored", "original endpoint private prompt") + originalPeer.replies())
        assertFreshCreates(newPeer)
        assertFalse(restart.hasUnresolvedTurn(changed))

        // Deliberately return through the same editor: the original server's
        // retained history is viewable, but the new server's transcript is not sent.
        editEndpoint(originalPeer.endpoint)
        saveEditor(ORIGINAL_TOKEN)
        awaitReady(original.id, original.endpoint)
        assertHistoryContains("original endpoint private prompt", "new endpoint only prompt", originalPeer, newPeer)
        submit(originalPeer, "original endpoint remains usable")
        assertEquals(2, originalPeer.submissions.get())
        assertPeerIsolation(originalPeer, ORIGINAL_TOKEN, listOf(NEW_TOKEN, "new-endpoint-runtime", "new-endpoint-stored", "new endpoint only prompt") + newPeer.replies())
        assertFreshCreates(originalPeer)
        assertEquals(listOf("new endpoint only prompt", newPeer.reply(1)), freshHistory(changed).entries.map { it.text })
    }

    @Test
    fun rejected_standard_auth_cannot_fall_back_to_seeded_home_or_legacy_credentials() {
        val originalPeer = peer("original", ORIGINAL_TOKEN)
        val rejectingPeer = peer("rejected", "not-the-entered-token")
        val original = standardProfile("standard", originalPeer)
        val rejected = standardProfile("rejected", rejectingPeer)
        assertTrue(credentials.putStandardCredential(rejected.id, REJECTED_TOKEN))
        listOf(original.id, rejected.id).forEach { id ->
            assertTrue(credentials.putHomeCredential(id, HOME_TOKEN))
            assertTrue(credentials.putRollbackCredential(id, LEGACY_TOKEN))
        }
        launch(listOf(original, rejected))
        awaitReady(original.id)
        submit(originalPeer, "retained original prompt")
        val before = freshHistory(original)

        editEndpoint(rejectingPeer.endpoint)
        saveEditor(REJECTED_TOKEN, succeeds = false)
        composeRule.onNodeWithTag("android_standard_setup_result")
            .assertTextEquals(context.getString(R.string.android_standard_failed))
        assertEquals(original.id, profiles.load().selectedId)
        assertEquals(original.endpoint, profiles.load().selected?.endpoint)
        assertEquals(ORIGINAL_TOKEN, freshCredentials().readStandardCredential(original.id))
        assertEquals(before, freshHistory(original))
        composeRule.onNodeWithTag("android_settings_done").performClick()
        submit(originalPeer, "usable after rejected editor check")

        selectProfile(1)
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                (runtime.recoveryState.connection as? AndroidConnectionState.Failed)?.reasonCode ==
                    AndroidHomeUnavailableReason.Unauthorized
            }
        }
        composeRule.onNodeWithText("Start typed turn").performScrollTo().assertIsNotEnabled()
        assertTrue(freshHistory(rejected).entries.isEmpty())
        assertEquals(0, rejectingPeer.frames.size)
        assertEquals(0, rejectingPeer.submissions.get())
        assertEquals("one failed setup check and one failed runtime connection", 2, rejectingPeer.upgrades.size)
        assertPeerIsolation(rejectingPeer, REJECTED_TOKEN, listOf(HOME_TOKEN, LEGACY_TOKEN, ORIGINAL_TOKEN, "original-stored", "retained original prompt") + originalPeer.replies())
        assertEquals(HOME_TOKEN, freshCredentials().readHomeCredential(rejected.id))
        assertEquals(LEGACY_TOKEN, freshCredentials().readRollbackCredential(rejected.id))

        selectProfile(0)
        awaitReady(original.id)
        submit(originalPeer, "usable after rejected runtime authorization")
        assertEquals(3, originalPeer.submissions.get())
        assertEquals(ORIGINAL_TOKEN, freshCredentials().readStandardCredential(original.id))
        assertEquals(0, rejectingPeer.submissions.get())
    }

    @Test
    fun explicit_home_and_standard_mode_changes_send_no_foreign_session_or_transcript() {
        val standardPeer = peer("standard", ORIGINAL_TOKEN)
        val homePeer = peer("home", HOME_TOKEN, home = true)
        val standard = standardProfile("standard", standardPeer)
        val home = RelayProfile(
            id = "home", endpoint = homePeer.endpoint, clientId = "fixture-client",
            deviceId = "fixture-device", displayName = "Disposable Home",
            homeBinding = RelayHomeBinding(homePeer.endpoint, HOME_HANDLE),
            mode = RelayConnectionMode.HomeBridge,
        )
        assertEquals(null, RelayProfileValidator.validateApprovedHomeRoute(homePeer.endpoint))
        assertTrue(credentials.putHomeCredential(home.id, HOME_TOKEN))
        assertTrue(credentials.putRollbackCredential(home.id, LEGACY_TOKEN))
        launch(listOf(standard, home))
        awaitReady(standard.id)
        submit(standardPeer, "direct Standard private prompt")
        val standardHistory = freshHistory(standard)

        openSettings()
        screenshot("standard-isolation-mode-selection.png")
        composeRule.onAllNodesWithText("Select")[1].performScrollTo().performClick()
        composeRule.connectToReady { runtime.recoveryState.connection == AndroidConnectionState.Connected }
        awaitReady(home.id)
        assertEquals(RelayConnectionMode.HomeBridge, port?.connectionMode())
        assertHistoryEmpty()
        submit(homePeer, "Home-only private prompt")
        assertHistoryContains("Home-only private prompt", "direct Standard private prompt", homePeer, standardPeer)
        assertEquals(listOf("conversation.open", "prompt.submit"), homePeer.methods())
        val open = homePeer.frames.first().getJSONObject("params")
        assertEquals(HOME_HANDLE, open.getString("conversation_handle"))
        assertPeerIsolation(homePeer, HOME_TOKEN, listOf(ORIGINAL_TOKEN, LEGACY_TOKEN, "standard-runtime", "standard-stored", "direct Standard private prompt") + standardPeer.replies())
        assertEquals(standardHistory, freshHistory(standard))

        selectProfile(0)
        awaitReady(standard.id)
        assertHistoryContains("direct Standard private prompt", "Home-only private prompt", standardPeer, homePeer)
        submit(standardPeer, "Standard after Home switch")
        assertEquals(2, standardPeer.submissions.get())
        assertEquals(1, homePeer.submissions.get())
        assertPeerIsolation(standardPeer, ORIGINAL_TOKEN, listOf(HOME_TOKEN, LEGACY_TOKEN, HOME_HANDLE, "Home-only private prompt") + homePeer.replies())
        assertEquals(listOf("Home-only private prompt", homePeer.reply(1)), freshHistory(home).entries.map { it.text })
        assertEquals(HOME_TOKEN, freshCredentials().readHomeCredential(home.id))
        assertEquals(ORIGINAL_TOKEN, freshCredentials().readStandardCredential(standard.id))
    }

    private fun launch(saved: List<RelayProfile>) {
        assertTrue(credentials.putStandardCredential(saved.first().id, ORIGINAL_TOKEN))
        assertTrue(profiles.save(RelayProfileCollection(saved, saved.first().id)))
        configuration = RelayConfigurationController(
            profiles = profiles, credentials = credentials, history = history,
            standardRestartStore = restart, standardSetupHttpClient = http,
        )
        val client = ModeAwareClientPort(
            collection = { configuration.collection },
            home = OkHttpRelaySessionClient(
                collection = { configuration.collection }, credentials = credentials,
                httpClient = http, helloTimeoutMillis = 10_000, requestTimeoutMillis = 10_000,
            ),
            standard = OkHttpStandardSessionClient(
                collection = { configuration.collection }, credentials = credentials,
                restartStore = restart, httpClient = http, requestTimeoutMillis = 10_000,
            ),
        )
        port = client
        composeRule.setContent {
            if (showScreen.value) {
                HermesRelayTheme {
                    AndroidClientScreen(
                        clientPort = client, configuration = configuration, historyStore = history,
                        onRuntime = { runtime = it },
                    )
                }
            }
        }
    }

    private fun awaitReady(id: String, endpoint: String? = null) {
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                configuration.collection.selectedId == id &&
                    (endpoint == null || configuration.collection.selected?.endpoint == endpoint) &&
                    runtime.recoveryState.connection == AndroidConnectionState.Connected &&
                    runtime.canReconfigure && runtime.recorder?.profileId == configuration.collection.selected?.historyKey
            }
        }
    }

    private fun submit(peer: TlsPeer, prompt: String) {
        val expected = peer.submissions.get() + 1
        val reply = peer.reply(expected)
        composeRule.submitTypedPrompt(prompt) { peer.submissions.get() == expected }
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                val entries = runtime.recorder?.history?.entries.orEmpty()
                runtime.canReconfigure &&
                    entries.any { it.role == AndroidTranscriptRole.User && it.text == prompt } &&
                    entries.any { it.role == AndroidTranscriptRole.Assistant && it.text == reply } &&
                    runtime.recorder?.history?.draft.orEmpty().isEmpty() &&
                    peer.audioRequests.size == (if (peer.home) 0 else expected)
            }
        }
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertIsDisplayed().assertTextEquals(reply)
        val saved = freshHistory(requireNotNull(configuration.collection.selected))
        assertTrue(saved.entries.any { it.role == AndroidTranscriptRole.Assistant && it.text == reply })
        peers.filter { it !== peer }.flatMap { it.replies() }.forEach { foreignReply ->
            composeRule.onNodeWithText(foreignReply).assertDoesNotExist()
            assertFalse("foreign assistant reply in retained history", saved.entries.any { it.text == foreignReply })
        }
        if (!peer.home) {
            // Standard always attempts TTS after text. A deliberate HTTP 404
            // settles that attempt; this fixture does not accept voice output.
            composeRule.scrollToConversationTag("android_audio_unavailable")
            composeRule.onNodeWithTag("android_audio_unavailable").assertIsDisplayed()
            assertEquals(expected, peer.audioRequests.size)
        }
    }

    private fun openSettings() {
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithText("Configure relay").performClick()
        composeRule.onNodeWithTag("android_settings_screen").assertIsDisplayed()
    }

    private fun selectProfile(index: Int) {
        openSettings()
        composeRule.onAllNodesWithText("Select")[index].performScrollTo().performClick()
    }

    private fun editEndpoint(endpoint: String) {
        openSettings()
        composeRule.onAllNodesWithText(context.getString(R.string.android_standard_edit))[0]
            .performScrollTo().performClick()
        composeRule.onNodeWithTag("android_standard_endpoint").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("android_standard_endpoint").performTextInput(endpoint)
        closeSoftKeyboard()
    }

    private fun saveEditor(token: String, succeeds: Boolean = true) {
        composeRule.onNodeWithTag("android_standard_token").performScrollTo().performTextInput(token)
        closeSoftKeyboard()
        composeRule.onNodeWithTag("android_standard_save").performScrollTo().performClick()
        composeRule.waitUntil(10_000) {
            if (succeeds) {
                composeRule.onAllNodesWithTag("android_settings_screen").fetchSemanticsNodes().isEmpty()
            } else {
                composeRule.onAllNodesWithTag("android_standard_setup_result").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun assertHistoryEmpty() {
        openHistory()
        composeRule.onNodeWithTag("android_history_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("android_history_close").performClick()
        peers.flatMap { it.replies() }.forEach { reply ->
            composeRule.onNodeWithText(reply).assertDoesNotExist()
        }
    }

    private fun assertHistoryContains(present: String, absent: String, presentPeer: TlsPeer, absentPeer: TlsPeer) {
        openHistory()
        composeRule.onNodeWithText(present).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(absent).assertDoesNotExist()
        presentPeer.replies().forEach { reply ->
            composeRule.onNode(hasTestTag("android_history_entry") and hasText(reply))
                .performScrollTo().assertIsDisplayed()
        }
        absentPeer.replies().forEach { reply -> composeRule.onNodeWithText(reply).assertDoesNotExist() }
        composeRule.onNodeWithTag("android_history_close").performClick()
        absentPeer.replies().forEach { reply -> composeRule.onNodeWithText(reply).assertDoesNotExist() }
    }

    private fun openHistory() {
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithTag("android_menu_history").performClick()
    }

    private fun freshCredentials() = KeystoreRelayCredentialStore(
        context, context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
    )

    private fun freshHistory(profile: RelayProfile) = FileAndroidHistoryStore(directory).load(profile.historyKey)

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun standardProfile(id: String, peer: TlsPeer) = RelayProfile(
        id = id, endpoint = peer.endpoint, clientId = "", deviceId = "", displayName = "Disposable Standard",
        mode = RelayConnectionMode.Standard, hermesProfile = "default",
    )

    private fun peer(name: String, token: String, home: Boolean = false) =
        TlsPeer(name, token, home).also { peers.add(it) }

    private fun assertFreshCreates(peer: TlsPeer) {
        peer.frames.filter { it.getString("method") == "session.create" }.forEach {
            val params = it.getJSONObject("params")
            assertFalse("New must not inherit a runtime reference", params.has("session_id"))
            assertFalse("New must not inherit a durable reference", params.has("stored_session_id"))
        }
    }

    private fun assertPeerIsolation(peer: TlsPeer, expectedToken: String, forbidden: List<String>) {
        assertEquals("audio is an explicit HTTP 404, separate from control upgrades",
            if (peer.home) 0 else peer.submissions.get(), peer.audioRequests.size)
        peer.audioRequests.forEach { request ->
            assertEquals("/api/audio/speak-stream", request.requestUrl?.encodedPath)
            assertEquals(expectedToken, request.requestUrl?.queryParameter("token"))
            assertEquals("default", request.requestUrl?.queryParameter("profile"))
            assertEquals(null, request.getHeader("Authorization"))
            val wire = request.requestLine + request.headers.toString()
            forbidden.forEach { assertFalse("foreign data in audio request", wire.contains(it)) }
        }
        peer.upgrades.forEach { request ->
            if (peer.home) {
                assertEquals("Device $expectedToken", request.getHeader("Authorization"))
                assertEquals("/api/v1/bridge/ws", request.path)
            } else {
                assertEquals(expectedToken, request.requestUrl?.queryParameter("token"))
                assertEquals(null, request.getHeader("Authorization"))
            }
            val wire = request.requestLine + request.headers.toString()
            forbidden.forEach { assertFalse("foreign data in upgrade", wire.contains(it)) }
        }
        val frames = peer.frames.joinToString("\n") { it.toString() }
        forbidden.forEach { assertFalse("foreign data in RPC", frames.contains(it)) }
    }

    /** Wire shapes are the existing Standard transport and HomeControlLeaseSmokeTest fixtures. */
    private inner class TlsPeer(val name: String, val token: String, val home: Boolean) {
        val server = MockWebServer()
        val upgrades = CopyOnWriteArrayList<RecordedRequest>()
        val audioRequests = CopyOnWriteArrayList<RecordedRequest>()
        val frames = CopyOnWriteArrayList<JSONObject>()
        val submissions = AtomicInteger()
        private val sessions = AtomicInteger()
        val endpoint: String

        init {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/api/audio/speak-stream") {
                        audioRequests.add(request)
                        return MockResponse().setResponseCode(404)
                    }
                    upgrades.add(request)
                    val authorized = if (home) request.getHeader("Authorization") == "Device $token"
                        else request.requestUrl?.queryParameter("token") == token
                    if (!authorized) return MockResponse().setResponseCode(401)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private val session = "$name-runtime-${sessions.incrementAndGet()}"
                        private var sequence = 0
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val requestFrame = JSONObject(text)
                            frames.add(requestFrame)
                            when (requestFrame.getString("method")) {
                                "session.create" -> result(webSocket, requestFrame, JSONObject()
                                    .put("session_id", session).put("stored_session_id", "$name-stored-${sessions.get()}"))
                                "session.resume" -> result(webSocket, requestFrame, JSONObject().put("session_id", session))
                                "conversation.open", "conversation.reconnect" -> result(webSocket, requestFrame, JSONObject()
                                    .put("schema", 1).put("status", "ready").put("unresolved_turn", false)
                                    .put("conversation_handle", HOME_HANDLE)
                                    .put("route", JSONObject().put("class", "home").put("id", "fixture-route"))
                                    .put("capabilities", JSONObject().put("heartbeat", true).put("timing", "absent")
                                        .put("commands", JSONArray()).put("interrupt", true).put("audio", false)
                                        .put("turn_keepalive", true)))
                                "prompt.submit" -> {
                                    val submission = submissions.incrementAndGet()
                                    val turn = "$name-turn-$submission"
                                    result(webSocket, requestFrame, JSONObject().put("schema", 1)
                                        .put("status", "submitted").put("conversation_handle", HOME_HANDLE).put("turn_id", turn))
                                    val params = if (home) JSONObject().put("schema", 1)
                                        .put("conversation_handle", HOME_HANDLE).put("turn_id", turn)
                                        .put("correlation_id", "fixture-correlation")
                                        .put("event", JSONObject().put("type", "message.complete")
                                            .put("payload", JSONObject().put("rendered", reply(submission)).put("status", "complete")))
                                    else JSONObject().put("session_id", session).put("seq", ++sequence)
                                        .put("type", "message.complete").put("payload", JSONObject()
                                            .put("rendered", reply(submission)).put("status", "complete"))
                                    webSocket.send(JSONObject().put("schema", 1).put("jsonrpc", "2.0")
                                        .put("method", "event").put("params", params).toString())
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                        }
                    })
                }
            }
            server.start()
            // Home approves a host/root or its exact bridge path, not /api/ws.
            endpoint = server.url(if (home) "/" else "/api/ws").toString().replaceFirst("https://", "wss://")
        }

        fun methods() = frames.map { it.getString("method") }

        fun reply(submission: Int) = "$name assistant reply $submission"

        fun replies() = (1..submissions.get()).map(::reply)

        private fun result(socket: WebSocket, request: JSONObject, body: JSONObject) {
            socket.send(JSONObject().put("schema", 1).put("jsonrpc", "2.0")
                .put("id", request.getString("id")).put("result", body).toString())
        }
    }

    private companion object {
        const val ORIGINAL_TOKEN = "disposable-original-standard-token"
        const val NEW_TOKEN = "disposable-new-standard-token"
        const val REJECTED_TOKEN = "disposable-rejected-standard-token"
        const val HOME_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val LEGACY_TOKEN = "disposable-legacy-rollback-token"
        const val HOME_HANDLE = "disposable-home-conversation"
    }
}
