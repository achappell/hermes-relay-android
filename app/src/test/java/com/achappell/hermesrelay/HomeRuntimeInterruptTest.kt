package com.achappell.hermesrelay

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ANDROID-VOICE-04` through the runtime: the real controller, a fake port and a fake recognizer. */
class HomeRuntimeInterruptTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val binding = AndroidTurnBinding(profile.id, "conversation-1", "turn-1")

    private class Inline : AbstractExecutorService() {
        private var down = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { down = true }
        override fun shutdownNow(): MutableList<Runnable> { down = true; return mutableListOf() }
        override fun isShutdown() = down
        override fun isTerminated() = down
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = down
    }

    private inner class Port : AndroidClientPort {
        var interrupts = 0
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
            AndroidInitiationResult.Accepted(binding)

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")

        override fun hasActiveTurn() = false

        override fun supportsInterrupt() = true

        override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
            interrupts += 1
            return true
        }

        fun emit(event: AndroidNormalizedEvent) = checkNotNull(listener)(event)
    }

    private class Rig(port: Port) {
        val speech = FakeSpeechInput()
        val timers = ManualVoiceTimers()
        val runtime = HomeRuntime(
            clientPort = port,
            speechInput = speech,
            historyStore = null,
            postToMain = { it.run() },
            workExecutor = Inline(),
            voiceTimers = timers,
        ).also {
            it.activityCreated()
            it.recover()
            it.initiate(AndroidTurnInput.Typed("tell me a story"))
        }
    }

    @Test
    fun one_tap_interrupts_once_and_starts_capture_once_when_the_turn_ends() {
        val port = Port()
        val rig = Rig(port)

        rig.runtime.interruptAndListen(binding)
        rig.runtime.interruptAndListen(binding)
        assertEquals(InterruptStatus.Pending, rig.runtime.interruptStatus)
        assertEquals(0, rig.speech.startCount)

        port.emit(AndroidNormalizedEvent.TurnInterrupted(binding, "user interrupted"))

        assertEquals("a second session.interrupt was sent", 1, port.interrupts)
        assertEquals(1, rig.speech.startCount)
        assertEquals(InterruptStatus.None, rig.runtime.interruptStatus)
    }

    @Test
    fun with_hands_free_armed_the_tap_only_interrupts() {
        val port = Port()
        val rig = Rig(port)
        rig.runtime.captureController!!.armHandsFree()
        val startsBefore = rig.speech.startCount

        rig.runtime.interruptAndListen(binding)
        port.emit(AndroidNormalizedEvent.TurnInterrupted(binding, "user interrupted"))

        assertEquals(1, port.interrupts)
        assertEquals("the interrupt started its own capture on top of hands-free", startsBefore, rig.speech.startCount)
    }

    @Test
    fun no_terminal_in_two_seconds_shows_unconfirmed_and_starts_capture() {
        val port = Port()
        val rig = Rig(port)

        rig.runtime.interruptAndListen(binding)
        rig.timers.advanceBy(2_000)

        assertEquals(InterruptStatus.Unconfirmed, rig.runtime.interruptStatus)
        assertEquals(1, rig.speech.startCount)
        assertTrue(rig.runtime.turnState.phase != AndroidTurnPhase.Interrupted)
    }

    @Test
    fun a_deliberate_disconnect_discards_a_pending_interrupt_and_never_opens_the_microphone() {
        val port = Port()
        val rig = Rig(port)
        rig.runtime.interruptAndListen(binding)

        rig.runtime.disconnect()
        rig.timers.advanceBy(10_000)

        assertEquals("the microphone opened after a deliberate disconnect", 0, rig.speech.startCount)
        assertEquals(InterruptStatus.None, rig.runtime.interruptStatus)
    }
}
