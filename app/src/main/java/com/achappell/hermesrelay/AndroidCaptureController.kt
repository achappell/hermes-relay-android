package com.achappell.hermesrelay

/** What the doorway is doing with the microphone right now. */
internal sealed interface AndroidCaptureState {
    data object Idle : AndroidCaptureState

    /** Requested, but the recognizer has not confirmed it is listening. */
    data object Starting : AndroidCaptureState

    data object Listening : AndroidCaptureState

    /** Speech heard; the recognizer is producing a transcript. */
    data class Transcribing(val partial: String) : AndroidCaptureState

    data class Submitted(val transcript: String) : AndroidCaptureState

    data class Failed(val reason: AndroidSpeechFailure) : AndroidCaptureState

    /** Capture cannot begin. The reason is actionable, not generic. */
    data class Unavailable(val reason: AndroidCaptureBlock) : AndroidCaptureState
}

internal enum class AndroidCaptureBlock {
    PermissionRequired,
    RecognizerUnavailable,
    NotConnected,
    ProfileUnavailable,
}

/**
 * Gates microphone capture and turns a finished transcript into a turn.
 *
 * Capture is refused before it starts unless the microphone is authorized, a
 * recognizer exists, a verified Profile is selected, and a live Session is
 * available. Nothing is recorded speculatively, and a transcript is submitted
 * only when the recognizer reports it as final.
 */
internal class AndroidCaptureController(
    private val speech: AndroidSpeechInput,
    private val initiation: AndroidInitiationController,
    private val isConnected: () -> Boolean,
    private val isAuthorized: () -> Boolean,
    private val onStateChange: (AndroidCaptureState) -> Unit = {},
    private val onInitiation: (AndroidInitiationState) -> Unit = {},
) {
    var state: AndroidCaptureState = AndroidCaptureState.Idle
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChange(value)
        }

    val isCapturing: Boolean
        get() = state is AndroidCaptureState.Starting ||
            state is AndroidCaptureState.Listening ||
            state is AndroidCaptureState.Transcribing

    /** Pre-capture gate. Returns null when capture may begin. */
    fun blockingReason(): AndroidCaptureBlock? = when {
        !isAuthorized() -> AndroidCaptureBlock.ProfileUnavailable
        !isConnected() -> AndroidCaptureBlock.NotConnected
        speech.authorization() == AndroidSpeechAuthorization.Unavailable ->
            AndroidCaptureBlock.RecognizerUnavailable
        speech.authorization() != AndroidSpeechAuthorization.Granted ->
            AndroidCaptureBlock.PermissionRequired
        else -> null
    }

    fun beginCapture() {
        if (isCapturing) return

        blockingReason()?.let { reason ->
            // Fail closed: the microphone is never opened to discover this.
            state = AndroidCaptureState.Unavailable(reason)
            return
        }

        state = AndroidCaptureState.Starting
        speech.start { event -> handle(event) }
    }

    /** Ends capture and waits for the recognizer's final transcript. */
    fun finishCapture() {
        if (!isCapturing) return
        speech.stop()
    }

    fun cancelCapture() {
        if (!isCapturing) return
        speech.cancel()
        state = AndroidCaptureState.Idle
    }

    private fun handle(event: AndroidSpeechEvent) {
        when (event) {
            AndroidSpeechEvent.Started -> state = AndroidCaptureState.Listening

            is AndroidSpeechEvent.Partial ->
                state = AndroidCaptureState.Transcribing(event.text)

            is AndroidSpeechEvent.Final -> submit(event.text)

            is AndroidSpeechEvent.Failed ->
                state = AndroidCaptureState.Failed(event.reason)

            AndroidSpeechEvent.Cancelled -> state = AndroidCaptureState.Idle
        }
    }

    private fun submit(transcript: String) {
        val text = transcript.trim()
        if (text.isEmpty()) {
            state = AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard)
            return
        }

        // The Session can end while the recognizer is finishing. Submitting
        // into a dead Session would be a turn the user cannot see the result
        // of, so report it rather than sending it.
        if (!isConnected()) {
            state = AndroidCaptureState.Unavailable(AndroidCaptureBlock.NotConnected)
            return
        }

        state = AndroidCaptureState.Submitted(text)
        onInitiation(initiation.initiate(AndroidTurnInput.Typed(text)))
    }
}
