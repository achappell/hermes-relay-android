package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FR5`, as amended: a doorway reopens a bounded window after each response
 * without re-triggering, and continues until an explicit exit. The bound
 * governs each window, not the number of windows.
 */
class AndroidHandsFreeTest {
    private val profile = AndroidProfile("amanda-laptop", "Amanda")
    private val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")

    @Test
    fun a_completed_turn_reopens_the_window_without_re_triggering() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("check the weather"))
        assertEquals(1, port.requests.size)

        controller.onTurnSettled(AndroidTurnPhase.Complete)

        assertTrue("the window did not reopen", controller.isHandsFree)
        assertEquals("capture was not restarted", 2, speech.startCount)

        // And it keeps going, which is the point of the amendment.
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("and tomorrow"))
        controller.onTurnSettled(AndroidTurnPhase.Complete)

        assertEquals(2, port.requests.size)
        assertEquals(3, speech.startCount)
    }

    @Test
    fun exactly_stop_closes_the_window_silently_and_is_never_submitted() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("stop"))

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.ExactStop, controller.lastHandsFreeExit)
        assertEquals("\"stop\" was sent to Hermes as a turn", 0, port.requests.size)
        // Silently: no failure is presented to the user.
        assertEquals(AndroidCaptureState.Idle, controller.state)
    }

    @Test
    fun stop_only_counts_when_it_is_the_whole_utterance() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("stop the timer"))

        assertTrue("a sentence containing stop ended the conversation", controller.isHandsFree)
        assertEquals("stop the timer", port.requests.single().input.let {
            (it as AndroidTurnInput.Typed).text
        })
    }

    @Test
    fun a_turn_that_did_not_complete_does_not_reopen_the_window() {
        listOf(
            AndroidTurnPhase.Unavailable,
            AndroidTurnPhase.Interrupted,
        ).forEach { phase ->
            val speech = FakeSpeechInput()
            val controller = controller(speech, FakePort())
            controller.armHandsFree()
            speech.emit(AndroidSpeechEvent.Started)
            speech.emit(AndroidSpeechEvent.Final("check the weather"))
            val startsBefore = speech.startCount

            controller.onTurnSettled(phase)

            assertTrue(
                "$phase reopened the window and presented a failed turn as ready",
                !controller.isHandsFree,
            )
            assertEquals(startsBefore, speech.startCount)
            assertEquals(AndroidHandsFreeExit.Failure, controller.lastHandsFreeExit)
        }
    }

    @Test
    fun losing_transport_ends_the_conversation_rather_than_listening_into_nothing() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("check the weather"))
        controller.onTurnSettled(AndroidTurnPhase.Disconnected)

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.SessionEnded, controller.lastHandsFreeExit)
    }

    @Test
    fun a_completed_turn_with_no_session_left_does_not_reopen() {
        val speech = FakeSpeechInput()
        var connected = true
        val controller = AndroidCaptureController(
            speech = speech,
            initiation = AndroidInitiationController(FakePort()),
            isConnected = { connected },
            isAuthorized = { true },
        )

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("check the weather"))
        connected = false
        controller.onTurnSettled(AndroidTurnPhase.Complete)

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.SessionEnded, controller.lastHandsFreeExit)
    }

    @Test
    fun silence_ends_the_conversation() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard))

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.Silence, controller.lastHandsFreeExit)
    }

    @Test
    fun an_empty_final_transcript_ends_hands_free_without_sending() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("   "))

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.Silence, controller.lastHandsFreeExit)
        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun a_recogniser_failure_ends_the_conversation() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.RecognizerBusy))

        assertTrue(!controller.isHandsFree)
        assertEquals(AndroidHandsFreeExit.Failure, controller.lastHandsFreeExit)
    }

    @Test
    fun disarming_stops_capture_and_sends_nothing() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.armHandsFree()
        speech.emit(AndroidSpeechEvent.Started)
        controller.disarmHandsFree()

        assertTrue(!controller.isHandsFree)
        assertTrue(speech.cancelled)
        assertEquals(0, port.requests.size)
        assertEquals(AndroidHandsFreeExit.Disarmed, controller.lastHandsFreeExit)
    }

    @Test
    fun arming_without_a_live_session_fails_closed_and_stays_disarmed() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort(), connected = false)

        controller.armHandsFree()

        assertTrue("hands-free stayed armed with no Session", !controller.isHandsFree)
        assertEquals("the microphone was opened anyway", 0, speech.startCount)
        assertEquals(AndroidHandsFreeExit.SessionEnded, controller.lastHandsFreeExit)
    }

    @Test
    fun a_settled_turn_does_nothing_when_hands_free_was_never_armed() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.onTurnSettled(AndroidTurnPhase.Complete)

        assertEquals(0, speech.startCount)
        assertNull(controller.lastHandsFreeExit)
    }

    private fun controller(
        speech: AndroidSpeechInput,
        port: FakePort,
        connected: Boolean = true,
    ) = AndroidCaptureController(
        speech = speech,
        initiation = AndroidInitiationController(port),
        isConnected = { connected },
        isAuthorized = { true },
    )

    private inner class FakePort : AndroidClientPort {
        val requests = mutableListOf<AndroidTurnRequest>()

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return AndroidInitiationResult.Accepted(binding)
        }
    }
}
