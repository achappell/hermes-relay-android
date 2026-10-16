package com.achappell.hermesrelay

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Activity/lifecycle and visible UI; disposable fake transport, not live Home. */
@RunWith(AndroidJUnit4::class)
class ClientHolderLifecycleSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(
        *if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test fun pending_header_and_recreated_then_resumed_screen_observe_latest_holder_state() {
        val app = ApplicationProvider.getApplicationContext<HermesRelayApplication>()
        val original = app.homeRuntimeBox
        val port = FixturePort()
        val handler = Handler(Looper.getMainLooper())
        app.homeRuntimeBox = HomeRuntimeBox { onTornDown ->
            HomeRuntime(port, null, null, { handler.post(it) }, Executors.newSingleThreadExecutor(), onTornDown)
        }
        val box = app.homeRuntimeBox
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val runtime = box.resolve()
                scenario.onActivity { runtime.connect() }
                compose.waitUntil(10_000) { runtime.recoveryState.connection == AndroidConnectionState.Connected }
                compose.onNodeWithTag("android_typed_prompt").performTextInput("Disposable lifecycle prompt")
                compose.onNodeWithText("Start typed turn").assertIsEnabled()
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                assertTrue("submission reached worker", port.beginEntered.await(10, TimeUnit.SECONDS))
                click("android_more_menu")
                compose.onNodeWithTag("android_menu_disconnect").assertIsNotEnabled()
                port.releaseBegin.countDown()
                compose.waitUntil(10_000) { port.listener != null && !runtime.initiationInFlight }
                compose.onNodeWithTag("android_menu_disconnect").assertIsEnabled()
                pressBack()
                port.emit(AndroidNormalizedEvent.ResponseTextDelta(port.binding, "Before recreation"))
                compose.waitUntil(10_000) { runtime.turnState.responseText == "Before recreation" }
                scenario.recreate()
                port.emit(AndroidNormalizedEvent.ResponseTextDelta(port.binding, " after recreation"))
                compose.waitUntil(10_000) { runtime.turnState.responseText.endsWith("after recreation") }
                showResponse("Before recreation after recreation")
                assertSame(runtime, box.resolve())
                scenario.moveToState(Lifecycle.State.CREATED)
                port.emit(AndroidNormalizedEvent.ResponseTextDelta(port.binding, " while stopped"))
                InstrumentationRegistry.getInstrumentation().runOnMainSync {}
                assertTrue(runtime.turnState.responseText.endsWith("while stopped"))
                scenario.moveToState(Lifecycle.State.RESUMED)
                showResponse("Before recreation after recreation while stopped")
                // Complete includes delivered-audio lifecycle; text-only completion is honestly Unavailable.
                port.emit(AndroidNormalizedEvent.AudioStarted(port.binding))
                port.emit(AndroidNormalizedEvent.AudioEnded(port.binding))
                port.emit(AndroidNormalizedEvent.TurnCompleted(port.binding, "Final visible reply"))
                compose.waitUntil(10_000) { runtime.turnState.isTerminal }
                showResponse("Final visible reply")
                compose.onNodeWithTag("android_conversation_rail").performScrollToNode(hasTestTag("android_turn_phase"))
                compose.onNodeWithTag("android_turn_phase").assertTextEquals("Turn phase: Complete").assertIsDisplayed()
                assertEquals(1, port.submissions)
                assertEquals(1, box.createdCount)
            }
        } finally {
            port.releaseBegin.countDown()
            app.homeRuntimeBox = original
        }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun showResponse(text: String) {
        compose.onNodeWithTag("android_conversation_rail").performScrollTo()
        compose.onNodeWithTag("android_conversation_rail").performScrollToNode(hasTestTag("android_response_text"))
        compose.onNodeWithTag("android_response_text").assertTextEquals(text).assertIsDisplayed()
    }

    private class FixturePort : AndroidClientPort {
        val binding = AndroidTurnBinding("fixture-lifecycle", "fixture-session", "fixture-turn")
        val beginEntered = CountDownLatch(1)
        val releaseBegin = CountDownLatch(1)
        @Volatile var submissions = 0
        @Volatile var listener: ((AndroidNormalizedEvent) -> Unit)? = null
        override fun snapshot() = AndroidClientSnapshot(BootstrapState.titleRes, BootstrapState.descriptionRes,
            BootstrapState.boundaryRes, AndroidProfile("fixture-lifecycle", "Lifecycle fixture"), AndroidAuthorizationState.Verified)
        override fun reconnect() = AndroidReconnectOutcome.Connected("fixture-session")
        override fun hasLiveTransport() = true
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            submissions += 1
            beginEntered.countDown()
            check(releaseBegin.await(10, TimeUnit.SECONDS)) { "test did not release pending submission" }
            return AndroidInitiationResult.Accepted(binding)
        }
        override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }
        fun emit(event: AndroidNormalizedEvent) { checkNotNull(listener)(event) }
    }
}
