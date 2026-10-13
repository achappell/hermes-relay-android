package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Paused time never consumes a write/drain deadline, including between worker observations. */
class AudioSinkPauseTest {
    private val format = AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun block() {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "test did not release worker" }
        }

        fun await() = assertTrue("worker reached boundary", entered.await(5, TimeUnit.SECONDS))
        fun release() = released.countDown()
    }

    private class Driver : AudioTrackDriver {
        val playCalls = AtomicInteger(0)
        val pauseCalls = AtomicInteger(0)
        val played = CountDownLatch(1)
        val headFrames = AtomicLong(0)
        var readHead: () -> Long = { headFrames.get() }
        var writeBytes: (Int) -> Int = { it }

        override fun state(): Int = android.media.AudioTrack.STATE_INITIALIZED
        override fun play() {
            playCalls.incrementAndGet()
            played.countDown()
        }
        override fun pause() { pauseCalls.incrementAndGet() }
        override fun write(buffer: ByteArray, offset: Int, size: Int): Int = writeBytes(size)
        override fun playbackHeadFrames(): Long = readHead()
        override fun underrunCount(): Int = 0
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class Fixture : AutoCloseable {
        val clock = ManualClock()
        val driver = Driver()
        private val events = LinkedBlockingQueue<String>()
        private val polls = LinkedBlockingQueue<CountDownLatch>()
        val sink = AudioTrackAudioSink(
            clock = clock,
            sleeper = Sleeper {
                val release = CountDownLatch(1)
                polls.put(release)
                events.put("poll")
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release poll" }
            },
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            writeStallDeadlineMillis = 100,
            drainStallDeadlineMillis = 100,
            drainTimeoutMillis = 1_000,
            minBufferSizeProvider = { 4 },
        )

        fun finish() = sink.finish({ events.put("drained") }) { events.put("failed") }
        fun expect(event: String) = assertEquals(event, events.poll(5, TimeUnit.SECONDS))
        fun nextPoll(): CountDownLatch {
            expect("poll")
            assertFalse("held output has not failed", sink.snapshotTelemetry().failed)
            assertFalse("held output has not drained", sink.snapshotTelemetry().drained)
            return checkNotNull(polls.poll())
        }
        override fun close() = sink.close()
    }

    @Test
    fun a_playing_reply_is_held_past_every_deadline_and_continues_on_resume() {
        Fixture().use { f ->
            assertTrue(f.sink.start(format))
            f.sink.write(ByteArray(32_000))
            assertTrue(f.driver.played.await(5, TimeUnit.SECONDS))
            f.sink.pause()
            f.finish()
            val first = f.nextPoll()
            val pauses = f.driver.pauseCalls.get()
            assertTrue("playing output is held at the driver, not just in the deadline clock", pauses > 0)
            f.clock.advanceMillis(5_000)
            f.sink.pause() // Repeated Pause must not lose the beginning of the hold.
            assertEquals("repeated Pause is idempotent", pauses, f.driver.pauseCalls.get())
            first.countDown()
            val second = f.nextPoll()
            f.clock.advanceMillis(5_000)
            f.driver.headFrames.set(16_000)
            f.sink.resume()
            f.sink.resume()
            second.countDown()
            f.expect("drained")
            assertEquals(2, f.driver.playCalls.get())
            assertFalse(f.sink.snapshotTelemetry().failed)
        }
    }

    @Test
    fun a_reply_paused_while_draining_is_not_failed_by_the_drain_deadlines() =
        pauseDuringHeadRead(resumeBeforeReadReturns = false)

    @Test
    fun an_entire_pause_between_drain_observations_does_not_consume_a_deadline() =
        pauseDuringHeadRead(resumeBeforeReadReturns = true)

    private fun pauseDuringHeadRead(resumeBeforeReadReturns: Boolean) {
        val head = Gate()
        Fixture().use { f ->
            val reads = AtomicInteger(0)
            f.driver.readHead = {
                // First read initializes drain state; second follows the pause check.
                if (reads.incrementAndGet() == 2) head.block()
                f.driver.headFrames.get()
            }
            try {
                assertTrue(f.sink.start(format))
                f.sink.write(ByteArray(32_000))
                f.finish()
                head.await()
                f.sink.pause()
                f.clock.advanceMillis(5_000)
                if (resumeBeforeReadReturns) f.sink.resume()
                head.release()
                val poll = f.nextPoll()
                f.driver.headFrames.set(16_000)
                if (!resumeBeforeReadReturns) f.sink.resume()
                poll.countDown()
                f.expect("drained")
            } finally {
                head.release()
            }
        }
    }

    @Test
    fun a_pause_during_a_zero_write_does_not_consume_the_write_deadline() {
        val write = Gate()
        Fixture().use { f ->
            val writes = AtomicInteger(0)
            f.driver.writeBytes = { size ->
                if (writes.incrementAndGet() == 1) { write.block(); 0 } else size
            }
            try {
                assertTrue(f.sink.start(format))
                f.sink.write(ByteArray(32_000))
                f.finish()
                write.await()
                f.sink.pause()
                f.clock.advanceMillis(5_000)
                write.release()
                val poll = f.nextPoll()
                f.driver.headFrames.set(16_000)
                f.sink.resume()
                poll.countDown()
                f.expect("drained")
            } finally {
                write.release()
            }
        }
    }

    @Test
    fun a_reply_held_before_playback_starts_starts_only_on_resume() {
        Fixture().use { f ->
            assertTrue(f.sink.start(format))
            f.sink.pause()
            f.sink.write(ByteArray(4))
            f.finish()
            val first = f.nextPoll()
            f.clock.advanceMillis(5_000)
            first.countDown()
            val second = f.nextPoll()
            assertEquals(0, f.driver.playCalls.get())
            assertEquals(0, f.driver.pauseCalls.get())
            f.driver.headFrames.set(2)
            f.sink.resume()
            second.countDown()
            f.expect("drained")
            assertEquals(1, f.driver.playCalls.get())
        }
    }

    @Test
    fun a_cancel_ends_the_hold_for_the_next_stream() {
        Fixture().use { f ->
            assertTrue(f.sink.start(format))
            f.sink.pause()
            f.sink.write(ByteArray(4))
            val poll = f.nextPoll()
            f.clock.advanceMillis(5_000)
            f.sink.cancel()
            assertTrue(f.sink.start(format))
            f.driver.headFrames.set(2)
            f.sink.write(ByteArray(4))
            f.finish()
            poll.countDown()
            f.expect("drained")
            assertFalse(f.sink.snapshotTelemetry().failed)
        }
    }

    @Test
    fun resumed_output_still_fails_after_the_remaining_unpaused_stall_budget() {
        Fixture().use { f ->
            assertTrue(f.sink.start(format))
            f.sink.write(ByteArray(32_000))
            f.finish()
            var poll = f.nextPoll()
            f.clock.advanceMillis(90)
            f.sink.pause()
            f.clock.advanceMillis(5_000)
            poll.countDown()
            poll = f.nextPoll()
            // This tail of the hold occurs after the worker's last paused observation.
            f.clock.advanceMillis(5_000)
            f.sink.resume()
            poll.countDown()
            poll = f.nextPoll()
            f.clock.advanceMillis(10)
            poll.countDown()
            f.expect("failed")
            assertEquals(AudioSinkFailureKind.PlaybackStalled, f.sink.snapshotTelemetry().failureKind)
        }
    }

    @Test
    fun resumed_writes_still_fail_when_the_unpaused_write_budget_expires() {
        Fixture().use { f ->
            f.driver.writeBytes = { 0 }
            assertTrue(f.sink.start(format))
            f.sink.write(ByteArray(4))
            f.finish()
            val first = f.nextPoll()
            f.sink.pause()
            f.clock.advanceMillis(5_000)
            f.sink.resume()
            first.countDown()
            val second = f.nextPoll()
            f.clock.advanceMillis(100)
            second.countDown()
            f.expect("failed")
            assertEquals(AudioSinkFailureKind.WriteFailure, f.sink.snapshotTelemetry().failureKind)
        }
    }

    @Test
    fun progressing_output_still_reaches_the_overall_unpaused_drain_timeout() {
        Fixture().use { f ->
            f.driver.readHead = { f.driver.headFrames.incrementAndGet() }
            assertTrue(f.sink.start(format))
            f.sink.write(ByteArray(32_000))
            f.finish()
            var poll = f.nextPoll()
            f.sink.pause()
            f.clock.advanceMillis(5_000)
            f.sink.resume()
            repeat(10) { step ->
                f.clock.advanceMillis(100)
                poll.countDown()
                if (step < 9) poll = f.nextPoll()
            }
            f.expect("failed")
            assertEquals(AudioSinkFailureKind.DrainTimeout, f.sink.snapshotTelemetry().failureKind)
        }
    }
}
