package com.achappell.hermesrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Microphone authorization as the Client sees it.
 *
 * `Unavailable` is distinct from `Denied`: the user refusing the microphone and
 * the device having no recognizer are different problems with different
 * remedies, and collapsing them would produce misleading guidance.
 */
internal enum class AndroidSpeechAuthorization {
    NotDetermined,
    Granted,
    Denied,
    Unavailable,
}

internal enum class AndroidSpeechFailure {
    PermissionRequired,
    RecognizerUnavailable,
    NoSpeechHeard,
    NetworkUnavailable,
    RecognizerBusy,

    /**
     * The recognizer has no speech pack for the language and, unless the user
     * allows it, may not use the network. Recoverable by an explicit,
     * user-initiated choice, never by a silent fallback.
     */
    LanguageUnavailable,

    /** The microphone could not be read, e.g. another app holds it. */
    AudioUnavailable,
    Unknown,
}

/** Normalized capture events. Audio never leaves the recognizer. */
internal sealed interface AndroidSpeechEvent {
    /** The recognizer is listening. Only now may the UI claim capture. */
    data object Started : AndroidSpeechEvent

    data class Partial(val text: String) : AndroidSpeechEvent

    data class Final(val text: String) : AndroidSpeechEvent

    data class Failed(val reason: AndroidSpeechFailure) : AndroidSpeechEvent

    data object Cancelled : AndroidSpeechEvent
}

/**
 * On-device speech capture.
 *
 * The relay refuses binary ingress — "binary ingress is not part of protocol
 * v1; send local-STT text" — so transcription happens here and only the
 * resulting text is ever submitted. No audio is retained, uploaded, or logged.
 */
internal interface AndroidSpeechInput {
    /**
     * Whether the system recognizer may send audio to the network for this
     * process. Off by default: audio stays on the device unless the user turns
     * this on, because the privacy copy promises exactly that. Readable from
     * composition.
     */
    var networkRecognitionAllowed: Boolean

    fun authorization(): AndroidSpeechAuthorization

    /** Begins listening. Events arrive on the main thread. */
    fun start(onEvent: (AndroidSpeechEvent) -> Unit)

    /** Stops listening and asks the recognizer to finalize what it heard. */
    fun stop()

    /** Abandons capture; no transcript is produced. */
    fun cancel()
}

/** Deterministic speech input for tests and for builds with no recognizer. */
internal class FakeSpeechInput(
    private val authorization: AndroidSpeechAuthorization = AndroidSpeechAuthorization.Granted,
) : AndroidSpeechInput {
    private var listener: ((AndroidSpeechEvent) -> Unit)? = null
    override var networkRecognitionAllowed: Boolean by mutableStateOf(false)
    var startCount: Int = 0
        private set
    var stopped: Boolean = false
        private set
    var cancelled: Boolean = false
        private set

    override fun authorization() = authorization

    override fun start(onEvent: (AndroidSpeechEvent) -> Unit) {
        startCount += 1
        listener = onEvent
    }

    override fun stop() {
        stopped = true
    }

    override fun cancel() {
        cancelled = true
        listener = null
    }

    fun emit(event: AndroidSpeechEvent) {
        listener?.invoke(event)
    }
}
