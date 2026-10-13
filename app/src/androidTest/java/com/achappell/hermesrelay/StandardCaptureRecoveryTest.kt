package com.achappell.hermesrelay

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
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
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        composeRule.waitUntil { port.listener != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { port.complete() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, speech.startCount) }
    }

    private class StandardPort(@Volatile var uncertain: Boolean) : AndroidClientPort {
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
            authorizationState = AndroidAuthorizationState.Verified,
        )
        override fun reconnect(): AndroidReconnectOutcome {
            connected = true
            return AndroidReconnectOutcome.Connected("session", unresolvedTurn = uncertain)
        }
        override fun newConversation(): AndroidReconnectOutcome {
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
