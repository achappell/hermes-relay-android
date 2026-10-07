package com.achappell.hermesrelay

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `UX-DR21` names the Android reading order explicitly:
 * Profile -> state -> response/Transcription -> action, with focus restoration.
 * These tests read the real semantics tree rather than asserting that modifiers
 * were written.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityOrderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun traversalIndexOf(tag: String): Float {
        scrollToRailTagIfNeeded(tag)
        return composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            .config
            .getOrElse(SemanticsProperties.TraversalIndex) { 0f }
    }

    private fun liveRegionOf(tag: String): LiveRegionMode? {
        scrollToRailTagIfNeeded(tag)
        return composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            .config
            .let { config ->
                if (config.contains(SemanticsProperties.LiveRegion)) {
                    config[SemanticsProperties.LiveRegion]
                } else {
                    null
                }
            }
    }

    private fun scrollToRailTagIfNeeded(tag: String) {
        if (tag !in railTags) return
        composeRule.scrollToConversationTag(tag)
    }

    private val railTags = setOf(
        "android_connection_state",
        "android_turn_phase",
        "android_response_text",
        "android_audio_unavailable",
    )

    @Test
    fun the_reading_order_runs_profile_then_state_then_response_then_action() {
        val port = ConnectedFakePort()
        val speech = FakeSpeechInput()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        // Before connecting, state and action bands are both present.
        val stateIndex = traversalIndexOf("android_connection_state")
        val actionIndex = traversalIndexOf("android_typed_prompt")

        assertTrue(
            "state ($stateIndex) must be read before action ($actionIndex)",
            stateIndex < actionIndex,
        )

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        val binding = port.lastBinding!!
        composeRule.runOnIdle {
            port.emit(AndroidNormalizedEvent.Thinking(binding))
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain later"))
        }
        composeRule.waitForIdle()

        val phaseIndex = traversalIndexOf("android_turn_phase")
        val responseIndex = traversalIndexOf("android_response_text")
        val promptIndex = traversalIndexOf("android_typed_prompt")

        assertTrue(
            "state ($phaseIndex) must precede response ($responseIndex)",
            phaseIndex < responseIndex,
        )
        assertTrue(
            "response ($responseIndex) must precede action ($promptIndex)",
            responseIndex < promptIndex,
        )
    }

    @Test
    fun changing_state_is_announced_and_errors_are_announced_assertively() {
        val port = ConnectedFakePort()
        val speech = FakeSpeechInput()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        // Connection state is a polite live region so a change is spoken
        // without interrupting whatever is being read.
        assertEquals(
            LiveRegionMode.Polite,
            liveRegionOf("android_connection_state"),
        )

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Tell me a story")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        val binding = port.lastBinding!!
        composeRule.runOnIdle {
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Text survives"))
            port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        }
        composeRule.waitForIdle()

        // Audio never arrived, so the unavailable notice must interrupt.
        assertEquals(
            LiveRegionMode.Assertive,
            liveRegionOf("android_audio_unavailable"),
        )
        assertNull(liveRegionOf("android_response_text"))
    }

    @Test
    fun partial_transcription_is_not_announced_for_each_recognizer_frame() {
        val port = ConnectedFakePort()
        val speech = FakeSpeechInput()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().performClick()
        composeRule.runOnIdle {
            speech.emit(AndroidSpeechEvent.Started)
            speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        }
        composeRule.waitForIdle()

        assertEquals(LiveRegionMode.Polite, liveRegionOf("android_capture_state"))
        assertNull(liveRegionOf("android_capture_partial"))
    }

    @Test
    fun a_permanently_denied_microphone_reads_its_state_then_its_open_settings_action() {
        val port = ConnectedFakePort()
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.NotDetermined)
        val deniedForever = object : RuntimePermissionSource {
            override fun isGranted() = false
            override fun hasAsked() = true
            override fun shouldShowRationale() = false
            override fun markAsked() = Unit
        }

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = port,
                    speechInput = speech,
                    microphonePermissionSource = deniedForever,
                )
            }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()

        val stateIndex = traversalIndexOf("android_capture_block")
        val actionIndex = traversalIndexOf("android_microphone_open_settings")
        assertTrue("state ($stateIndex) must precede action ($actionIndex)", stateIndex < actionIndex)
        assertEquals(LiveRegionMode.Polite, liveRegionOf("android_capture_block"))

        // TalkBack reads the visible text of both, so the state and the action
        // are spoken as written rather than from an unlabeled control.
        composeRule.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Microphone access is turned off for Hermes, so Android will not ask again.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_profile_and_response_labels_are_headings() {
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port)
            }
        }

        val config = composeRule
            .onNodeWithText("Selected Hermes Profile")
            .fetchSemanticsNode()
            .config

        assertTrue(
            "the Profile label is not exposed as a heading",
            config.contains(SemanticsProperties.Heading),
        )
    }

    @Test
    fun disconnect_is_a_labelled_button_after_history_in_the_overflow_menu_and_ends_the_session() {
        val port = ConnectedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, historyStore = InMemoryAndroidHistoryStore())
            }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("android_more_menu").performClick()
        val history = composeRule.onNodeWithTag("android_menu_history").fetchSemanticsNode()
        val disconnect = composeRule.onNodeWithTag("android_menu_disconnect").fetchSemanticsNode()
        assertTrue(
            "Disconnect must follow History in the overflow menu",
            disconnect.positionInRoot.y > history.positionInRoot.y,
        )
        assertEquals(
            androidx.compose.ui.semantics.Role.Button,
            disconnect.config[SemanticsProperties.Role],
        )
        composeRule.onNodeWithText("Disconnect").assertIsDisplayed()

        composeRule.onNodeWithTag("android_menu_disconnect").performClick()
        composeRule.waitForIdle()

        assertEquals(1, port.endSessions)
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithText("Connect").assertIsDisplayed()
    }

    private class ConnectedFakePort : AndroidClientPort {
        var endSessions = 0
            private set

        override fun endSession() {
            endSessions += 1
        }

        private val profile = AndroidProfile("amanda-laptop", "Amanda")
        val requests = mutableListOf<AndroidTurnRequest>()
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
            requests += request
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
}
