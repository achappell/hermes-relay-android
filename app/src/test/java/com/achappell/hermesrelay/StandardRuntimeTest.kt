package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class StandardRuntimeTest {
    private val profile = AndroidProfile("standard", "Standard")
    private val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("uncertain input"))

    @Test
    fun reconnect_and_discard_cannot_clear_standard_uncertainty_or_replay() {
        val port = Port()
        val recovery = AndroidRecoveryController(port)
        recovery.transportLost("lost", AndroidUnconfirmedTurn(null, request))
        recovery.recover()
        recovery.discardUnconfirmedTurn()
        recovery.resendUnconfirmedTurn()
        assertTrue(recovery.state.hasUnconfirmedTurn)
        assertTrue(recovery.state.unresolvedHomeTurn)
        assertEquals(0, port.submissions)
    }

    @Test
    fun failed_new_session_keeps_switch_guard_and_history_success_preserves_old_history() {
        val port = Port()
        val store = InMemoryAndroidHistoryStore()
        val journal = RecordingJournal()
        val runtime = HomeRuntime(port, null, store, { it.run() }, InlineExecutor(), journal = journal)
        val recorder = checkNotNull(runtime.recorder)
        recorder.open("standard-identity")
        recorder.recordUserTurn("Earlier local input")
        recorder.recordResponse("Earlier local response")
        runtime.recoveryController.transportLost("lost", AndroidUnconfirmedTurn(null, request))
        assertFalse(runtime.canReconfigure)

        var result: Boolean? = null
        runtime.newStandardConversation { result = it }
        assertEquals(false, result)
        assertTrue(runtime.recoveryState.hasUnconfirmedTurn)
        assertEquals(
            AndroidConnectionState.Failed("New session failed", AndroidHomeUnavailableReason.RequestRejected),
            runtime.recoveryState.connection,
        )
        assertFalse(runtime.canReconfigure)
        assertEquals(2, recorder.history.entries.size)

        port.newSessionSucceeds = true
        runtime.newStandardConversation { result = it }
        assertEquals(true, result)
        assertFalse(runtime.recoveryState.hasUnconfirmedTurn)
        assertTrue(runtime.canReconfigure)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(listOf("Earlier local input", "Earlier local response", "New conversation"),
            recorder.history.entries.map { it.text })
        assertEquals(0, port.submissions)
        assertTrue(journal.lines.contains("standard new_conversation result=failed"))
        assertTrue(journal.lines.contains("standard new_conversation result=connected"))
    }

    @Test
    fun selected_standard_failure_does_not_contact_home_or_expose_home_interfaces() {
        val selected = RelayProfile("s", "wss://standard.example/api/ws", "", "", "Standard",
            mode = RelayConnectionMode.Standard)
        val home = Port(RelayConnectionMode.HomeBridge)
        val standard = Port()
        val router = ModeAwareClientPort({ RelayProfileCollection(listOf(selected), selected.id) }, home, standard)
        standard.connectionFails = true
        assertTrue(router.reconnect() is AndroidReconnectOutcome.Unrecoverable)
        assertEquals(0, home.connections)
        assertEquals(RelayConnectionMode.Standard, router.connectionMode())
        assertNull(router.homeConversations())
        assertNull(router.homeApprovals())
        assertTrue(router.hasUncertainTurn())
    }

    @Test
    fun active_standard_work_blocks_reconfiguration_even_without_an_observed_turn() {
        val port = Port()
        port.uncertain = false
        port.active = true
        val runtime = HomeRuntime(port, null, null, { it.run() }, InlineExecutor())
        assertFalse(runtime.canReconfigure)
        port.active = false
        assertTrue(runtime.canReconfigure)
    }

    private inner class Port(private val mode: RelayConnectionMode = RelayConnectionMode.Standard) : AndroidClientPort {
        var uncertain = true
        var active = false
        var newSessionSucceeds = false
        var connectionFails = false
        var submissions = 0
        var connections = 0
        private var live = true
        override fun hasLiveTransport() = live
        override fun connectionMode() = mode
        override fun hasUncertainTurn() = uncertain
        override fun hasActiveTurn() = active
        override fun snapshot() = AndroidClientSnapshot(
            BootstrapState.titleRes, BootstrapState.descriptionRes, BootstrapState.boundaryRes,
            profile, AndroidAuthorizationState.Verified,
        )
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            submissions++
            return AndroidInitiationResult.Rejected(AndroidInitiationFailure.DeliveryUncertain)
        }
        override fun reconnect(): AndroidReconnectOutcome {
            connections++
            return if (connectionFails) AndroidReconnectOutcome.Unrecoverable("Standard unavailable")
            else AndroidReconnectOutcome.Connected("transport", unresolvedTurn = uncertain)
        }
        override fun newConversation(): AndroidReconnectOutcome {
            if (!newSessionSucceeds) {
                live = false
                return AndroidReconnectOutcome.Unrecoverable("New session failed", AndroidHomeUnavailableReason.RequestRejected)
            }
            live = true
            uncertain = false
            return AndroidReconnectOutcome.Connected("fresh-transport")
        }
    }

    private class InlineExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
