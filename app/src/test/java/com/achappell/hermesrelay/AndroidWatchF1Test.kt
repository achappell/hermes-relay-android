package com.achappell.hermesrelay

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ANDROID-WATCH-F1`: hands-free turn actions while capture has no Home binding. */
class AndroidWatchF1Test {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val binding = AndroidTurnBinding(
        profileId = profile.id,
        conversationHandle = "conversation-1",
        connectionId = "connection-1",
        turnId = "turn-1",
    )

    @Test
    fun unbound_listening_is_terminal_for_turn_actions_and_the_reducer_ignores_a_prior_turn_event() {
        val armed = AndroidTurnState(phase = AndroidTurnPhase.Listening)

        assertNull(armed.binding)
        assertTrue(armed.isTerminal)
        assertFalse(armed.isInFlight)
        assertEquals(
            "the reducer must not let a prior turn's interrupt change unbound capture",
            armed,
            AndroidTurnStateReducer.reduce(
                armed,
                AndroidNormalizedEvent.TurnInterrupted(binding, "stale turn"),
            ),
        )
    }

    @Test
    fun bound_listening_stays_in_flight() {
        val bound = AndroidTurnState(binding = binding, phase = AndroidTurnPhase.Listening)

        assertFalse(bound.isTerminal)
        assertTrue(bound.isInFlight)
    }

    @Test
    fun connection_loss_while_armed_with_no_binding_reconnects_without_replaying_a_turn() {
        val rig = armedRig()

        rig.port.emitConnectionLost()
        rig.runtime.recover()

        assertEquals(AndroidConnectionState.Connected, rig.runtime.recoveryState.connection)
        assertNull("no turn was in flight to retain", rig.runtime.recoveryState.unconfirmedTurn)
        assertEquals("no turn was replayed or submitted", 0, rig.port.beginTurnCount)
    }

    @Test
    fun armed_no_binding_does_not_offer_disconnect_confirmation_or_interrupt_the_previous_turn() {
        val rig = armedRig()

        assertEquals(AndroidTurnPhase.Listening, rig.runtime.turnState.phase)
        assertNull(rig.runtime.turnState.binding)
        assertFalse(rig.runtime.turnState.isInFlight)
        assertFalse(rig.runtime.hasAcceptedTurn)
        assertFalse(rig.runtime.disconnectNeedsConfirmation)

        // Disconnect is allowed without treating the old turn binding as live.
        rig.runtime.disconnect()

        assertEquals(listOf("endSession"), rig.port.calls)
        assertEquals("no turn was submitted from the armed window", 0, rig.port.beginTurnCount)
        assertEquals("no stale turn was interrupted", 0, rig.port.interruptCount)
        assertTrue(rig.speech.cancelled)
        assertFalse(rig.runtime.handsFree)
    }

    @Test
    fun finishing_the_activity_while_armed_with_no_binding_tears_down_and_releases_the_microphone() {
        val rig = armedRig()

        rig.runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertEquals("the ended turn must not defer teardown", 1, rig.port.closeCount)
        assertTrue(rig.speech.cancelled)
        assertFalse(rig.runtime.handsFree)
    }

    /** A runtime whose last turn completed and whose hands-free capture has reopened unbound. */
    private fun armedRig(): Rig {
        val port = Port()
        val speech = FakeSpeechInput()
        val runtime = HomeRuntime(
            clientPort = port,
            speechInput = speech,
            historyStore = null,
            postToMain = { it.run() },
            workExecutor = InlineExecutor(),
            voiceTimers = ManualVoiceTimers(),
        )
        runtime.activityCreated()
        runtime.recover()
        // Begin from an already completed Home turn. The old Accepted state is
        // intentionally retained while hands-free starts the next capture.
        runtime.applyInitiationState(
            AndroidInitiationState.Accepted(binding),
            recordAcceptedInput = false,
        )
        port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "done"))
        runtime.captureController!!.armHandsFree()
        return Rig(runtime, port, speech)
    }

    private class Rig(val runtime: HomeRuntime, val port: Port, val speech: FakeSpeechInput)

    private class InlineExecutor : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = shutdown
    }

    private inner class Port : AndroidClientPort {
        val calls = mutableListOf<String>()
        var beginTurnCount = 0
        var interruptCount = 0
        var closeCount = 0
        private var turnListener: ((AndroidNormalizedEvent) -> Unit)? = null
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            beginTurnCount += 1
            return AndroidInitiationResult.Accepted(binding)
        }

        override fun reconnect() = AndroidReconnectOutcome.Connected("connection-1")

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            turnListener = onEvent
            return AndroidTurnObservation { turnListener = null }
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation { connectionListener = null }
        }

        override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
            interruptCount += 1
            calls += "interrupt"
            return true
        }

        override fun endSession() {
            calls += "endSession"
        }

        override fun close() {
            closeCount += 1
        }

        fun emit(event: AndroidNormalizedEvent) = checkNotNull(turnListener)(event)

        fun emitConnectionLost() = checkNotNull(connectionListener)(
            AndroidNormalizedEvent.Disconnected("connection-1", "transport lost"),
        )
    }
}
