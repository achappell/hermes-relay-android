package com.achappell.hermesrelay

/**
 * Monotonic time for deadlines and stall detection.
 *
 * Production reads [System.nanoTime]; tests substitute a manual clock so a
 * deadline is crossed by advancing the clock, never by waiting for it.
 */
internal fun interface MonotonicClock {
    fun nanoTime(): Long

    companion object {
        val Real: MonotonicClock = MonotonicClock { System.nanoTime() }
    }
}

/**
 * Pauses the calling thread between polls.
 *
 * Throws [InterruptedException] like [Thread.sleep]. Tests substitute an
 * implementation that advances a manual clock instead of blocking.
 */
internal fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)

    companion object {
        val Real: Sleeper = Sleeper { millis -> Thread.sleep(millis) }
    }
}
