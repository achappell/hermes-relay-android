package com.achappell.hermesrelay

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardCaptureRecoveryTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )
    private val granted = object : RuntimePermissionSource {
        override fun isGranted() = true
        override fun hasAsked() = true
        override fun shouldShowRationale() = false
        override fun markAsked() = Unit
    }

    @Test
    fun new_conversation_restores_capture_without_a_connection_transition() {
        val port = StandardPort(uncertain = true)
        val speech = FakeSpeechInput()
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech, microphonePermissionSource = granted)
            }
        }
        composeRule.waitUntil { port.connected }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsNotEnabled()
        composeRule.scrollToConversationTag("android_standard_new_conversation")
        composeRule.onNodeWithTag("android_standard_new_conversation").performClick()
        composeRule.waitUntil { !port.uncertain }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, speech.startCount) }
    }

    @Test
    fun completing_a_standard_turn_restores_capture() {
        val port = StandardPort(uncertain = false)
        val speech = FakeSpeechInput()
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech, microphonePermissionSource = granted)
            }
        }
        composeRule.waitUntil { port.connected }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("synthetic turn")
        closeSoftKeyboard()
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        composeRule.waitUntil { port.listener != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { port.complete() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, speech.startCount) }
    }

    @Test
    fun rejected_authorization_offers_configuration_instead_of_futile_retry() {
        val port = StandardPort(uncertain = false, reconnectFailure = AndroidHomeUnavailableReason.Unauthorized)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = port,
                    configuration = standardConfiguration(),
                    speechInput = FakeSpeechInput(),
                    microphonePermissionSource = granted,
                )
            }
        }
        waitForFailure()
        composeRule.onNodeWithTag("android_session_status")
            .assertTextEquals(context.getString(R.string.android_connection_failed, "Synthetic refusal"))
        composeRule.onNodeWithTag("android_doorway_state_description")
            .assertTextEquals(context.getString(R.string.android_state_unavailable_authorization))
        composeRule.onNodeWithTag("android_send_blocked")
            .assertTextEquals(context.getString(R.string.android_composer_block_authorization))
        composeRule.onNodeWithTag("android_capture_block")
            .assertTextEquals(context.getString(R.string.android_capture_block_profile))
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsNotEnabled()
        composeRule.onNodeWithText("Start typed turn").assertIsNotEnabled()
        composeRule.scrollToConversationTag("android_edit_relay")
        composeRule.onNodeWithTag("android_connect").assertDoesNotExist()
        composeRule.onNodeWithTag("android_edit_relay").performClick()
        composeRule.onNodeWithTag("android_settings_screen").assertIsDisplayed()
    }

    @Test
    fun session_refusal_retains_its_reason_and_allows_a_deliberate_new_conversation() {
        val port = StandardPort(uncertain = false, reconnectFailure = AndroidHomeUnavailableReason.RequestRejected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = FakeSpeechInput(), microphonePermissionSource = granted)
            }
        }
        waitForFailure()
        composeRule.onNodeWithTag("android_doorway_state_description").assertTextContains("Synthetic refusal", substring = true)
        composeRule.onNodeWithTag("android_send_blocked")
            .assertTextEquals(context.getString(R.string.android_composer_block_disconnected))
        composeRule.onNodeWithTag("android_capture_block")
            .assertTextEquals(context.getString(R.string.android_capture_block_not_connected))
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsNotEnabled()
        composeRule.scrollToConversationTag("android_standard_new_conversation")
        composeRule.onNodeWithTag("android_connect").assertDoesNotExist()
        composeRule.onNodeWithTag("android_standard_new_conversation").assertIsEnabled().performClick()
        composeRule.waitUntil { port.connected }
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_doorway_state")
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsEnabled()
    }

    @Test
    fun failed_new_conversation_preserves_uncertainty_and_its_actual_failure_reason() {
        val port = StandardPort(uncertain = true, newConversationFailure = AndroidHomeUnavailableReason.RequestRejected)
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = FakeSpeechInput(), microphonePermissionSource = granted)
            }
        }
        composeRule.waitUntil { port.connected }
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_standard_new_conversation")
        composeRule.onNodeWithTag("android_standard_new_conversation").performClick()
        waitForFailure()
        composeRule.onNodeWithTag("android_doorway_state_description").assertTextContains("Synthetic refusal", substring = true)
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertTrue(port.uncertain)
            port.newConversationFailure = null
        }
        composeRule.scrollToConversationTag("android_standard_new_conversation")
        composeRule.onNodeWithTag("android_standard_new_conversation").performClick()
        composeRule.waitUntil { !port.uncertain }
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_doorway_state")
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsEnabled()
    }

    private fun waitForFailure() {
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("android_connection_state").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text.contains("Synthetic refusal") }
            }
        }
    }

    private fun standardConfiguration() = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(
            RelayProfileCollection(
                profiles = listOf(
                    RelayProfile(
                        id = "standard",
                        endpoint = "wss://standard.example/api/ws",
                        clientId = "",
                        deviceId = "",
                        displayName = "Standard test",
                        mode = RelayConnectionMode.Standard,
                    ),
                ),
                selectedId = "standard",
            ),
        ),
        credentials = InMemoryRelayCredentialStore().apply {
            putStandardCredential("standard", "test-only")
        },
    )

    private class StandardPort(
        @Volatile var uncertain: Boolean,
        private val reconnectFailure: AndroidHomeUnavailableReason? = null,
        @Volatile var newConversationFailure: AndroidHomeUnavailableReason? = null,
    ) : AndroidClientPort {
        @Volatile var connected = false
        @Volatile var active = false
        @Volatile var listener: ((AndroidNormalizedEvent) -> Unit)? = null
        private val binding = AndroidTurnBinding("standard", "session", "turn")
        override fun connectionMode() = RelayConnectionMode.Standard
        override fun hasLiveTransport() = connected
        override fun hasUncertainTurn() = uncertain
        override fun hasActiveTurn() = active
        override fun snapshot() = AndroidClientSnapshot(
            titleRes = R.string.android_mode_standard,
            descriptionRes = R.string.android_standard_limits,
            boundaryRes = R.string.android_standard_limits,
            selectedProfile = AndroidProfile("standard", "Standard test"),
            authorizationState = if (connected) AndroidAuthorizationState.Verified else AndroidAuthorizationState.Verifying,
        )
        override fun reconnect(): AndroidReconnectOutcome {
            reconnectFailure?.let { return AndroidReconnectOutcome.Unrecoverable("Synthetic refusal", it) }
            connected = true
            return AndroidReconnectOutcome.Connected("session", unresolvedTurn = uncertain)
        }
        override fun newConversation(): AndroidReconnectOutcome {
            newConversationFailure?.let {
                connected = false
                return AndroidReconnectOutcome.Unrecoverable("Synthetic refusal", it)
            }
            connected = true
            uncertain = false
            return AndroidReconnectOutcome.Connected("session")
        }
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            active = true
            return AndroidInitiationResult.Accepted(binding)
        }
        override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }
        fun complete() {
            active = false
            listener?.invoke(AndroidNormalizedEvent.TurnCompleted(binding))
        }
    }
}
