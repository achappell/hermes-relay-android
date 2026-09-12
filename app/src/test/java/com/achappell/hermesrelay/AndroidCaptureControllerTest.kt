package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCaptureControllerTest {
    private val profile = AndroidProfile("amanda-laptop", "Amanda")
    private val binding = AndroidTurnBinding("amanda-laptop", "session-1", "turn-1")

    @Test
    fun a_spoken_turn_is_submitted_from_the_final_transcript() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val states = mutableListOf<AndroidCaptureState>()
        val controller = controller(speech, port, onStateChange = { states += it })

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("check the"))
        speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        controller.finishCapture()
        speech.emit(AndroidSpeechEvent.Final("check the weather"))

        assertEquals(1, speech.startCount)
        assertTrue(speech.stopped)
        assertEquals(AndroidCaptureState.Submitted("check the weather"), controller.state)
        assertEquals(
            listOf(
                AndroidCaptureState.Starting,
                AndroidCaptureState.Listening,
                AndroidCaptureState.Transcribing("check the"),
                AndroidCaptureState.Transcribing("check the weather"),
                AndroidCaptureState.Submitted("check the weather"),
            ),
            states,
        )
        assertEquals(
            AndroidTurnRequest(profile, AndroidTurnInput.Typed("check the weather")),
            port.requests.single(),
        )
    }

    @Test
    fun a_partial_transcript_is_never_submitted() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("check the"))

        assertEquals(0, port.requests.size)
    }

    @Test
    fun capture_fails_closed_without_the_microphone_permission() {
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.NotDetermined)
        val port = FakePort()
        val controller = controller(speech, port)

        controller.beginCapture()

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.PermissionRequired),
            controller.state,
        )
        assertEquals("the microphone was opened anyway", 0, speech.startCount)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun capture_fails_closed_when_no_recognizer_exists() {
        val speech = FakeSpeechInput(AndroidSpeechAuthorization.Unavailable)
        val controller = controller(speech, FakePort())

        controller.beginCapture()

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.RecognizerUnavailable),
            controller.state,
        )
        assertEquals(0, speech.startCount)
    }

    @Test
    fun capture_fails_closed_without_a_live_session() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort(), connected = false)

        controller.beginCapture()

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.NotConnected),
            controller.state,
        )
        assertEquals(0, speech.startCount)
    }

    @Test
    fun capture_fails_closed_without_a_verified_profile() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort(), authorized = false)

        controller.beginCapture()

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.ProfileUnavailable),
            controller.state,
        )
        assertEquals(0, speech.startCount)
    }

    @Test
    fun a_session_lost_while_transcribing_reports_rather_than_submits() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        var connected = true
        val controller = AndroidCaptureController(
            speech = speech,
            initiation = AndroidInitiationController(port),
            isConnected = { connected },
            isAuthorized = { true },
        )

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        connected = false
        speech.emit(AndroidSpeechEvent.Final("check the weather"))

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.NotConnected),
            controller.state,
        )
        assertEquals("a turn was sent into a dead Session", 0, port.requests.size)
    }

    @Test
    fun an_empty_final_transcript_sends_nothing() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("   "))

        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun a_recognizer_failure_is_reported_without_sending_a_turn() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard))

        assertEquals(
            AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard),
            controller.state,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun cancelling_capture_sends_nothing_and_returns_to_idle() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = controller(speech, port)

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        controller.cancelCapture()

        assertTrue(speech.cancelled)
        assertEquals(AndroidCaptureState.Idle, controller.state)
        assertEquals(0, port.requests.size)
    }

    @Test
    fun a_second_begin_while_capturing_does_not_open_a_second_recognizer() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        controller.beginCapture()

        assertEquals(1, speech.startCount)
    }

    @Test
    fun an_accepted_spoken_turn_reports_its_binding() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        var initiation: AndroidInitiationState? = null
        val controller = AndroidCaptureController(
            speech = speech,
            initiation = AndroidInitiationController(port),
            isConnected = { true },
            isAuthorized = { true },
            onInitiation = { initiation = it },
        )

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("check the weather"))

        assertEquals(AndroidInitiationState.Accepted(binding), initiation)
        assertNull((controller.state as? AndroidCaptureState.Failed)?.reason)
    }

    @Test
    fun a_session_replaced_mid_capture_refuses_to_attach_the_utterance_to_it() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        var session = "session-1"
        val controller = AndroidCaptureController(
            speech = speech,
            initiation = AndroidInitiationController(port),
            isConnected = { true },
            isAuthorized = { true },
            currentSessionId = { session },
        )

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("check the"))
        // Recovery negotiates a fresh Session while the user is still speaking.
        session = "session-2"
        speech.emit(AndroidSpeechEvent.Final("check the weather"))

        assertEquals(
            AndroidCaptureState.Unavailable(AndroidCaptureBlock.SessionReplaced),
            controller.state,
        )
        assertEquals("the utterance joined a conversation it was not part of", 0, port.requests.size)
    }

    @Test
    fun an_unchanged_session_submits_normally() {
        val speech = FakeSpeechInput()
        val port = FakePort()
        val controller = AndroidCaptureController(
            speech = speech,
            initiation = AndroidInitiationController(port),
            isConnected = { true },
            isAuthorized = { true },
            currentSessionId = { "session-1" },
        )

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Final("check the weather"))

        assertEquals(AndroidCaptureState.Submitted("check the weather"), controller.state)
        assertEquals(1, port.requests.size)
    }

    @Test
    fun the_live_partial_transcript_is_the_participants_provisional_text() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("check"))
        assertEquals(AndroidCaptureState.Transcribing("check"), controller.state)

        speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        assertEquals(AndroidCaptureState.Transcribing("check the weather"), controller.state)
    }

    @Test
    fun cancelling_clears_the_provisional_transcript() {
        val speech = FakeSpeechInput()
        val controller = controller(speech, FakePort())

        controller.beginCapture()
        speech.emit(AndroidSpeechEvent.Started)
        speech.emit(AndroidSpeechEvent.Partial("check the weather"))
        controller.cancelCapture()

        // Idle carries no partial text, so a cancelled utterance leaves nothing.
        assertEquals(AndroidCaptureState.Idle, controller.state)
    }

    private fun controller(
        speech: AndroidSpeechInput,
        port: FakePort,
        connected: Boolean = true,
        authorized: Boolean = true,
        onStateChange: (AndroidCaptureState) -> Unit = {},
    ) = AndroidCaptureController(
        speech = speech,
        initiation = AndroidInitiationController(port),
        isConnected = { connected },
        isAuthorized = { authorized },
        onStateChange = onStateChange,
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
