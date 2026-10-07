package com.achappell.hermesrelay

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `ANDROID-HOME-07`: recreating the Activity while a fake Home streams a reply
 * keeps one runtime, never closes the client and keeps the turn on screen.
 *
 * Needs a device or emulator. It proves the wiring (Application-scoped box,
 * `onDestroy` rule, Compose observing the runtime) only; it does not exercise
 * real audio, the real Home, or the journal line `runtime created`
 * (`ANDROID-DIAG-01`), and it has not been run in CI.
 */
@RunWith(AndroidJUnit4::class)
class HomeRuntimeRecreationTest {
    @Test
    fun recreating_the_activity_keeps_one_runtime_its_socket_and_its_turn() {
        val app = ApplicationProvider.getApplicationContext<HermesRelayApplication>()
        val port = StreamingFakePort()
        val mainHandler = Handler(Looper.getMainLooper())
        val original = app.homeRuntimeBox
        app.homeRuntimeBox = HomeRuntimeBox { onTornDown ->
            HomeRuntime(
                clientPort = port,
                speechInput = null,
                historyStore = null,
                postToMain = { mainHandler.post(it) },
                workExecutor = Executors.newSingleThreadExecutor(),
                onTornDown = onTornDown,
            )
        }
        val box = app.homeRuntimeBox
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val runtime = box.resolve()
                scenario.onActivity { runtime.initiate(AndroidTurnInput.Typed("hello")) }
                assertTrue("turn was never observed", port.turnObserved.await(10, TimeUnit.SECONDS))
                port.emit(AndroidNormalizedEvent.ResponseTextDelta(port.binding, "Streaming answer"))
                InstrumentationRegistry.getInstrumentation().runOnMainSync {}

                scenario.recreate()
                scenario.recreate()

                assertSame(runtime, box.resolve())
                assertEquals("runtime created once", 1, box.createdCount)
                assertEquals("the socket stayed open", 0, port.closeCount)
                assertEquals("Streaming answer", runtime.turnState.responseText)

                // Let the reply end so closing the scenario tears the runtime down.
                port.emit(AndroidNormalizedEvent.TurnCompleted(port.binding, "Streaming answer"))
                InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            }
        } finally {
            app.homeRuntimeBox = original
        }
    }

    private class StreamingFakePort : AndroidClientPort {
        val binding = AndroidTurnBinding("amanda", "conversation-1", "turn-1")
        val turnObserved = CountDownLatch(1)

        @Volatile
        var closeCount = 0

        @Volatile
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("amanda", "Amanda"),
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
            AndroidInitiationResult.Accepted(binding)

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            listener = onEvent
            turnObserved.countDown()
            return AndroidTurnObservation { listener = null }
        }

        @Volatile
        private var turnOpen = true

        override fun hasActiveTurn(): Boolean = turnOpen

        override fun close() {
            closeCount += 1
        }

        fun emit(event: AndroidNormalizedEvent) {
            if (event is AndroidNormalizedEvent.TurnCompleted) turnOpen = false
            checkNotNull(listener) { "no turn is being observed" }(event)
        }
    }
}
