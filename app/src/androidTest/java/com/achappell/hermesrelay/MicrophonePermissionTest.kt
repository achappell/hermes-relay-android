package com.achappell.hermesrelay

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ANDROID-VOICE-02`: the four microphone permission states reach the user as
 * distinct, actionable surfaces, and a permanently denied microphone offers the
 * application-details screen instead of a button that silently does nothing.
 */
@RunWith(AndroidJUnit4::class)
class MicrophonePermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeSource(
        var granted: Boolean = false,
        var asked: Boolean = false,
        var rationale: Boolean = false,
    ) : RuntimePermissionSource {
        var markCount = 0
            private set

        override fun isGranted() = granted

        override fun hasAsked() = asked

        override fun shouldShowRationale() = rationale

        override fun markAsked() {
            markCount += 1
            asked = true
        }
    }

    @Test
    fun a_screen_that_has_not_asked_shows_the_request_and_never_launches_it() {
        val source = FakeSource()

        showConnectedScreen(source)

        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("android_capture_block").performScrollTo().assertIsDisplayed()
        assertEquals("a request was launched without a user action", 0, source.markCount)
    }

    @Test
    fun a_denial_with_rationale_explains_why_and_offers_the_request_again() {
        val source = FakeSource(asked = true, rationale = true)

        showConnectedScreen(source)

        composeRule.onNodeWithTag("android_capture_block").performScrollTo().assertIsDisplayed()
        assertTextEquals(
            "android_capture_block",
            context.getString(R.string.android_capture_block_permission_rationale),
        )
        composeRule.onNodeWithTag("android_tap_to_speak").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTagCount("android_microphone_open_settings", 0)
    }

    @Test
    fun a_permanent_denial_offers_open_settings_with_the_application_details_intent() {
        val source = FakeSource(asked = true, rationale = false)
        var opened: Intent? = null

        showConnectedScreen(source) { opened = it }

        assertTextEquals(
            "android_capture_block",
            context.getString(R.string.android_capture_block_permission_settings),
        )
        composeRule.onAllNodesWithTagCount("android_tap_to_speak", 0)
        composeRule.onNodeWithTag("android_microphone_open_settings")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        val intent = checkNotNull(opened) { "Open settings started no Intent" }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
        assertEquals("no permission request is launched from Open settings", 0, source.markCount)
    }

    @Test
    fun returning_from_settings_with_the_permission_granted_clears_the_prompt() {
        val source = FakeSource(asked = true, rationale = false)
        val owner = TestLifecycleOwner()

        composeRule.setContent {
            HermesRelayTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val permission = rememberRuntimePermission(
                        permission = Manifest.permission.RECORD_AUDIO,
                        source = source,
                    )
                    Column {
                        Text(
                            text = permission.state.name,
                            modifier = Modifier.testTag("state"),
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle { owner.resume() }
        composeRule.onNodeWithTag("state").assertIsDisplayed()
        assertTextEquals("state", RuntimePermissionState.PermanentlyDenied.name)

        // The user grants the microphone in Settings and comes back.
        source.granted = true
        composeRule.runOnIdle {
            owner.pause()
            owner.resume()
        }
        composeRule.waitForIdle()

        assertTextEquals("state", RuntimePermissionState.Granted.name)
    }

    @Test
    fun the_settings_intent_targets_this_application_only() {
        val intent = applicationDetailsSettingsIntent("com.example.app")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.example.app", intent.data.toString())
        assertNull(intent.component)
    }

    private fun showConnectedScreen(
        source: RuntimePermissionSource,
        openSettings: ((Intent) -> Unit)? = null,
    ) {
        val port = ConnectedPort()
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.NotDetermined)
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = port,
                    speechInput = speech,
                    microphonePermissionSource = source,
                    openApplicationSettings = openSettings,
                )
            }
        }
        composeRule.scrollToConversationTag("android_connect")
        composeRule.onNodeWithTag("android_connect").performClick()
        composeRule.waitForIdle()
    }

    private fun assertTextEquals(tag: String, expected: String) {
        val texts = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertEquals(expected, texts)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
        tag: String,
        expected: Int,
    ) {
        assertEquals(
            "$tag node count",
            expected,
            onAllNodesWithTag(tag).fetchSemanticsNodes().size,
        )
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        fun pause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
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
