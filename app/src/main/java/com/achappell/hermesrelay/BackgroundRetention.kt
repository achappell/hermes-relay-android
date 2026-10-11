package com.achappell.hermesrelay

/**
 * What a stop of the app has to keep alive (`ANDROID-HOME-08`).
 *
 * [Reply] holds from a submitted prompt until the reply's audio has drained or
 * the turn ended; it is also what a foreground service has to cover. Hands-free
 * voice retention is a later step and is not represented here.
 */
internal enum class BackgroundRetention { None, Reply }

/** Why retained work ended; [journal] and [trigger] are the content-free journal words. */
internal enum class RetentionEnd(val journal: String, val trigger: String) {
    WorkFinished("workFinished", "backgroundWorkEnded"),
    NotificationStop("notificationStop", "notificationStop"),
    IdleTimeout("idleTimeout", "backgroundWorkEnded"),
    TaskRemoved("taskRemoved", "taskRemoved"),
    FocusLost("interruption", "backgroundWorkEnded"),
}

/**
 * Who is holding the reply's output. Resume needs the set empty, so a focus
 * gain never resumes a reply the user paused. Route loss joins in
 * `ANDROID-HOME-09`.
 */
internal enum class PauseReason { User, Focus }

/** Timings of retained work; injectable so tests cross them by moving a manual timer. */
internal data class RetentionTimings(
    /** A reply held paused this long while stopped is ended (`ANDROID-HOME-08` D2). */
    val pausedHoldMillis: Long = 60_000,
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
)

/** Brings the foreground service up, updates it and ends it; one implementation per platform. */
internal fun interface ForegroundServiceController {
    fun sync(state: VoiceServiceState)

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
