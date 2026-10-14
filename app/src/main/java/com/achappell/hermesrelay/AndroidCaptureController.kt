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

    /** Recovery negotiated a fresh Session while the user was speaking. */
    SessionReplaced,
}

/** Why a hands-free conversation ended. Recorded so the UI can be honest. */
internal enum class AndroidHandsFreeExit {
    /** The user said exactly "stop". FR5 requires this to close silently. */
    ExactStop,

    /** The window closed with nothing heard. */
    Silence,

    /** Capture or the recogniser failed. */
    Failure,

    /** The Session ended or was replaced. */
    SessionEnded,

    /** The user turned it off. */
    Disarmed,
}

/**
 * Gates microphone capture and turns a finished transcript into a turn.
 *
 * Capture is refused before it starts unless the microphone is authorized, a
 * recognizer exists, a verified Profile is selected, and a live Session is
 * available. Nothing is recorded speculatively, and a transcript is submitted
 * only when the recognizer reports it as final.
 */
private const val EXACT_STOP = "stop"

internal class AndroidCaptureController(
    private val speech: AndroidSpeechInput,
    private val initiation: AndroidInitiationController,
    private val isConnected: () -> Boolean,
    private val isAuthorized: () -> Boolean,
    private val currentSessionId: () -> String? = { null },
    private val onStateChange: (AndroidCaptureState) -> Unit = {},
    private val onInitiation: (AndroidInitiationState) -> Unit = {},
    private val onHandsFreeChange: (Boolean) -> Unit = {},
    private val timers: VoiceTimers,
    private val timings: VoiceTimings = VoiceTimings.Default,
    private val isForeground: () -> Boolean = { true },
    private val wakeSuppressed: () -> Boolean = { false },
    private val prepareMicrophone: ((Boolean) -> Unit) -> Unit = { it(true) },
    private val onVoiceActivity: (Boolean) -> Unit = {},
) {
    /** True from the moment capture starts until it submits, fails or is cancelled. */
    private var captureOpen = false
    private var captureGeneration = 0L
    private var lastPartial: String? = null
    private var silenceTimer: VoiceTimer? = null
    private var finalWaitTimer: VoiceTimer? = null
    private var endingCapture = false
    private var retryTimer: VoiceTimer? = null
    var hasVoiceActivity = false
        private set

    private fun voiceActivity(active: Boolean) {
        if (hasVoiceActivity == active) return
        hasVoiceActivity = active
        onVoiceActivity(active)
    }

    /**
     * The Session capture began in.
     *
     * A transcript belongs to the Session that was live when the user started
     * speaking. If recovery replaces that Session mid-capture, submitting the
     * transcript would attach it to a conversation the user was not having.
     */
    private var captureSessionId: String? = null

    /**
     * Hands-free continuation, per `FR5`.
     *
     * Each window is bounded by the recogniser's own silence endpointing; what
     * continues is the reopening, not a single unbounded microphone.
     */
    var isHandsFree: Boolean = false
        private set

    var lastHandsFreeExit: AndroidHandsFreeExit? = null
        private set
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

    /** Whether the system recognizer may use the network. Observable from composition. */
    val networkRecognitionAllowed: Boolean
        get() = speech.networkRecognitionAllowed

    /**
     * The user's explicit choice after a missing speech pack: allow network
     * recognition and listen again. Never called implicitly.
     */
    fun allowNetworkRecognitionAndRetry() {
        speech.networkRecognitionAllowed = true
        if (!isCapturing) state = AndroidCaptureState.Idle
        beginCapture()
    }

    /** Returns to on-device-only recognition. */
    fun useDeviceRecognitionOnly() {
        speech.networkRecognitionAllowed = false
    }

    fun armHandsFree() {
        if (isHandsFree || !isForeground()) return
        blockingReason()?.let {
            state = AndroidCaptureState.Unavailable(it)
            lastHandsFreeExit = if (it == AndroidCaptureBlock.NotConnected) {
                AndroidHandsFreeExit.SessionEnded
            } else {
                AndroidHandsFreeExit.Failure
            }
            return
        }
        lastHandsFreeExit = null
        isHandsFree = true
        onHandsFreeChange(true)
        continueHandsFree()
    }

    fun disarmHandsFree(reason: AndroidHandsFreeExit = AndroidHandsFreeExit.Disarmed) {
        if (!isHandsFree) return
        isHandsFree = false
        voiceActivity(false)
        lastHandsFreeExit = reason
        retryTimer?.cancel()
        retryTimer = null
        cancelCapture()
        onHandsFreeChange(false)
    }

    /**
     * Continue the conversation after a turn settles.
     *
     * Only a *completed* turn reopens the window. A turn that failed, was
     * interrupted, or lost transport must not, because reopening would present
     * an unsuccessful turn as ready for conversation. Completion also means the
     * response audio has finished draining, so capture never reopens while the
     * device is still speaking.
     */
    fun onTurnSettled(phase: AndroidTurnPhase) {
        if (!isHandsFree) return

        when (phase) {
            AndroidTurnPhase.Complete -> {
                if (isConnected()) {
                    continueHandsFree()
                } else {
                    disarmHandsFree(AndroidHandsFreeExit.SessionEnded)
                }
            }

            AndroidTurnPhase.Disconnected -> disarmHandsFree(AndroidHandsFreeExit.SessionEnded)

            else -> disarmHandsFree(AndroidHandsFreeExit.Failure)
        }
    }

    /** Explicit/tapped capture is foreground-only, even if a permission result arrives late. */
    fun beginCapture() {
        if (!isForeground() || isHandsFree) return
        startCapture()
    }

    fun continueHandsFree() {
        if (isHandsFree && !wakeSuppressed()) startCapture()
    }

    /** Retire the entire recognizer generation, including delayed results and activity callbacks. */
    fun suppressWake() {
        retryTimer?.cancel()
        retryTimer = null
        if (isHandsFree) cancelCapture()
    }

    private fun startCapture() {
        if (isCapturing || (isHandsFree && wakeSuppressed())) return

        blockingReason()?.let { reason ->
            // Fail closed: the microphone is never opened to discover this.
            state = AndroidCaptureState.Unavailable(reason)
            disarmHandsFree(
                if (reason == AndroidCaptureBlock.NotConnected) {
                    AndroidHandsFreeExit.SessionEnded
                } else {
                    AndroidHandsFreeExit.Failure
                },
            )
            return
        }

        captureSessionId = currentSessionId()
        val generation = ++captureGeneration
        captureOpen = true
        lastPartial = null
        endingCapture = false
        state = AndroidCaptureState.Starting
        prepareMicrophone { ready ->
            if (!captureOpen || generation != captureGeneration) return@prepareMicrophone
            if (!ready) {
                closeCapture()
                state = AndroidCaptureState.Failed(AndroidSpeechFailure.AudioUnavailable)
                disarmHandsFree(AndroidHandsFreeExit.Failure)
            } else if (!isForeground() && !isHandsFree) {
                cancelCapture()
            } else if (isHandsFree && wakeSuppressed()) {
                cancelCapture()
            } else {
                speech.start { event -> handle(generation, event) }
            }
        }
    }

    /** Ends capture and waits for the recognizer's final transcript. */
    fun finishCapture() {
        if (!isCapturing || endingCapture) return
        endCapture()
    }

    /**
     * Asks the recognizer to finalize and bounds how long to wait for it. Both
     * the explicit Stop and the silence endpoint come through here, and only
     * the first has any effect, so the two cannot submit one utterance twice.
     */
    private fun endCapture() {
        endingCapture = true
        cancelTimers()
        speech.stop()
        finalWaitTimer = timers.after(timings.finalResultWaitMillis) { finalResultOverdue() }
    }

    /**
     * No final result arrived in time. A partial transcript is never submitted
     * (the recognizer's final is the only transcript that becomes a turn), so
     * the fallback is the no-speech state rather than a guess.
     */
    private fun finalResultOverdue() {
        finalWaitTimer = null
        if (!captureOpen) return
        closeCapture()
        speech.cancel()
        captureSessionId = null
        noSpeech()
    }

    private fun cancelTimers() {
        silenceTimer?.cancel()
        silenceTimer = null
        finalWaitTimer?.cancel()
        finalWaitTimer = null
    }

    private fun noSpeech() {
        if (isHandsFree) {
            state = AndroidCaptureState.Idle
            // Do not spin on a recognizer which immediately reports silence.
            retryTimer?.cancel()
            retryTimer = timers.after(250) {
                retryTimer = null
                continueHandsFree()
            }
        } else {
            state = AndroidCaptureState.Failed(AndroidSpeechFailure.NoSpeechHeard)
            disarmHandsFree(AndroidHandsFreeExit.Silence)
        }
    }

    private fun closeCapture() {
        captureOpen = false
        endingCapture = false
        lastPartial = null
        cancelTimers()
        voiceActivity(false)
    }

    fun cancelCapture() {
        if (!isCapturing) return
        closeCapture()
        speech.cancel()
        captureSessionId = null
        // Returning to Idle drops the provisional transcript with it; a
        // cancelled utterance must leave nothing on screen.
        state = AndroidCaptureState.Idle
    }

    private fun handle(generation: Long, event: AndroidSpeechEvent) {
        // A displaced recognizer must stay retired even after a new capture opens.
        if (!captureOpen || generation != captureGeneration) return
        if (isHandsFree && wakeSuppressed()) {
            suppressWake()
            return
        }
        when (event) {
            AndroidSpeechEvent.Started -> state = AndroidCaptureState.Listening
            AndroidSpeechEvent.SpeechActivity -> voiceActivity(true)

            is AndroidSpeechEvent.Partial -> {
                if (event.text.isNotBlank()) voiceActivity(true)
                state = AndroidCaptureState.Transcribing(event.text)
                // Silence is measured from the last change in the transcript: a
                // repeated partial is the recognizer talking, not the person.
                if (!endingCapture && event.text != lastPartial) {
                    lastPartial = event.text
                    silenceTimer?.cancel()
                    silenceTimer = timers.after(timings.silenceEndpointMillis) {
                        silenceTimer = null
                        if (captureOpen && !endingCapture) endCapture()
                    }
                }
            }

            is AndroidSpeechEvent.Final -> {
                closeCapture()
                submit(event.text)
            }

            is AndroidSpeechEvent.Failed -> {
                closeCapture()
                if (event.reason == AndroidSpeechFailure.NoSpeechHeard) {
                    noSpeech()
                } else {
                    state = AndroidCaptureState.Failed(event.reason)
                    disarmHandsFree(AndroidHandsFreeExit.Failure)
                }
            }

            AndroidSpeechEvent.Cancelled -> {
                closeCapture()
                state = AndroidCaptureState.Idle
            }
        }
    }

    private fun submit(transcript: String) {
        val text = transcript.trim()

        // FR5: exactly "stop" closes the local window silently. It is a command
        // to the doorway, not a turn for Hermes, so it is never submitted.
        if (text.equals(EXACT_STOP, ignoreCase = true)) {
            state = AndroidCaptureState.Idle
            disarmHandsFree(AndroidHandsFreeExit.ExactStop)
            return
        }

        if (text.isEmpty()) {
            captureSessionId = null
            noSpeech()
            return
        }

        // The Session can end while the recognizer is finishing. Submitting
        // into a dead Session would be a turn the user cannot see the result
        // of, so report it rather than sending it.
        if (!isConnected()) {
            captureSessionId = null
            state = AndroidCaptureState.Unavailable(AndroidCaptureBlock.NotConnected)
            disarmHandsFree(AndroidHandsFreeExit.SessionEnded)
            return
        }

        // The Session that heard this utterance must still be the live one.
        val startedIn = captureSessionId
        val liveNow = currentSessionId()
        if (startedIn != null && liveNow != null && startedIn != liveNow) {
            captureSessionId = null
            state = AndroidCaptureState.Unavailable(AndroidCaptureBlock.SessionReplaced)
            disarmHandsFree(AndroidHandsFreeExit.SessionEnded)
            return
        }

        captureSessionId = null
        state = AndroidCaptureState.Submitted(text)
        onInitiation(initiation.initiate(AndroidTurnInput.Typed(text)))
    }
}
