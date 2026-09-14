package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
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

        composeRule.onNodeWithText("Hermes conversation").assertIsDisplayed()
        composeRule
            .onNodeWithText("Use this doorway for typed and spoken Hermes turns.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Current turn and recovery state stay visible here. Local History is kept on this device.")
            .assertIsDisplayed()
    }

    @Test
    fun conversation_shell_exposes_a_fixed_header_and_action_surface() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(BootstrapClientPort)
            }
        }

        composeRule.onNodeWithTag("android_doorway_header").assertIsDisplayed()
        composeRule.onNodeWithTag("android_action_surface").assertIsDisplayed()
    }

    @Test
    fun no_profile_exposes_configuration_as_the_primary_path() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = BootstrapClientPort,
                    configuration = emptyConfiguration(),
                )
            }
        }

        composeRule.scrollToConversationTag("android_doorway_state")
        composeRule.onNodeWithTag("android_doorway_state").assertIsDisplayed()
        composeRule.onAllNodesWithText("No Profile selected").assertCountEquals(2)
        composeRule.onNodeWithText("Configure a relay to begin.").assertIsDisplayed()
        composeRule.onNodeWithTag("android_configure_relay").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ready").assertCountEquals(0)
    }

    @Test
    fun disconnected_profile_exposes_retry_and_edit_relay() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = AuthorizedFakePort(),
                    configuration = configuredConfiguration(),
                )
            }
        }

        composeRule.scrollToConversationTag("android_doorway_state")
        composeRule.onNodeWithText("Unavailable").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_edit_relay")
        composeRule.onNodeWithTag("android_edit_relay").assertIsDisplayed()
        composeRule.onNodeWithTag("android_edit_relay").performClick()
        composeRule.scrollToConversationTag("android_relay_configuration")
        composeRule.onNodeWithText("Hermes relay configuration").assertIsDisplayed()
    }

    @Test
    fun connected_idle_profile_is_explicitly_ready() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(AuthorizedFakePort())
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_doorway_state")
        composeRule.onNodeWithTag("android_doorway_state").assertIsDisplayed()
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to speak").assertIsDisplayed()
        composeRule.onAllNodesWithText("Unavailable").assertCountEquals(0)
        composeRule.onAllNodesWithTag("android_voice_activity").assertCountEquals(0)
    }

    @Test
    fun disconnected_composer_keeps_a_draft_and_explains_why_send_is_disabled() {
        val store = InMemoryAndroidHistoryStore()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = AuthorizedFakePort(),
                    configuration = configuredConfiguration(),
                    historyStore = store,
                )
            }
        }

        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("check the weather")

        composeRule
            .onNodeWithTag("android_cached_draft")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Saved locally — connect before sending.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Start typed turn").assertIsNotEnabled()
        assertEquals("check the weather", store.load("amanda").draft)
    }

    @Test
    fun prompt_history_is_grouped_under_a_recent_prompts_label() {
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(AuthorizedFakePort())
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag("android_prompt_history_label")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recent prompts").assertIsDisplayed()
    }

    @Test
    fun authorized_typed_turn_uses_the_selected_profile_once() {
        val port = AuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_turn_status")
        composeRule
            .onNodeWithText("Turn accepted for Amanda. Waiting for Home events.")
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

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        val binding = port.requests.single().let { request ->
            AndroidTurnBinding(request.profile.id, "session-1", "turn-1")
        }

        port.emit(AndroidNormalizedEvent.CaptureStarted(binding))
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_turn_phase")
        composeRule.onNodeWithText("Turn phase: Listening").assertIsDisplayed()

        port.emit(AndroidNormalizedEvent.TranscriptionStarted(binding))
        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain "))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "later"))
        port.emit(AndroidNormalizedEvent.AudioBuffering(binding))
        composeRule.waitForIdle()
        composeRule.scrollToConversationTag("android_turn_phase")
        composeRule.onNodeWithText("Turn phase: Buffering").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_voice_activity")
        composeRule.onNodeWithTag("android_voice_activity").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertIsDisplayed()

        port.emit(AndroidNormalizedEvent.AudioStarted(binding))
        port.emit(AndroidNormalizedEvent.AudioChunkReceived(binding))
        port.emit(AndroidNormalizedEvent.AudioEnded(binding))
        port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_turn_phase")
        composeRule.onNodeWithText("Turn phase: Complete").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertIsDisplayed()
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

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Tell me a story")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        val binding = AndroidTurnBinding("amanda", "session-1", "turn-1")

        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Text survives"))
        port.emit(AndroidNormalizedEvent.TurnCompleted(binding))
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_turn_phase")
        composeRule.onNodeWithText("Turn phase: Unavailable").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_audio_unavailable")
        composeRule.onNodeWithTag("android_audio_unavailable").assertIsDisplayed()
        composeRule.onAllNodesWithText("Turn phase: Speaking").assertCountEquals(0)
    }

    @Test
    fun a_lost_turn_is_marked_unconfirmed_and_resent_only_by_an_explicit_action() {
        val port = RecoverableAuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        val binding = port.lastBinding!!

        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.Disconnected("session-1", "The socket closed."))
        composeRule.waitForIdle()

        composeRule.scrollToConversationTag("android_unconfirmed_turn")
        composeRule.onNodeWithTag("android_unconfirmed_turn").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_connection_state")
        composeRule.onNodeWithText("Home bridge: Disconnected").assertIsDisplayed()
        assertEquals(1, port.requests.size)

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertEquals(1, port.requests.size)

        composeRule.scrollToConversationTag("android_unconfirmed_turn")
        composeRule
            .onNodeWithText("Resend this turn")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(2, port.requests.size)
        assertEquals(port.requests[0], port.requests[1])
        composeRule.onAllNodesWithText(
                "This turn lost transport before Home confirmed it. It was not replayed. " +
                "Resend it only if you want to ask again.",
        ).assertCountEquals(0)
    }

    @Test
    fun retained_context_is_labelled_as_cached_while_disconnected() {
        val port = RecoverableAuthorizedFakePort()

        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(port)
            }
        }

        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("Check the weather")
        composeRule.onNodeWithText("Start typed turn").performClick()
        closeSoftKeyboard()
        composeRule.waitForIdle()
        val binding = port.lastBinding!!

        port.emit(AndroidNormalizedEvent.Thinking(binding))
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Rain later"))
        composeRule.waitForIdle()

        // While connected, retained text is live and carries no cached label.
        composeRule.onAllNodesWithTag("android_cached_response").assertCountEquals(0)
        composeRule.onAllNodesWithTag("android_cached_draft").assertCountEquals(0)

        port.emit(AndroidNormalizedEvent.Disconnected(binding.sessionId, "The socket closed."))
        composeRule.waitForIdle()

        // The answer stays visible, but must not read as a live conversation.
        composeRule.scrollToConversationTag("android_response_text")
        composeRule.onNodeWithTag("android_response_text").assertIsDisplayed()
        composeRule.scrollToConversationTag("android_cached_response")
        composeRule.onNodeWithTag("android_cached_response").assertIsDisplayed()
        composeRule.onNodeWithText("Start typed turn").assertIsNotEnabled()

        // The composer emptied when the turn was sent, so a draft only exists
        // once something new is typed during the outage.
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("written while offline")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("android_cached_draft").performScrollTo().assertIsDisplayed()
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

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")
    }

    private fun emptyConfiguration() = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(),
        credentials = InMemoryRelayCredentialStore(),
    )

    private fun configuredConfiguration() = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(
            RelayProfileCollection(
                profiles = listOf(
                    RelayProfile(
                        id = "amanda",
                        endpoint = "wss://relay.example/voice-session",
                        clientId = "amanda",
                        deviceId = "android",
                        displayName = "Amanda",
                    ),
                ),
                selectedId = "amanda",
            ),
        ),
        credentials = InMemoryRelayCredentialStore(mapOf("amanda" to "token")),
    )

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

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")

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

    private class RecoverableAuthorizedFakePort : AndroidClientPort {
        private val profile = AndroidProfile("amanda", "Amanda")
        val requests = mutableListOf<AndroidTurnRequest>()
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null
        private var sessionId = "session-1"
        private var connections = 0
        var lastBinding: AndroidTurnBinding? = null
            private set

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            val binding = AndroidTurnBinding(
                profileId = request.profile.id,
                sessionId = sessionId,
                turnId = "turn-${requests.size}",
            )
            lastBinding = binding
            return AndroidInitiationResult.Accepted(binding)
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

        override fun reconnect(): AndroidReconnectOutcome {
            // The first connect establishes session-1; a later recovery
            // negotiates a genuinely fresh session, as the relay does.
            connections += 1
            sessionId = "session-$connections"
            return AndroidReconnectOutcome.Connected(sessionId)
        }

        fun emit(event: AndroidNormalizedEvent) {
            listener?.invoke(event)
        }
    }
}
