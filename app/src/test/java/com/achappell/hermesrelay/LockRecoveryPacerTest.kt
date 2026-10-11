package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** `ANDROID-HOME-04`: the production pacer that stops a backoff wait early. */
class LockRecoveryPacerTest {
    private companion object {
        /** Only bounds a hung test; ordering never depends on it elapsing. */
        const val SAFETY_TIMEOUT_SECONDS = 10L
    }

    private fun elapsedMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun an_unwoken_pause_lasts_about_the_requested_time() {
        val pacer = LockRecoveryPacer()

        val elapsed = elapsedMillis { pacer.pause(150) }

        assertTrue("paused for $elapsed ms", elapsed >= 140)
    }

    @Test
    fun a_wake_from_another_thread_ends_a_running_pause_early() {
        val pacer = LockRecoveryPacer()
        val pausing = CountDownLatch(1)
        var elapsed = 0L
        val waiter = thread {
            elapsed = elapsedMillis {
                pausing.countDown()
                pacer.pause(60_000)
            }
        }
        assertTrue(pausing.await(SAFETY_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        pacer.wake()
        waiter.join(SAFETY_TIMEOUT_SECONDS * 1_000)

        assertTrue("the pause ended after $elapsed ms", elapsed < 30_000)
    }

    @Test
    fun a_wake_before_a_pause_makes_the_next_pause_return_at_once_and_only_once() {
        val pacer = LockRecoveryPacer()

        pacer.wake()
        val first = elapsedMillis { pacer.pause(60_000) }
        val second = elapsedMillis { pacer.pause(100) }

        assertTrue("remembered wake: $first ms", first < 30_000)
        assertTrue("consumed: second pause lasted $second ms", second >= 90)
    }

    @Test
    fun reset_forgets_a_wake_nobody_consumed() {
        val pacer = LockRecoveryPacer()

        pacer.wake()
        pacer.reset()
        val elapsed = elapsedMillis { pacer.pause(100) }

        assertEquals(true, elapsed >= 90)
    }
}
