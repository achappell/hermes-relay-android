package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `ANDROID-VOICE-01`: a missing speech pack offers one explicit action, never a silent online fallback. */
@RunWith(AndroidJUnit4::class)
class SpeechLanguagePackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val granted = object : RuntimePermissionSource {
        override fun isGranted() = true
        override fun hasAsked() = true
        override fun shouldShowRationale() = false
        override fun markAsked() = Unit
    }

    private fun textOf(tag: String): String = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun failWithMissingPack(speech: FakeSpeechInput) {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = ConnectedPort(),
                    speechInput = speech,
                    microphonePermissionSource = granted,
                )
            }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.LanguageUnavailable))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_failure_shows_the_language_pack_copy_and_one_online_action() {
        val speech = FakeSpeechInput()

        failWithMissingPack(speech)

        composeRule.onNodeWithTag("android_capture_failed").performScrollTo().assertIsDisplayed()
        assertEquals(
            context.getString(R.string.android_capture_failed_language),
            textOf("android_capture_failed"),
        )
        composeRule.onNodeWithTag("android_capture_retry_online").performScrollTo().assertIsDisplayed()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("android_capture_retry_online").fetchSemanticsNodes().size,
        )
        assertFalse("network recognition was on before the user chose it", speech.networkRecognitionAllowed)
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("android_network_recognition_indicator")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun choosing_the_online_retry_listens_again_and_shows_a_visible_indicator() {
        val speech = FakeSpeechInput()
        failWithMissingPack(speech)

        composeRule.onNodeWithTag("android_capture_retry_online").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(speech.networkRecognitionAllowed)
        assertEquals("the retry did not reopen the recognizer", 2, speech.startCount)
        composeRule.onNodeWithTag("android_network_recognition_indicator")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(
            context.getString(R.string.android_network_recognition_on),
            textOf("android_network_recognition_indicator"),
        )
    }

    @Test
    fun the_indicator_offers_a_way_back_to_on_device_only() {
        val speech = FakeSpeechInput()
        failWithMissingPack(speech)
        composeRule.onNodeWithTag("android_capture_retry_online").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("android_network_recognition_off").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertFalse(speech.networkRecognitionAllowed)
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("android_network_recognition_indicator")
                .fetchSemanticsNodes().size,
        )
    }

    private class ConnectedPort : AndroidClientPort {
        private val profile = AndroidProfile("amanda-laptop", "Amanda")

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
            AndroidInitiationResult.Accepted(
                AndroidTurnBinding(request.profile.id, "session-1", "turn-1"),
            )

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation = AndroidTurnObservation { }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")
    }
}
