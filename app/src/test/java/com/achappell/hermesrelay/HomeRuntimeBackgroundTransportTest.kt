package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-HOME-06` at the runtime seam: a stopped app closes an idle
 * transport without ending the claim, never trusts a stale `Connected`, and
 * reconnects (never resends) after a submit fails at the transport.
 */
class HomeRuntimeBackgroundTransportTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val clock = ManualClock()
    private val pacer = ManualPacer(clock)
    private val journal = RecordingJournal()
    private val work = QueuedExecutorService()

    private class Home(
        private val initiation: AndroidInitiationResult =
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
    ) : AndroidClientPort {
        var reconnects = 0
        var beginTurns = 0
        var lifecycleCloses = 0
        var live = true

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
            live = true
            return AndroidReconnectOutcome.Connected("bridge-$reconnects")
        }

        override fun hasLiveTransport() = live

        override fun closeForLifecycle() {
            lifecycleCloses += 1
            live = false
        }
    }

    private fun runtime(home: Home): HomeRuntime = HomeRuntime(
        clientPort = home,
        speechInput = null,
        historyStore = InMemoryAndroidHistoryStore(),
        postToMain = { it.run() },
        workExecutor = work,
        journal = journal,
        recoveryPacer = pacer,
        clock = clock,
        networkEvents = NetworkEvents.None,
    ).also { runtime ->
        runtime.reconnectIfForeground = {
            if (runtime.needsReconnect) {
                runtime.recover()
            }
        }
    }

    private fun connected(home: Home): HomeRuntime {
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.appForegrounded()
        runtime.recover()
        work.drain()
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        return runtime
    }

    @Test
    fun an_idle_stop_closes_the_transport_without_ending_the_claim_and_shows_disconnected() {
        val home = Home()
        val runtime = connected(home)

        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(1, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
        assertEquals(1, journal.count("lifecycle deactivate trigger=stopWithoutRetention reply=none"))
    }

    @Test
    fun the_next_start_reconnects_exactly_once() {
        val home = Home()
        val runtime = connected(home)
        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        runtime.appStarted()
        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(2, home.reconnects)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_configuration_change_closes_nothing() {
        val home = Home()
        val runtime = connected(home)

        runtime.appBackgrounded(isChangingConfigurations = true)
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_stop_with_a_reply_in_flight_leaves_the_socket_to_background_retention() {
        val binding = AndroidTurnBinding(profile.id, "bridge-1", "turn-1")
        val home = Home(initiation = AndroidInitiationResult.Accepted(binding))
        val runtime = connected(home)
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        work.drain()
        assertTrue(runtime.hasAcceptedTurn)

        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_newer_start_keeps_the_socket_a_superseded_stop_would_have_closed() {
        val home = Home()
        val runtime = connected(home)

        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.appStarted()
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_stop_after_the_user_disconnected_closes_nothing_more() {
        val home = Home()
        val runtime = connected(home)
        runtime.disconnect()
        work.drain()

        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
    }

    @Test
    fun a_cached_connected_without_a_live_transport_reconnects_on_resume() {
        val home = Home()
        val runtime = connected(home)
        home.live = false

        runtime.appForegrounded()
        runtime.reconnectIfForeground()
        work.drain()

        assertEquals(2, home.reconnects)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_submit_that_fails_at_the_transport_reconnects_without_resending() {
        val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("hello"))
        val home = Home(
            initiation = AndroidInitiationResult.Uncertain(request, AndroidHomeUnavailableReason.TransportUnavailable),
        )
        val runtime = connected(home)

        runtime.initiate(AndroidTurnInput.Typed("hello"))
        work.drain()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(2, home.reconnects)
        assertEquals("one prompt.submit overall", 1, home.beginTurns)
        assertNotNull("the prompt is still offered", runtime.recoveryState.unconfirmedTurn)
        assertFalse(runtime.recoveryState.isRecovering)
    }
}
