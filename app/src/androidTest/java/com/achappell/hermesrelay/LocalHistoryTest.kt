package com.achappell.hermesrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalHistoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var directory: File

    private fun openHistoryFromConversationMenu() {
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithTag("android_menu_history").performClick()
        composeRule.waitForIdle()
    }

    @Before
    fun setUp() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "history-test-${System.nanoTime()}",
        )
        directory.mkdirs()
    }

    @Test
    fun a_conversation_survives_a_new_store_over_the_same_directory() {
        val first = FileAndroidHistoryStore(directory)
        AndroidHistoryRecorder(first).apply {
            open("profile-1")
            recordUserTurn("check the weather")
            recordResponse("Rain later")
        }

        // A fresh store stands in for the next app launch.
        val reopened = FileAndroidHistoryStore(directory).load("profile-1")

        assertEquals(2, reopened.entries.size)
        assertEquals("check the weather", reopened.entries.first().text)
        assertEquals("Rain later", reopened.entries.last().text)
    }

    @Test
    fun each_profile_gets_its_own_file_and_deleting_one_leaves_the_other() {
        val store = FileAndroidHistoryStore(directory)
        AndroidHistoryRecorder(store).apply {
            open("profile-1")
            recordUserTurn("first profile")
        }
        AndroidHistoryRecorder(store).apply {
            open("profile-2")
            recordUserTurn("second profile")
        }

        assertEquals(2, directory.listFiles()?.count { it.name.startsWith("history-") })

        store.delete("profile-1")

        assertTrue(store.load("profile-1").entries.isEmpty())
        assertEquals("second profile", store.load("profile-2").entries.single().text)
    }

    @Test
    fun a_completed_turn_is_kept_and_can_be_cleared() {
        val store = FileAndroidHistoryStore(directory)
        val port = HistoryFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = port,
                    configuration = RelayConfigurationController(
                        profiles = InMemoryRelayProfileStore(
                            RelayProfileCollection(
                                profiles = listOf(
                                    RelayProfile(
                                        id = "profile-1",
                                        endpoint = "wss://relay.example/voice-session",
                                        clientId = "amanda-laptop",
                                        deviceId = "android",
                                        displayName = "Amanda",
                                    ),
                                ),
                                selectedId = "profile-1",
                            ),
                        ),
                        credentials = InMemoryRelayCredentialStore(
                            mapOf("profile-1" to "relay-token"),
                        ),
                        history = store,
                    ),
                    historyStore = store,
                )
            }
        }

        openHistoryFromConversationMenu()
        composeRule.onNodeWithTag("android_history_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("android_history_close").performClick()
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("check the weather")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        val binding = port.lastBinding!!
        composeRule.runOnIdle {
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain later"))
            port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        }
        composeRule.waitForIdle()

        // Both sides of the exchange are kept.
        openHistoryFromConversationMenu()
        composeRule.onAllNodesWithTag("android_history_entry").assertCountEquals(2)
        assertEquals(2, store.load("profile-1").entries.size)

        composeRule.onNodeWithTag("android_history_clear").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("android_history_entry").assertCountEquals(0)
        assertTrue(store.load("profile-1").entries.isEmpty())
    }

    @Test
    fun local_history_uses_a_sheet_and_keeps_export_in_the_more_menu() {
        val store = FileAndroidHistoryStore(directory)
        AndroidHistoryRecorder(store).apply {
            open("profile-1")
            recordUserTurn("check the weather")
            recordResponse("Rain later")
        }

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = HistoryFakePort(),
                    configuration = historyConfiguration(store),
                    historyStore = store,
                )
            }
        }

        openHistoryFromConversationMenu()
        composeRule.onNodeWithTag("android_history_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("android_history_boundary").assertIsDisplayed()
        composeRule.onAllNodesWithTag("android_history_entry_meta").assertCountEquals(2)

        composeRule.onNodeWithTag("android_history_more").performClick()
        composeRule.onNodeWithText("Share as text").assertIsDisplayed()
        composeRule.onNodeWithText("Share as Markdown").assertIsDisplayed()
    }

    @Test
    fun a_submitted_prompt_can_be_recalled_without_losing_a_half_typed_one() {
        val store = FileAndroidHistoryStore(directory)
        val port = HistoryFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, historyStore = store)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("check the weather")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        val binding = port.lastBinding!!
        composeRule.runOnIdle {
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain later"))
            port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        }
        composeRule.waitForIdle()

        // Start typing something new, then recall the previous prompt.
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("half typed")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("android_prompt_previous").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("check the weather").performScrollTo().assertIsDisplayed()

        // Stepping forward restores what was being typed rather than discarding it.
        composeRule.onNodeWithTag("android_prompt_next").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("half typed").performScrollTo().assertIsDisplayed()
    }

    private class HistoryFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda-laptop", "Amanda")
        var lastBinding: AndroidTurnBinding? = null
            private set
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            val binding = AndroidTurnBinding(request.profile.id, "session-1", "turn-1")
            lastBinding = binding
            return AndroidInitiationResult.Accepted(binding)
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")

        fun emit(event: AndroidNormalizedEvent) {
            listener?.invoke(event)
        }
    }

    private fun historyConfiguration(store: AndroidHistoryStore) = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(
            RelayProfileCollection(
                profiles = listOf(
                    RelayProfile(
                        id = "profile-1",
                        endpoint = "wss://relay.example/voice-session",
                        clientId = "amanda-laptop",
                        deviceId = "android",
                        displayName = "Amanda",
                    ),
                ),
                selectedId = "profile-1",
            ),
        ),
        credentials = InMemoryRelayCredentialStore(mapOf("profile-1" to "relay-token")),
        history = store,
    )
}
