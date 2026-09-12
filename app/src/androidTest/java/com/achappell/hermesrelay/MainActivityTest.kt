package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_honest_bootstrap_state() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(BootstrapClientPort)
            }
        }

        composeRule.onNodeWithText("Android Client bootstrap").assertIsDisplayed()
        composeRule
            .onNodeWithText("The native Android surface is alive, but Hermes session transport is not connected yet.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Conversation, response audio, Profiles, Local History, and Device administration arrive as independently verified slices.")
            .assertIsDisplayed()
    }

    @Test
    fun authorized_typed_turn_uses_the_selected_profile_once() {
        val port = AuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Turn accepted for Amanda. Waiting for normalized Session events.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Start typed turn").assertIsNotEnabled()
        assertEquals(1, port.requests.size)
        assertEquals("amanda", port.requests.single().profile.id)
    }

    @Test
    fun renders_normalized_phases_and_one_coherent_response() {
        val port = ObservableAuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        composeRule.waitForIdle()
        val binding = port.requests.single().let { request ->
            AndroidTurnBinding(request.profile.id, "session-1", "turn-1")
        }

        port.emit(AndroidNormalizedEvent.CaptureStarted(binding))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Turn phase: Listening").performScrollTo().assertIsDisplayed()

        port.emit(AndroidNormalizedEvent.TranscriptionStarted(binding))
        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain "))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "later"))
        port.emit(AndroidNormalizedEvent.AudioBuffering(binding))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Turn phase: Buffering").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_response_text").performScrollTo().assertIsDisplayed()

        port.emit(AndroidNormalizedEvent.AudioStarted(binding))
        port.emit(AndroidNormalizedEvent.AudioChunkReceived(binding))
        port.emit(AndroidNormalizedEvent.AudioEnded(binding))
        port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Turn phase: Complete").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_response_text").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Response audio unavailable. Completed response text remains available.")
            .assertCountEquals(0)
    }

    @Test
    fun preserves_completed_text_when_normalized_audio_delivery_fails() {
        val port = ObservableAuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Tell me a story")
        composeRule.onNodeWithText("Start typed turn").performClick()
        composeRule.waitForIdle()
        val binding = AndroidTurnBinding("amanda", "session-1", "turn-1")

        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Text survives"))
        port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Turn phase: Unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_response_text").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_audio_unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Turn phase: Speaking").assertCountEquals(0)
    }

    private class AuthorizedFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda", "Amanda")
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
                AndroidTurnBinding(
                    profileId = request.profile.id,
                    sessionId = "session-1",
                    turnId = "turn-1",
                ),
            )
        }
    }

    private class ObservableAuthorizedFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda", "Amanda")
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
                AndroidTurnBinding(
                    profileId = request.profile.id,
                    sessionId = "session-1",
                    turnId = "turn-1",
                ),
            )
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation {
                if (listener === onEvent) {
                    listener = null
                }
            }
        }

        fun emit(event: AndroidNormalizedEvent) {
            listener?.invoke(event)
        }
    }
}
