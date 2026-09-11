package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shows_honest_bootstrap_state() {
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

        composeRule
            .onNodeWithText("Turn accepted for Amanda. Waiting for normalized Session events.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Start typed turn").assertIsNotEnabled()
        assertEquals(1, port.requests.size)
        assertEquals("amanda", port.requests.single().profile.id)
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
}
