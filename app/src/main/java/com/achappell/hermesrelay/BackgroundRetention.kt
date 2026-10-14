package com.achappell.hermesrelay

/**
 * What a stop of the app has to keep alive (`ANDROID-HOME-08`).
 *
 * [Reply] holds until audio drains. [VoiceSession] holds a foreground-armed
 * hands-free session or a foreground-started capture.
 */
internal enum class BackgroundRetention { None, Reply, VoiceSession }

/** Why retained work ended; [journal] and [trigger] are the content-free journal words. */
internal enum class RetentionEnd(val journal: String, val trigger: String) {
    WorkFinished("workFinished", "backgroundWorkEnded"),
    NotificationStop("notificationStop", "notificationStop"),
    IdleTimeout("idleTimeout", "backgroundWorkEnded"),
    TaskRemoved("taskRemoved", "taskRemoved"),
    FocusLost("interruption", "backgroundWorkEnded"),
}

/** Independent output holds: only an empty set permits a safe resume. */
internal enum class PauseReason { User, Focus, Route }

/** Timings of retained work; injectable so tests cross them by moving a manual timer. */
internal data class RetentionTimings(
    /** A reply held paused this long while stopped is ended (`ANDROID-HOME-08` D2). */
    val pausedHoldMillis: Long = 60_000,
    val voiceIdleMillis: Long = 60_000,
    /** Provisional acoustic tail; physical speaker/microphone calibration remains a device gate. */
    val outputTailMillis: Long = 1_500,
    /**
     * While retained, the state is re-read this often. Terminal signals and the
     * client clearing its turn are not ordered, so this bounds how long a
     * finished reply can keep the service up.
     */
    val recheckMillis: Long = 1_000,
) {
    companion object {
        val Default = RetentionTimings()
    }
}

/** What the foreground service shows and whether it runs. */
internal data class VoiceServiceState(
    val active: Boolean,
    val paused: Boolean,
    val appStopped: Boolean,
    val microphone: Boolean = false,
    val listening: Boolean = false,
)

/** Brings the foreground service up, updates it and ends it; one implementation per platform. */
internal fun interface ForegroundServiceController {
    fun sync(state: VoiceServiceState)

    /** Completes only after the platform has installed the microphone service type. */
    fun awaitMicrophone(onReady: (Boolean) -> Unit) = onReady(true)

    companion object {
        val None = ForegroundServiceController { }
    }
}

internal enum class FocusChange { LossTransient, LossPermanent, Duck, Gain }

/** Audio focus for one reply; delivers changes on the main thread. */
internal interface AudioFocusController {
    /** Requests focus; false when it was not granted. [allowDuck] picks the gain type once. */
    fun request(allowDuck: Boolean, onChange: (FocusChange) -> Unit): Boolean

    fun abandon()

    object None : AudioFocusController {
        override fun request(allowDuck: Boolean, onChange: (FocusChange) -> Unit) = true

        override fun abandon() = Unit
    }
}

/** No timers: the default where a runtime has no looper (JVM tests); the app passes the main looper's. */
internal object NoVoiceTimers : VoiceTimers {
    override fun after(delayMillis: Long, action: () -> Unit) = VoiceTimer { }
}
