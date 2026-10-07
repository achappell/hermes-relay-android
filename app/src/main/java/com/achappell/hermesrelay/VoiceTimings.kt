package com.achappell.hermesrelay

import android.os.Handler
import android.os.Looper

/**
 * The numbers that decide when a spoken turn ends, in one place so tap-to-talk
 * and hands-free cannot drift apart (`ANDROID-VOICE-03`). iOS: 1.5 s silence
 * endpoint, 2 s wait for the final result.
 */
internal data class VoiceTimings(
    /**
     * A pause this long after the last change in the transcript ends the
     * utterance. Enforced locally from partial-result silence because several
     * recognizers ignore the `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS`
     * extras; the same value is also passed to the recognizer.
     */
    val silenceEndpointMillis: Long = 1_500,
    /** How long to wait for the recognizer's final result once the endpoint is reached. */
    val finalResultWaitMillis: Long = 2_000,
    /** Passed as `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`. */
    val minimumUtteranceMillis: Long = 500,
) {
    companion object {
        val Default = VoiceTimings()
    }
}

/** A scheduled callback that can be withdrawn. */
internal fun interface VoiceTimer {
    fun cancel()
}

/**
 * Runs a callback after a delay on the thread that drives capture (main).
 * Production uses the main looper; tests advance a manual instance so a
 * deadline is crossed by moving time, not by waiting for it.
 */
internal fun interface VoiceTimers {
    fun after(delayMillis: Long, action: () -> Unit): VoiceTimer
}

/** [VoiceTimers] on the main looper. The handler is created on first use. */
internal object MainLooperVoiceTimers : VoiceTimers {
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    override fun after(delayMillis: Long, action: () -> Unit): VoiceTimer {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return VoiceTimer { handler.removeCallbacks(runnable) }
    }
}
