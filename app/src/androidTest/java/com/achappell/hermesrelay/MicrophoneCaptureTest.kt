package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrophoneCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun the_platform_input_detects_the_installed_recognizer_without_claiming_permission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = PlatformSpeechInput(context)

        // RECORD_AUDIO is not granted to the test process, and the manifest
        // <queries> element must still let the recognizer be seen.
        assertEquals(AndroidSpeechAuthorization.NotDetermined, input.authorization())
    }

    @Test
    fun a_missing_permission_offers_to_grant_rather_than_opening_the_microphone() {
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.NotDetermined)
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                // AndroidClientScreen scrolls internally; wrapping it in another
                // scrolling column measures it with infinite height.
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.onNodeWithTag("android_connect").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Allow microphone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_capture_block").performScrollTo().assertIsDisplayed()
        assertEquals("the microphone was opened without permission", 0, speech.startCount)
    }

    @Test
    fun capture_is_refused_before_a_session_exists() {
        val speech = FakeSpeechInput()
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                // AndroidClientScreen scrolls internally; wrapping it in another
                // scrolling column measures it with infinite height.
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        // No connect click: there is no live Session yet.
        composeRule.onNodeWithTag("android_capture_block").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Connect to Hermes before speaking.")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, speech.startCount)
    }

    @Test
    fun an_authorized_capture_listens_and_submits_its_final_transcript() {
        val speech = FakeSpeechInput()
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                // AndroidClientScreen scrolls internally; wrapping it in another
                // scrolling column measures it with infinite height.
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.onNodeWithTag("android_connect").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Started) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_capture_state").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Listening…").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("android_capture_stop").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(speech.stopped)

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Final("check the weather")) }
        composeRule.waitForIdle()

        assertEquals(1, port.requests.size)
        assertEquals(
            AndroidTurnInput.Typed("check the weather"),
            port.requests.single().input,
        )
        composeRule.onNodeWithText("Turn accepted for Amanda. Waiting for normalized Session events.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun typed_turns_still_work_when_no_recognizer_exists() {
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.Unavailable)
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                // AndroidClientScreen scrolls internally; wrapping it in another
                // scrolling column measures it with infinite height.
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.onNodeWithTag("android_connect").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("typed instead")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(AndroidTurnInput.Typed("typed instead"), port.requests.single().input)
        assertEquals(0, speech.startCount)
    }

    private class ConnectedFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda-laptop", "Amanda")
        val requests = mutableListOf<AndroidTurnRequest>()

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return AndroidInitiationResult.Accepted(
                AndroidTurnBinding(request.profile.id, "session-1", "turn-1"),
            )
        }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")
    }
}
