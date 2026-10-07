package com.achappell.hermesrelay

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A clock that only moves when the test (or a [sleeper] poll) says so.
 *
 * Deadlines are crossed by advancing it, never by waiting for them, so a test
 * result does not depend on machine speed (`ANDROID-TEST-01`). Safe to share
 * with a worker thread.
 */
internal class ManualClock(startNanos: Long = 0L) : MonotonicClock {
    private val nanos = AtomicLong(startNanos)

    override fun nanoTime(): Long = nanos.get()

    fun advanceMillis(millis: Long) {
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis))
    }

    /** Sleeping advances the clock instead of blocking the thread. */
    val sleeper: Sleeper = Sleeper { millis -> advanceMillis(millis) }
}
