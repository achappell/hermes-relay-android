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
        val requests = mutableListOf<AndroidTurnRequest>()
        val observedBindings = mutableListOf<AndroidTurnBinding>()
        var cancelledObservations = 0
        var beforeSubmit: () -> Unit = {}
        private var listener: ((AndroidNormalizedEvent) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            beforeSubmit()
            requests += request
            return AndroidInitiationResult.Accepted(
                binding.copy(turnId = "turn-${requests.size}"),
            )
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            observedBindings += binding
            listener = onEvent
            return AndroidTurnObservation {
                cancelledObservations += 1
                listener = null
            }
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

    /** Retains displaced callbacks, including callbacks delivered inside platform cancellation. */
    private class RetainingSpeech : AndroidSpeechInput {
        override var networkRecognitionAllowed = false
        override fun authorization() = AndroidSpeechAuthorization.Granted
        val callbacks = mutableListOf<(AndroidSpeechEvent) -> Unit>()
        val startCount: Int get() = callbacks.size
        var cancelCount = 0
        var duringCancel: (AndroidSpeechEvent) -> Unit = {}

        override fun start(onEvent: (AndroidSpeechEvent) -> Unit) {
            callbacks += onEvent
        }

        override fun stop() = Unit

        override fun cancel() {
            cancelCount += 1
            val callback = callbacks.last()
            callback(AndroidSpeechEvent.Cancelled)
            duringCancel(AndroidSpeechEvent.Cancelled)
            callback(AndroidSpeechEvent.Final("late cancellation transcript"))
        }

        fun emit(event: AndroidSpeechEvent) = callbacks.last()(event)
    }

    private class Rig(port: Port) {
        val speech = RetainingSpeech()
        val journal = RecordingJournal()
        val timers = ManualVoiceTimers()
        val runtime = HomeRuntime(
            clientPort = port,
            speechInput = speech,
            historyStore = null,
            postToMain = { it.run() },
            workExecutor = Inline(),
            voiceTimers = timers,
            journal = journal,
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

    @Test
    fun typed_send_retires_starting_capture_before_submission_and_ignores_late_callbacks() {
        assertTypedOwnsNextTurn(AndroidCaptureState.Starting)
    }

    @Test
    fun typed_send_retires_listening_capture_before_submission_and_ignores_late_callbacks() {
        assertTypedOwnsNextTurn(AndroidCaptureState.Listening)
    }

    @Test
    fun typed_send_retires_transcribing_capture_before_submission_and_ignores_late_callbacks() {
        assertTypedOwnsNextTurn(AndroidCaptureState.Transcribing("provisional"))
    }

    private fun assertTypedOwnsNextTurn(captureState: AndroidCaptureState) {
        val port = Port()
        val rig = Rig(port)
        rig.runtime.interruptAndListen(binding)
        port.emit(AndroidNormalizedEvent.TurnInterrupted(binding, "user interrupted"))
        when (captureState) {
            AndroidCaptureState.Listening -> rig.speech.emit(AndroidSpeechEvent.Started)
            is AndroidCaptureState.Transcribing -> {
                rig.speech.emit(AndroidSpeechEvent.Started)
                rig.speech.emit(AndroidSpeechEvent.Partial(captureState.partial))
            }
            else -> Unit
        }
        assertEquals(captureState, rig.runtime.captureState)
        val oldCallback = rig.speech.callbacks.single()
        rig.speech.duringCancel = {
            assertEquals("capture must close before recognizer cancellation", captureState, rig.runtime.captureState)
        }
        port.beforeSubmit = {
            assertEquals("recognizer must cancel before typed submission", 1, rig.speech.cancelCount)
            assertEquals(AndroidCaptureState.Idle, rig.runtime.captureState)
        }

        rig.runtime.initiate(AndroidTurnInput.Typed("typed next"))
        val nextBinding = binding.copy(turnId = "turn-2")
        assertEquals(listOf("tell me a story", "typed next"), port.requests.map { (it.input as AndroidTurnInput.Typed).text })
        assertEquals(AndroidInitiationState.Accepted(nextBinding), rig.runtime.initiationState)
        assertEquals(nextBinding, rig.runtime.turnState.binding)
        assertEquals(listOf(binding, nextBinding), port.observedBindings)
        assertEquals(1, port.cancelledObservations)
        assertEquals(
            listOf("voice capture started", "voice capture cancelled reason=typed_prompt"),
            rig.journal.lines.filter { it.startsWith("voice capture ") },
        )

        oldCallback(AndroidSpeechEvent.Started)
        oldCallback(AndroidSpeechEvent.Partial("obsolete"))
        oldCallback(AndroidSpeechEvent.Cancelled)
        oldCallback(AndroidSpeechEvent.Final("obsolete final"))
        rig.timers.advanceBy(10_000)
        assertEquals(2, port.requests.size)
        assertEquals(1, port.interrupts)
        assertEquals(1, rig.speech.startCount)
        assertEquals(AndroidCaptureState.Idle, rig.runtime.captureState)
        assertEquals(AndroidInitiationState.Accepted(nextBinding), rig.runtime.initiationState)
        assertEquals(1, port.cancelledObservations)
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(nextBinding, "typed response"))
        port.emit(AndroidNormalizedEvent.AudioStarted(nextBinding))
        port.emit(AndroidNormalizedEvent.AudioEnded(nextBinding))
        port.emit(AndroidNormalizedEvent.TurnCompleted(nextBinding))
        assertEquals("typed response", rig.runtime.turnState.responseText)
        assertEquals(AndroidTurnPhase.Complete, rig.runtime.turnState.phase)

        // Reopening must not let the displaced recognizer act on this fresh window.
        port.beforeSubmit = {}
        rig.runtime.captureController!!.beginCapture()
        rig.speech.emit(AndroidSpeechEvent.Started)
        oldCallback(AndroidSpeechEvent.Started)
        oldCallback(AndroidSpeechEvent.Partial("old partial after reopen"))
        oldCallback(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard))
        oldCallback(AndroidSpeechEvent.Cancelled)
        oldCallback(AndroidSpeechEvent.Final("old final after reopen"))
        assertEquals(AndroidCaptureState.Listening, rig.runtime.captureState)
        assertEquals(2, port.requests.size)
        assertEquals(AndroidInitiationState.Accepted(nextBinding), rig.runtime.initiationState)
        assertEquals(1, port.cancelledObservations)
        rig.speech.emit(AndroidSpeechEvent.Final("fresh voice"))
        assertEquals(3, port.requests.size)
        assertEquals(AndroidCaptureState.Submitted("fresh voice"), rig.runtime.captureState)
        assertEquals(AndroidInitiationState.Accepted(binding.copy(turnId = "turn-3")), rig.runtime.initiationState)
    }

    @Test
    fun typed_send_retires_pending_interrupt_deadline_before_work_is_submitted() {
        val port = Port()
        val rig = Rig(port)
        rig.runtime.interruptAndListen(binding)
        port.beforeSubmit = {
            // Advancing inside beginTurn also proves retirement precedes executor work,
            // not just the eventual Accepted result's existing coordinator reset.
            rig.timers.advanceBy(2_000)
            assertEquals(0, rig.speech.startCount)
            assertEquals(InterruptStatus.None, rig.runtime.interruptStatus)
        }

        rig.runtime.initiate(AndroidTurnInput.Typed("typed before deadline"))
        rig.timers.advanceBy(10_000)

        assertEquals(0, rig.speech.startCount)
        assertEquals(0, rig.speech.cancelCount)
        assertEquals(1, port.interrupts)
        assertEquals(2, port.requests.size)
        assertEquals(emptyList<String>(), rig.journal.lines.filter { it.startsWith("voice capture ") })
        val nextBinding = binding.copy(turnId = "turn-2")
        port.emit(AndroidNormalizedEvent.ResponseTextDelta(nextBinding, "still observing"))
        assertEquals("still observing", rig.runtime.turnState.responseText)
    }

    @Test
    fun typed_send_cancels_capture_without_disarming_hands_free_continuation() {
        val port = Port()
        val rig = Rig(port)
        rig.runtime.captureController!!.armHandsFree()
        rig.speech.emit(AndroidSpeechEvent.Started)

        rig.runtime.initiate(AndroidTurnInput.Typed("typed while hands free"))

        assertEquals(1, rig.speech.cancelCount)
        assertEquals(AndroidCaptureState.Idle, rig.runtime.captureState)
        assertTrue(rig.runtime.handsFree)
        assertTrue(rig.runtime.captureController!!.isHandsFree)
        val nextBinding = binding.copy(turnId = "turn-2")
        port.emit(AndroidNormalizedEvent.AudioStarted(nextBinding))
        port.emit(AndroidNormalizedEvent.AudioEnded(nextBinding))
        port.emit(AndroidNormalizedEvent.TurnCompleted(nextBinding))
        assertEquals(2, rig.speech.startCount)
        assertEquals(AndroidCaptureState.Starting, rig.runtime.captureState)
        assertEquals(2, port.requests.size)
    }
}
