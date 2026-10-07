package com.achappell.hermesrelay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `ANDROID-VOICE-04`: the interrupt control announces its mode and action, is spent after one tap, and reports an unconfirmed interrupt. */
@RunWith(AndroidJUnit4::class)
class InterruptControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Port : AndroidClientPort {
        private val profile = AndroidProfile("amanda-laptop", "Amanda")
        var interrupts = 0
        var lastBinding: AndroidTurnBinding? = null
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

        override fun hasActiveTurn() = true

        override fun supportsInterrupt() = true

        override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
            interrupts += 1
            return true
        }

        fun emit(event: AndroidNormalizedEvent) {
            listener?.invoke(event)
        }
    }

    private fun startTurn(port: Port, speech: FakeSpeechInput = FakeSpeechInput()) {
        composeRule.setContent {
            HermesRelayTheme { AndroidClientScreen(clientPort = port, speechInput = speech) }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Tell me a story")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.runOnIdle { port.emit(AndroidNormalizedEvent.Thinking(port.lastBinding!!)) }
        composeRule.waitForIdle()
    }

    @Test
    fun the_control_announces_its_mode_and_its_action() {
        val port = Port()
        startTurn(port)

        val config = composeRule.onNodeWithTag("android_interrupt").performScrollTo()
            .fetchSemanticsNode().config
        assertEquals(
            context.getString(R.string.android_turn_phase_thinking),
            config[SemanticsProperties.StateDescription],
        )
        assertEquals(
            context.getString(R.string.android_interrupt),
            config[SemanticsActions.OnClick].label,
        )
        assertEquals(
            context.getString(R.string.android_interrupt),
            config[SemanticsProperties.Text].joinToString("") { it.text },
        )
    }

    @Test
    fun one_tap_spends_the_control_and_sends_one_interrupt() {
        val port = Port()
        startTurn(port)

        composeRule.onNodeWithTag("android_interrupt").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("android_interrupt").assertIsNotEnabled()
        assertEquals(1, port.interrupts)
    }

    @Test
    fun no_terminal_within_two_seconds_shows_the_unconfirmed_state_and_opens_the_microphone_once() {
        val port = Port()
        val speech = FakeSpeechInput()
        startTurn(port, speech)

        composeRule.onNodeWithTag("android_interrupt").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 6_000) {
            composeRule.onAllNodesWithTag("android_interrupt_unconfirmed").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("android_interrupt_unconfirmed").performScrollTo().assertIsDisplayed()
        assertEquals(1, port.interrupts)
        assertEquals("interrupt-and-listen did not start capture exactly once", 1, speech.startCount)
    }
}
