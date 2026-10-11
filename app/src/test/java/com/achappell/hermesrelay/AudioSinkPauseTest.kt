package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ANDROID-HOME-08`: a reply held by the notification's Pause is not a stalled
 * write or drain. The sink freezes its deadlines while held, starts nothing
 * until resumed, and a cancel ends the hold.
 */
class AudioSinkPauseTest {
    private val format = AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")

    private class Driver(private val head: () -> Long) : AudioTrackDriver {
        val playCalls = AtomicInteger(0)
        val pauseCalls = AtomicInteger(0)
        private var queuedFrames = 0

        override fun state(): Int = android.media.AudioTrack.STATE_INITIALIZED

        override fun play() {
            playCalls.incrementAndGet()
        }

        override fun pause() {
            pauseCalls.incrementAndGet()
        }

        override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
            queuedFrames += size / 2
            return size
        }

        override fun playbackHeadFrames(): Long = if (playCalls.get() > 0) head() else 0

        override fun underrunCount(): Int = 0

        override fun stop() = Unit

        override fun release() = Unit
    }

    private fun sink(clock: ManualClock, driver: Driver) = AudioTrackAudioSink(
        clock = clock,
        sleeper = clock.sleeper,
        driverFactory = AudioTrackDriverFactory { _, _ -> driver },
        writeStallDeadlineMillis = 100,
        drainStallDeadlineMillis = 100,
        drainTimeoutMillis = 1_000,
        minBufferSizeProvider = { 4 },
    )

    /** Lets the worker spin on the manual clock until it has run well past every deadline. */
    private fun waitUntilClockPast(clock: ManualClock, millis: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (clock.nanoTime() < TimeUnit.MILLISECONDS.toNanos(millis)) {
            check(System.nanoTime() < deadline) { "worker never advanced the clock" }
            Thread.sleep(1)
        }
    }

    @Test
    fun a_playing_reply_is_held_past_every_deadline_and_continues_on_resume() {
        val clock = ManualClock()
        val driver = Driver(head = { 16_000 })
        val sink = sink(clock, driver)
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)
        assertTrue(sink.start(format))
        // A full second of audio starts playback; then the reply is held.
        sink.write(ByteArray(32_000))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (driver.playCalls.get() == 0) {
            check(System.nanoTime() < deadline)
            Thread.sleep(1)
        }

        sink.pause()
        sink.finish(drained::countDown) { failed.countDown() }
        waitUntilClockPast(clock, 5_000)

        assertEquals("held, not failed", 1L, failed.count)
        assertEquals("held, not drained", 1L, drained.count)
        assertEquals(1, driver.pauseCalls.get())

        sink.resume()

        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(2, driver.playCalls.get())
        assertEquals(1L, failed.count)
        sink.close()
    }

    @Test
    fun a_reply_paused_while_draining_is_not_failed_by_the_drain_deadlines() {
        // The clock moves only when the test says so, so the pause lands mid-drain.
        val clock = ManualClock()
        val headFrames = java.util.concurrent.atomic.AtomicLong(0)
        val polls = AtomicInteger(0)
        val driver = Driver(head = { polls.incrementAndGet(); headFrames.get() })
        val sink = AudioTrackAudioSink(
            clock = clock,
            sleeper = Sleeper { Thread.sleep(1) },
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            writeStallDeadlineMillis = 100,
            drainStallDeadlineMillis = 100,
            drainTimeoutMillis = 1_000,
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)
        assertTrue(sink.start(format))
        sink.write(ByteArray(32_000))
        sink.finish(drained::countDown) { failed.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (polls.get() == 0) {
            check(System.nanoTime() < deadline) { "the drain loop never polled" }
            Thread.sleep(1)
        }

        sink.pause()
        clock.advanceMillis(5_000)
        Thread.sleep(100)

        assertEquals("held, not stalled or timed out", 1L, failed.count)
        assertEquals(1L, drained.count)

        sink.resume()
        headFrames.set(16_000)

        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(1L, failed.count)
        sink.close()
    }

    @Test
    fun a_reply_held_before_playback_starts_starts_only_on_resume() {
        val clock = ManualClock()
        val driver = Driver(head = { 2 })
        val sink = sink(clock, driver)
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)
        assertTrue(sink.start(format))
        sink.write(ByteArray(4))

        sink.pause()
        sink.finish(drained::countDown) { failed.countDown() }
        waitUntilClockPast(clock, 5_000)

        assertEquals(0, driver.playCalls.get())
        assertEquals("nothing to hold yet", 0, driver.pauseCalls.get())
        assertEquals(1L, failed.count)

        sink.resume()

        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(1, driver.playCalls.get())
        sink.close()
    }

    @Test
    fun a_cancel_ends_the_hold_for_the_next_stream() {
        val clock = ManualClock()
        val driver = Driver(head = { 2 })
        val sink = sink(clock, driver)
        assertTrue(sink.start(format))
        sink.pause()
        sink.cancel()

        val drained = CountDownLatch(1)
        assertTrue(sink.start(format))
        sink.write(ByteArray(4))
        sink.finish(drained::countDown) { throw AssertionError("unexpected failure") }

        assertTrue("the new stream is not held", drained.await(5, TimeUnit.SECONDS))
        sink.close()
    }
}
