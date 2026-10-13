package com.achappell.hermesrelay

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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

    private var originalAccessibilityFlags: Int? = null

    @After
    fun restoreAccessibilityServiceFlags() {
        originalAccessibilityFlags?.let { original ->
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { flags = original }
        }
    }

    private fun enableTouchExploration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
        val manager = instrumentation.targetContext
            .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        composeRule.waitUntil { manager.isTouchExplorationEnabled }
    }

    @Test
    fun accessibility_focus_reveals_a_clipped_turn_without_moving_keyboard_focus() {
        enableTouchExploration()
        var composeHost: View? = null
        val port = ConnectedFakePort()
        var density = 1f
        composeRule.setContent {
            density = LocalDensity.current.density
            composeHost = LocalView.current
            HermesRelayTheme {
                Box(Modifier.height(560.dp)) {
                    AndroidClientScreen(clientPort = port, speechInput = FakeSpeechInput())
                }
            }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Read the completed turn")
        // Seed the turn through Send's semantic action; nested scrolling must not
        // turn this focus-provider fixture into a coordinate-tap test.
        composeRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.android_start_typed_turn),
        ).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue(click())
        }
        closeSoftKeyboard()
        composeRule.runOnIdle {
            val binding = port.lastBinding!!
            port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "A retained response for reading."))
            port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        }
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_turn_phase")
        val phase = composeRule.onNodeWithTag("android_turn_phase")
        val rail = composeRule.onNodeWithTag("android_conversation_rail")
        val phaseBefore = phase.getUnclippedBoundsInRoot()
        val railBefore = rail.getUnclippedBoundsInRoot()
        val desiredTop = railBefore.bottom.value - (phaseBefore.bottom - phaseBefore.top).value / 3f
        rail.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, (phaseBefore.top.value - desiredTop) * density)
        }
        composeRule.waitForIdle()
        val clipped = phase.getUnclippedBoundsInRoot()
        assertTrue("fixture must expose only part of the turn label", clipped.top < railBefore.bottom)
        assertTrue("fixture must clip the bottom of the turn label", clipped.bottom > railBefore.bottom)
        composeRule.onNodeWithTag("android_typed_prompt").assertIsFocused()

        val virtualId = phase.fetchSemanticsNode().id
        val promptId = composeRule.onNodeWithTag("android_typed_prompt").fetchSemanticsNode().id
        val provider = composeRule.runOnIdle {
            val host = checkNotNull(composeHost)
            checkNotNull(ViewCompat.getAccessibilityDelegate(host)?.getAccessibilityNodeProvider(host))
        }
        composeRule.runOnIdle {
            assertFalse(provider.performAction(Int.MAX_VALUE, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        composeRule.waitForIdle()
        assertEquals("declined focus must not scroll the rail", clipped, phase.getUnclippedBoundsInRoot())
        composeRule.runOnIdle {
            assertTrue(provider.performAction(virtualId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        composeRule.waitForIdle()

        val revealed = phase.getUnclippedBoundsInRoot()
        val viewport = rail.getUnclippedBoundsInRoot()
        assertTrue("focused label top must be visible: $revealed in $viewport", revealed.top >= viewport.top)
        assertTrue("focused label bottom must be visible: $revealed in $viewport", revealed.bottom <= viewport.bottom)
        composeRule.runOnIdle {
            assertTrue(
                "accessibility focus must remain on the revealed label",
                provider.createAccessibilityNodeInfo(virtualId)?.isAccessibilityFocused == true,
            )
            assertTrue(provider.performAction(promptId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(provider.performAction(virtualId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        composeRule.waitForIdle()
        assertEquals("an already visible label must not jump", revealed, phase.getUnclippedBoundsInRoot())
        composeRule.onNodeWithTag("android_typed_prompt").assertIsFocused()
    }

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
        "android_interrupt",
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
    fun the_interrupt_control_follows_turn_state_in_accessibility_order() {
        val port = ConnectedFakePort(interruptSupported = true)
        val speech = FakeSpeechInput()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Tell me a story")
        composeRule.onNodeWithText("Start typed turn").performScrollTo().performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        val binding = port.lastBinding!!
        composeRule.runOnIdle { port.emit(AndroidNormalizedEvent.Thinking(binding)) }
        composeRule.waitForIdle()

        val stateIndex = traversalIndexOf("android_turn_phase")
        val interruptIndex = traversalIndexOf("android_interrupt")
        assertTrue(
            "turn state ($stateIndex) must be read before Interrupt ($interruptIndex)",
            stateIndex < interruptIndex,
        )
    }

    @Test
    fun connection_status_is_on_demand_and_errors_remain_assertive() {
        val port = ConnectedFakePort()
        val speech = FakeSpeechInput()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort = port, speechInput = speech)
            }
        }

        assertNull(liveRegionOf("android_doorway_state"))
        assertNull(liveRegionOf("android_connection_state"))
        assertNull(liveRegionOf("android_send_blocked"))
        assertNull(liveRegionOf("android_capture_block"))
        composeRule.onNodeWithTag("android_action_surface").performScrollTo()
        composeRule.onNodeWithTag("android_send_blocked")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.android_composer_block_disconnected))
        composeRule.onNodeWithTag("android_capture_block")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.android_capture_block_not_connected))
        composeRule.onNodeWithTag("android_conversation_rail").performScrollTo()

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
    fun verification_during_recovery_keeps_connection_guidance_readable_but_not_live() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = ConnectedFakePort(authorizationState = AndroidAuthorizationState.Verifying),
                    speechInput = FakeSpeechInput(),
                )
            }
        }
        val authorization = composeRule.onNodeWithText(
            context.getString(
                R.string.android_authorization_label,
                context.getString(R.string.android_authorization_verifying),
            ),
        )
        authorization.assertIsDisplayed()
        assertFalse(authorization.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithTag("android_send_blocked")
            .assertTextEquals(context.getString(R.string.android_composer_block_disconnected))
        assertNull(liveRegionOf("android_send_blocked"))
        composeRule.onNodeWithTag("android_capture_block")
            .assertTextEquals(context.getString(R.string.android_capture_block_not_connected))
        assertNull(liveRegionOf("android_capture_block"))
        composeRule.onNodeWithTag("android_doorway_state_description")
            .assertTextEquals(context.getString(R.string.android_state_unavailable_connection))
        composeRule.onNodeWithTag("android_tap_to_speak").assertIsNotEnabled()
    }

    @Test
    fun recovery_details_update_without_automatic_availability_announcements() {
        val state = mutableStateOf<AndroidDoorwayState>(AndroidDoorwayState.Ready)
        val connection = mutableStateOf<AndroidConnectionState>(AndroidConnectionState.Connected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HermesRelayTheme {
                DoorwayStateZone(
                    state = state.value,
                    connectionState = connection.value,
                    canConfigure = false,
                    canEditRelay = false,
                    onConfigure = {},
                    onEditRelay = {},
                )
            }
        }
        val status = composeRule.onNodeWithTag("android_doorway_state")
        val description = composeRule.onNodeWithTag("android_doorway_state_description")
        status.assertTextEquals(context.getString(R.string.android_state_ready))
        assertNull(liveRegionOf("android_doorway_state"))

        composeRule.runOnIdle {
            state.value = AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Authorization)
            connection.value = AndroidConnectionState.Disconnected
        }
        status.assertTextEquals(context.getString(R.string.android_state_unavailable))
        description.assertTextEquals(context.getString(R.string.android_state_unavailable_authorization))
        assertNull(liveRegionOf("android_doorway_state"))

        composeRule.runOnIdle {
            state.value = AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Connection)
            connection.value = AndroidConnectionState.Reconnecting(attempt = 1, of = 3)
        }
        description.assertTextEquals(context.getString(R.string.android_state_unavailable_connection_reconnecting))
        // Retry progress must remain readable without reintroducing a live region.
        composeRule.runOnIdle {
            connection.value = AndroidConnectionState.Reconnecting(attempt = 2, of = 3)
        }
        val retry = composeRule.onNodeWithTag("android_connection_state")
        retry.assertTextEquals(
            context.getString(
                R.string.android_connection_label,
                context.getString(R.string.android_connection_reconnecting, 2, 3),
            ),
        ).assertIsDisplayed()
        assertFalse(retry.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals(A11yOrder.STATE, retry.fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
        assertNull(liveRegionOf("android_doorway_state"))

        composeRule.runOnIdle {
            state.value = AndroidDoorwayState.Ready
            connection.value = AndroidConnectionState.Connected
        }
        status.assertTextEquals(context.getString(R.string.android_state_ready)).assertIsDisplayed()
        description.assertTextEquals(context.getString(R.string.android_state_ready_description))
        assertNull(liveRegionOf("android_doorway_state"))
        retry.assertDoesNotExist()
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

    private class ConnectedFakePort(
        private val interruptSupported: Boolean = false,
        private val authorizationState: AndroidAuthorizationState = AndroidAuthorizationState.Verified,
    ) : AndroidClientPort {
        var endSessions = 0
            private set

        override fun endSession() {
            endSessions += 1
        }

        override fun supportsInterrupt() = interruptSupported

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
            authorizationState = authorizationState,
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
