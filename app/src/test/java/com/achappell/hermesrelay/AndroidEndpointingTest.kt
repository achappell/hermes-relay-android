package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-VOICE-03`: a pause of 1.5 s after the last words ends capture and
 * sends the turn; a shorter pause does not; the recognizer gets 2 s to deliver
 * its final result. Time only moves when the test moves it.
 */
class AndroidEndpointingTest {
    private val profile = AndroidProfile("amanda-laptop", "Amanda")
    private val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")

    private class Rig {
        val speech = FakeSpeechInput()
        val timers = ManualVoiceTimers()
    }

    private fun Rig.start(port: FakePort = FakePort(), onState: (AndroidCaptureState) -> Unit = {}) =
        AndroidCaptureController(
            speech = speech,
            timers = timers,
            initiation = AndroidInitiationController(port),
            isConnected = { true },
            isAuthorized = { true },
            onStateChange = onState,
        ).also {
            it.beginCapture()
            speech.emit(AndroidSpeechEvent.Started)
        }

    @Test
    fun the_documented_timings_are_one_and_a_half_seconds_and_two_seconds() {
        assertEquals(1_500L, VoiceTimings.Default.silenceEndpointMillis)
        assertEquals(2_000L, VoiceTimings.Default.finalResultWaitMillis)
    }

    @Test
    fun silence_after_a_partial_ends_capture_and_submits_once() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)

        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_499)
        assertFalse("capture ended before the pause was long enough", rig.speech.stopped)

        rig.timers.advanceBy(1)
        assertTrue("the silence endpoint did not stop the recognizer", rig.speech.stopped)

        rig.speech.emit(AndroidSpeechEvent.Final("check the weather"))
        assertEquals(AndroidCaptureState.Submitted("check the weather"), controller.state)
        assertEquals(1, port.requests.size)
        assertEquals(AndroidTurnInput.Typed("check the weather"), port.requests.single().input)
    }

    @Test
    fun a_shorter_pause_does_not_cut_the_sentence_off() {
        val rig = Rig()
        val controller = rig.start()

        rig.speech.emit(AndroidSpeechEvent.Partial("check the"))
        rig.timers.advanceBy(1_000)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_000)
        assertFalse("a 1 s mid-sentence pause ended capture", rig.speech.stopped)
        assertTrue(controller.isCapturing)

        rig.timers.advanceBy(500)
        assertTrue(rig.speech.stopped)
    }

    @Test
    fun a_repeated_identical_partial_is_not_new_speech() {
        val rig = Rig()
        rig.start()

        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_000)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(500)

        assertTrue("an unchanged transcript postponed the endpoint", rig.speech.stopped)
    }

    @Test
    fun silence_is_not_measured_before_anything_was_heard() {
        val rig = Rig()
        val controller = rig.start()

        rig.timers.advanceBy(10_000)

        assertFalse(rig.speech.stopped)
        assertTrue("the recognizer's own timeout owns an empty window", controller.isCapturing)
    }

    @Test
    fun a_late_final_within_two_seconds_wins_over_the_last_partial() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_500)

        rig.timers.advanceBy(1_999)
        rig.speech.emit(AndroidSpeechEvent.Final("check the weather in Chicago"))

        assertEquals(AndroidCaptureState.Submitted("check the weather in Chicago"), controller.state)
        assertEquals(
            AndroidTurnInput.Typed("check the weather in Chicago"),
            port.requests.single().input,
        )
        assertEquals("a timer outlived the capture", 0, rig.timers.pendingCount)
    }

    @Test
    fun with_no_final_after_two_seconds_the_partial_is_not_sent_and_no_speech_is_shown() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_500)

        rig.timers.advanceBy(2_000)

        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertTrue("the recognizer was left open", rig.speech.cancelled)
        assertEquals("a partial transcript was sent", 0, port.requests.size)

        // A final that arrives after the deadline belongs to a capture that ended.
        rig.speech.emit(AndroidSpeechEvent.Final("check the weather"))
        assertEquals(0, port.requests.size)
    }

    @Test
    fun explicit_stop_still_sends_and_cancels_the_endpoint_timer() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))

        controller.finishCapture()
        rig.speech.emit(AndroidSpeechEvent.Final("check the weather"))
        rig.timers.advanceBy(10_000)

        assertEquals(1, port.requests.size)
        assertEquals(1, rig.speech.stopCount)
    }

    @Test
    fun silence_and_stop_racing_send_exactly_one_turn() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))

        rig.timers.advanceBy(1_500)
        controller.finishCapture()
        rig.speech.emit(AndroidSpeechEvent.Final("check the weather"))
        rig.speech.emit(AndroidSpeechEvent.Final("check the weather"))
        rig.timers.advanceBy(10_000)

        assertEquals("the recognizer was stopped twice", 1, rig.speech.stopCount)
        assertEquals("two turns were begun", 1, port.requests.size)
    }

    @Test
    fun stop_with_nothing_heard_reports_no_speech_after_the_wait() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)

        controller.finishCapture()
        rig.timers.advanceBy(2_000)

        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun cancel_leaves_no_timer_behind_and_nothing_is_sent() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))

        controller.cancelCapture()
        rig.timers.advanceBy(10_000)

        assertEquals(AndroidCaptureState.Idle, controller.state)
        assertEquals(0, rig.timers.pendingCount)
        assertEquals(0, port.requests.size)
        assertFalse(rig.speech.stopped)
    }

    @Test
    fun a_whitespace_final_is_never_sent() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        rig.speech.emit(AndroidSpeechEvent.Partial("hmm"))
        rig.timers.advanceBy(1_500)

        rig.speech.emit(AndroidSpeechEvent.Final("   "))

        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun the_hands_free_exit_phrase_still_closes_the_window_after_the_endpoint() {
        val rig = Rig()
        val port = FakePort()
        val controller = rig.start(port)
        controller.armHandsFree()
        rig.speech.emit(AndroidSpeechEvent.Started)
        rig.speech.emit(AndroidSpeechEvent.Partial("stop"))
        rig.timers.advanceBy(1_500)

        rig.speech.emit(AndroidSpeechEvent.Final("stop"))

        assertFalse(controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.ExactStop, controller.lastHandsFreeExit)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun an_overdue_final_in_hands_free_closes_the_window_as_silence() {
        val rig = Rig()
        val controller = rig.start()
        controller.armHandsFree()
        rig.speech.emit(AndroidSpeechEvent.Started)
        rig.speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        rig.timers.advanceBy(1_500)

        rig.timers.advanceBy(2_000)

        assertFalse(controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.Silence, controller.lastHandsFreeExit)
        assertNull(controller.lastHandsFreeExit?.takeIf { it == AndroidHandsFreeExit.Failure })
    }

    private inner class FakePort : AndroidClientPort {
        val requests = mutableListOf<AndroidTurnRequest>()

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation = AndroidTurnObservation { }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return AndroidInitiationResult.Accepted(binding)
        }
    }
}
