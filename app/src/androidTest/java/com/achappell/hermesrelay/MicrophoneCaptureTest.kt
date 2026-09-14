package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
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

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
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
        composeRule.onNodeWithText("Connect to the Home bridge before speaking.")
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

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Started) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_capture_state").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_voice_activity").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("Turn accepted for Amanda. Waiting for Home events.")
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

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("typed instead")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        assertEquals(AndroidTurnInput.Typed("typed instead"), port.requests.single().input)
        assertEquals(0, speech.startCount)
    }

    @Test
    fun the_live_transcript_is_shown_while_speaking_and_cleared_on_cancel() {
        val speech = FakeSpeechInput()
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Started) }
        composeRule.waitForIdle()

        // Nothing provisional until the recognizer produces words.
        composeRule.onAllNodesWithTag("android_capture_partial").assertCountEquals(0)

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Partial("check the")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("check the").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Partial("check the weather")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("check the weather").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("You (transcribing)").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
        composeRule.waitForIdle()

        // A cancelled utterance leaves nothing behind and sends nothing.
        composeRule.onAllNodesWithTag("android_capture_partial").assertCountEquals(0)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun hands_free_continues_after_a_completed_turn_and_stop_ends_it() {
        val speech = FakeSpeechInput()
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_hands_free").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Started) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_hands_free_active").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { speech.emit(AndroidSpeechEvent.Final("check the weather")) }
        composeRule.waitForIdle()
        assertEquals(1, port.requests.size)

        // Completing the turn reopens the window without another tap.
        val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")
        composeRule.runOnIdle {
            port.emit(AndroidNormalizedEvent.AudioStarted(binding))
            port.emit(AndroidNormalizedEvent.AudioEnded(binding))
            port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        }
        composeRule.waitForIdle()
        assertEquals("the window did not reopen", 2, speech.startCount)

        // Exactly "stop" ends it, and is never sent as a turn.
        composeRule.runOnIdle {
            speech.emit(AndroidSpeechEvent.Started)
            speech.emit(AndroidSpeechEvent.Final("stop"))
        }
        composeRule.waitForIdle()

        assertEquals("stop was submitted to Hermes", 1, port.requests.size)
        composeRule.onNodeWithTag("android_hands_free_exit").performScrollTo().assertIsDisplayed()
    }

    private class ConnectedFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda-laptop", "Amanda")
        val requests = mutableListOf<AndroidTurnRequest>()
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

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
}
