package com.achappell.hermesrelay

import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioSinkRecoveryTest {
    private class Driver : AudioTrackDriver {
        val accepted = CopyOnWriteArrayList<Byte>()
        val plays = AtomicInteger()
        val volumes = CopyOnWriteArrayList<Float>()
        var writeResult: (Int, Int) -> Int = { _, size -> size }
        var initialized = true
        var head: (() -> Long)? = null
        var beforeWrite: () -> Unit = {}
        var onPlay: () -> Unit = {}
        var underruns = 0
        var routeChanged: () -> Unit = {}
        val released = CountDownLatch(1)
        override fun observeRoutingChanges(listener: () -> Unit) { routeChanged = listener }
        override fun state() = if (initialized) AudioTrack.STATE_INITIALIZED else AudioTrack.STATE_UNINITIALIZED
        override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
            beforeWrite()
            val count = writeResult(offset, size)
            if (count > 0) accepted.addAll(buffer.slice(offset until offset + count))
            return count
        }
        override fun play() { plays.incrementAndGet(); onPlay() }
        override fun playbackHeadFrames() = head?.invoke() ?: (accepted.size / 2).toLong()
        override fun underrunCount() = underruns
        override fun stop() = Unit
        override fun release() { released.countDown() }
        override fun setVolume(volume: Float): Int { volumes += volume; return AudioTrack.SUCCESS }
    }

    private class Fixture(val first: Driver = Driver(), val second: Driver = Driver()) : AutoCloseable {
        val creates = AtomicInteger()
        val starts = AtomicInteger()
        val timer = ManualAudioScheduler()
        var creation: (Int) -> Driver = { if (it == 1) first else second }
        var beforeControl: () -> Unit = {}
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> creation(creates.incrementAndGet()) },
            minBufferSizeProvider = { 4 },
            controlExecutor = Executor { beforeControl(); it.run() },
            startupScheduler = timer,
        )
        fun start() = assertTrue(sink.start(AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")) { starts.incrementAndGet() })
        fun finish(success: Boolean) {
            val done = CountDownLatch(1)
            var drained = false
            sink.finish({ drained = true; done.countDown() }, { done.countDown() })
            assertTrue("finish terminates", done.await(5, TimeUnit.SECONDS))
            assertEquals("terminal outcome", success, drained)
        }
        override fun close() { sink.close(); timer.shutdownNow() }
    }

    @Test fun dead_object_after_partial_transfer_only_writes_unaccepted_suffix() {
        Fixture().use { f ->
            f.first.writeResult = { offset, _ -> if (offset == 0) 4 else AudioTrack.ERROR_DEAD_OBJECT }
            f.start()
            f.sink.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            f.finish(true)
            assertEquals(2, f.creates.get())
            assertEquals(listOf<Byte>(1, 2, 3, 4), f.first.accepted)
            assertEquals(listOf<Byte>(5, 6, 7, 8), f.second.accepted.take(4))
            assertEquals(8, f.sink.snapshotTelemetry().acceptedBytes)
            assertEquals(1, f.starts.get())
        }
    }

    @Test fun invalid_operation_recovers_without_replaying() {
        Fixture().use { f ->
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_INVALID_OPERATION }
            f.start(); f.sink.write(byteArrayOf(1, 2)); f.finish(true)
            assertEquals(2, f.creates.get())
            assertEquals(listOf<Byte>(1, 2), f.second.accepted.take(2))
        }
    }

    @Test fun noninitialized_track_recovers_before_writing() {
        Fixture().use { f ->
            f.start(); f.first.initialized = false
            f.sink.write(byteArrayOf(1, 2)); f.finish(true)
            assertEquals(2, f.creates.get())
            assertTrue(f.first.accepted.isEmpty())
        }
    }

    @Test fun uncertain_native_queue_continues_suffix_but_finishes_failed() {
        Fixture().use { f ->
            f.first.head = { 0 }
            f.first.writeResult = { offset, _ -> if (offset == 0) 2 else AudioTrack.ERROR_DEAD_OBJECT }
            f.start(); f.sink.write(byteArrayOf(1, 2, 3, 4)); f.finish(false)
            assertEquals(listOf<Byte>(1, 2), f.first.accepted)
            assertEquals(listOf<Byte>(3, 4), f.second.accepted.take(2))
            assertEquals(1, f.starts.get())
        }
    }

    @Test fun failed_recreation_terminates_finish() {
        Fixture().use { f ->
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.creation = { if (it == 1) f.first else error("native unavailable") }
            f.start(); f.sink.write(byteArrayOf(1, 2)); f.finish(false)
            assertEquals(2, f.creates.get())
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun second_dead_track_fails_without_third_creation() {
        Fixture().use { f ->
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.second.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.start(); repeat(5) { f.sink.write(byteArrayOf(1, 2)) }; f.finish(false)
            assertEquals(2, f.creates.get())
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun ordinary_write_error_is_not_retried() {
        Fixture().use { f ->
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_BAD_VALUE }
            f.start(); f.sink.write(byteArrayOf(1, 2)); f.finish(false)
            assertEquals(1, f.creates.get())
        }
    }

    @Test fun replacement_starts_pending_pcm_without_fresh_cushion_and_old_cap_cannot_replay() {
        Fixture().use { f ->
            val played = CountDownLatch(1)
            f.second.onPlay = { played.countDown() }
            f.start()
            f.sink.write(byteArrayOf(1, 2))
            val staleCap = f.timer.awaitScheduled()
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.sink.write(byteArrayOf(3, 4))
            assertTrue(played.await(5, TimeUnit.SECONDS))
            assertTrue(staleCap.isCancelled)
            staleCap.runStale()
            f.finish(true)
            assertEquals(1, f.second.plays.get())
            assertEquals(1, f.starts.get())
        }
    }

    @Test fun recovery_preserves_pause_and_duck_even_when_invalidation_arrives_during_write() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val recreated = CountDownLatch(1)
            f.first.beforeWrite = { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.creation = { if (it == 1) f.first else f.second.also { recreated.countDown() } }
            f.start(); f.sink.write(byteArrayOf(1, 2))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.sink.pause(); f.sink.setDucked(true); proceed.countDown()
            assertTrue(recreated.await(5, TimeUnit.SECONDS))
            // Release is after replacement publication and after its initial volume is set.
            assertTrue(f.first.released.await(5, TimeUnit.SECONDS))
            assertTrue(f.second.accepted.isEmpty())
            assertEquals(0, f.second.plays.get())
            assertEquals(listOf(0.2f), f.second.volumes)
            f.sink.resume(); f.finish(true)
            assertEquals(1, f.second.plays.get())
            assertEquals(1, f.starts.get())
        }
    }

    @Test fun healthy_route_notifications_do_not_replace_a_track() {
        Fixture().use { f ->
            f.start(); repeat(5) { f.first.routeChanged() }
            f.sink.write(byteArrayOf(1, 2)); f.finish(true)
            assertEquals(1, f.creates.get())
        }
    }

    @Test fun burst_of_obsolete_route_invalidations_coalesces_to_one_replacement() {
        Fixture().use { f ->
            f.start(); f.first.initialized = false
            repeat(5) { f.first.routeChanged() }
            f.sink.write(byteArrayOf(1, 2)); f.finish(true)
            assertEquals(2, f.creates.get())
            assertEquals(1, f.starts.get())
        }
    }

    @Test fun cancellation_while_recreation_is_blocked_releases_unpublished_driver_without_callbacks() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            f.creation = {
                if (it == 1) f.first else {
                    entered.countDown()
                    assertTrue(proceed.await(5, TimeUnit.SECONDS))
                    f.second
                }
            }
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.start(); f.sink.write(byteArrayOf(1, 2))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // cancel publishes ownership before its synchronous test release waits for the native lock.
            val cancelled = CountDownLatch(1)
            val cancelPublished = CountDownLatch(1)
            f.beforeControl = { cancelPublished.countDown() }
            val cancelThread = Thread { f.sink.cancel(); cancelled.countDown() }
            cancelThread.start()
            assertTrue(cancelPublished.await(5, TimeUnit.SECONDS))
            // A route callback cannot acquire a fresh generation on behalf of the old track.
            f.first.routeChanged()
            proceed.countDown()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(f.second.released.await(5, TimeUnit.SECONDS))
            assertTrue(f.second.accepted.isEmpty())
            assertEquals(0, f.second.plays.get())
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun native_head_unavailable_is_not_proof_of_delivered_audio() {
        Fixture().use { f ->
            f.first.head = { error("dead native head") }
            f.first.writeResult = { offset, _ -> if (offset == 0) 2 else AudioTrack.ERROR_DEAD_OBJECT }
            f.start(); f.sink.write(byteArrayOf(1, 2, 3, 4)); f.finish(false)
            assertEquals(listOf<Byte>(3, 4), f.second.accepted.take(2))
        }
    }

    @Test fun unusable_replacement_terminates_without_another_attempt() {
        Fixture().use { f ->
            f.second.initialized = false
            f.first.writeResult = { _, _ -> AudioTrack.ERROR_DEAD_OBJECT }
            f.start(); f.sink.write(byteArrayOf(1, 2)); f.finish(false)
            assertEquals(2, f.creates.get())
            assertTrue(f.second.released.await(5, TimeUnit.SECONDS))
            assertEquals(0, f.starts.get())
        }
    }

    @Test fun replacement_drains_at_its_response_head_without_counting_old_prefix_or_padding() {
        Fixture().use { f ->
            val oldFrames = 2_000 // Larger than the 1,600-frame silent drain tail.
            f.first.writeResult = { offset, _ -> if (offset == 0) oldFrames * 2 else AudioTrack.ERROR_DEAD_OBJECT }
            f.first.head = { oldFrames.toLong() }
            f.second.head = { 2 } // Exactly replacement response frames; padding never advances this head.
            f.start()
            f.sink.write(ByteArray(oldFrames * 2 + 4))
            f.finish(true)
            assertEquals(oldFrames + 2, f.sink.snapshotTelemetry().acceptedFrames)
            assertEquals(2, f.creates.get())
        }
    }

    @Test fun underruns_accumulate_across_both_native_tracks() {
        Fixture().use { f ->
            f.first.writeResult = { offset, _ -> if (offset == 0) 2 else AudioTrack.ERROR_DEAD_OBJECT }
            f.second.beforeWrite = { f.second.underruns = 2 }
            f.start()
            f.first.underruns = 5
            f.sink.write(byteArrayOf(1, 2, 3, 4))
            f.finish(true)
            assertEquals(7, f.sink.snapshotTelemetry().underrunCount)
        }
    }
}
