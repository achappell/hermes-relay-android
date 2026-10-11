package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-HOME-04` at the runtime seam: foreground and background inputs, the
 * returning network, stop conditions and the unconfirmed turn. Work runs on a
 * queue the test drains, so it decides which input overtakes which.
 */
class HomeRuntimeReconnectTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val clock = ManualClock()
    private val pacer = ManualPacer(clock)
    private val network = FakeNetworkEvents()
    private val journal = RecordingJournal()
    private val work = QueuedExecutorService()

    private class ScriptedHome(
        private val clock: ManualClock,
        private val outcomes: MutableList<AndroidReconnectOutcome>,
        private val initiation: AndroidInitiationResult =
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
    ) : AndroidClientPort {
        var reconnects = 0
        var beginTurns = 0
        val attemptTimesMillis = mutableListOf<Long>()
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("amanda", "Amanda"),
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            beginTurns += 1
            return initiation
        }

        override fun reconnect(): AndroidReconnectOutcome {
            reconnects += 1
            attemptTimesMillis += clock.nanoTime() / 1_000_000
            return if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation { connectionListener = null }
        }

        fun dropTransport() {
            connectionListener?.invoke(AndroidNormalizedEvent.Disconnected("bridge-1", "Software caused connection abort"))
        }
    }

    private val unavailable = AndroidReconnectOutcome.Retryable(
        "Home is unreachable.",
        AndroidHomeUnavailableReason.TransportUnavailable,
    )

    private fun runtime(home: ScriptedHome): HomeRuntime = HomeRuntime(
        clientPort = home,
        speechInput = null,
        historyStore = InMemoryAndroidHistoryStore(),
        postToMain = { it.run() },
        workExecutor = work,
        journal = journal,
        recoveryPacer = pacer,
        clock = clock,
        networkEvents = network,
    ).also { runtime ->
        // What the resumed screen supplies: reconnect a disconnected Profile.
        runtime.reconnectIfForeground = {
            if (runtime.recoveryState.connection != AndroidConnectionState.Connected) runtime.recover()
        }
    }

    @Test
    fun a_foreground_reconnect_that_fails_at_the_transport_retries_until_connected() {
        val home = ScriptedHome(clock, mutableListOf(unavailable, unavailable, AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(listOf(0L, 500L, 1_500L), home.attemptTimesMillis)
    }

    @Test
    fun a_returning_network_retries_within_the_same_instant_not_the_next_backoff_step() {
        val home = ScriptedHome(clock, mutableListOf(unavailable, AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        var pauses = 0
        pacer.onPause = {
            pauses += 1
            if (pauses == 1) network.available()
        }

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals("the retry does not wait the full next step", listOf(0L, 0L), home.attemptTimesMillis)
    }

    @Test
    fun a_network_event_while_disconnected_in_the_foreground_starts_one_reconnect() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        runtime.appForegrounded()

        network.available()
        network.available()
        work.drain()

        assertEquals(1, home.reconnects)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_network_event_while_connected_does_nothing() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        runtime.appForegrounded()
        runtime.recover()
        work.drain()

        network.available()
        work.drain()

        assertEquals(1, home.reconnects)
    }

    @Test
    fun a_deliberate_disconnect_ignores_the_network() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        runtime.appForegrounded()
        runtime.recover()
        work.drain()
        runtime.disconnect()
        work.drain()

        network.available()
        work.drain()

        assertTrue(runtime.userDisconnected)
        assertEquals(1, home.reconnects)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
    }

    @Test
    fun an_explicit_disconnect_during_the_retry_ladder_stops_future_attempts() {
        val home = ScriptedHome(clock, mutableListOf(unavailable))
        val runtime = runtime(home)
        var pauses = 0
        pacer.onPause = {
            pauses += 1
            if (pauses == 1) {
                assertTrue("Disconnect is available while recovery is running", runtime.disconnectEnabled)
                runtime.disconnect()
            }
        }

        runtime.appStarted()
        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(1, home.reconnects)
        assertTrue(runtime.userDisconnected)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
    }

    @Test
    fun the_app_stopping_during_a_wait_ends_the_ladder_disconnected_with_no_further_attempt() {
        val home = ScriptedHome(clock, mutableListOf(unavailable))
        val runtime = runtime(home)
        var pauses = 0
        pacer.onPause = {
            pauses += 1
            if (pauses == 1) runtime.appBackgrounded(isChangingConfigurations = false)
        }

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(1, home.reconnects)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
        assertFalse(runtime.recoveryState.isRecovering)
    }

    @Test
    fun a_recreation_is_not_a_background_and_the_ladder_keeps_going() {
        val home = ScriptedHome(clock, mutableListOf(unavailable, AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        pacer.onPause = { runtime.appBackgrounded(isChangingConfigurations = true) }

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(2, home.reconnects)
    }

    @Test
    fun switching_profile_during_a_wait_stops_the_previous_ladder() {
        val home = ScriptedHome(clock, mutableListOf(unavailable))
        val runtime = runtime(home)
        runtime.profileSelected("amanda")
        var pauses = 0
        pacer.onPause = {
            pauses += 1
            if (pauses == 1) runtime.profileSelected("other")
        }

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(1, home.reconnects)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
    }

    @Test
    fun the_first_profile_report_and_a_repeat_after_recreation_stop_nothing() {
        val home = ScriptedHome(clock, mutableListOf(unavailable, AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        pacer.onPause = { runtime.profileSelected("amanda") }
        runtime.profileSelected("amanda")

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_start_followed_by_a_stop_does_not_reconnect_after_the_stop() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)

        runtime.appStarted()
        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(0, home.reconnects)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
    }

    @Test
    fun a_stop_followed_by_a_start_reconnects_exactly_once() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)

        runtime.appStarted()
        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.appStarted()
        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals("only the newest input reconnects", 1, home.reconnects)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_transport_loss_with_an_unconfirmed_turn_reconnects_and_never_resubmits() {
        val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("hello"))
        val home = ScriptedHome(
            clock,
            mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")),
            initiation = AndroidInitiationResult.Uncertain(request, AndroidHomeUnavailableReason.TransportUnavailable),
        )
        val runtime = runtime(home)
        runtime.appForegrounded()

        runtime.initiate(AndroidTurnInput.Typed("hello"))
        work.drain()
        assertNotNull(runtime.recoveryState.unconfirmedTurn)
        home.dropTransport()
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(1, home.reconnects)
        assertEquals("one prompt.submit overall", 1, home.beginTurns)
        assertEquals(
            "the unconfirmed prompt is still offered",
            AndroidUnconfirmedTurn(null, request),
            runtime.recoveryState.unconfirmedTurn,
        )
    }

    @Test
    fun tear_down_stops_listening_to_the_network() {
        val home = ScriptedHome(clock, mutableListOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val runtime = runtime(home)
        runtime.activityCreated()

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertEquals(1, network.observers)
        assertEquals(1, network.cancelled)
    }

    @Test
    fun a_resume_and_a_returning_network_together_run_one_ladder() {
        val home = ScriptedHome(clock, mutableListOf(unavailable))
        val runtime = runtime(home)

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        network.available()
        work.drain()

        assertEquals("one ladder of six attempts, not two", 6, home.reconnects)
    }
}
