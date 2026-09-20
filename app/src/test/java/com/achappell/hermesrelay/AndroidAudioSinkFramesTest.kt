package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frame accounting for the drain guard.
 *
 * `AudioTrack.playbackHeadPosition` counts frames. The guard waits for it to
 * reach the frames written, so an inflated target strands the turn in Speaking
 * until the guard times out rather than when the audio actually ends.
 */
class AndroidAudioSinkFramesTest {

    @Test
    fun a_mono_frame_is_one_sample() {
        assertEquals(2, bytesPerFrame(1))
    }

    @Test
    fun a_stereo_frame_is_two_samples() {
        assertEquals(4, bytesPerFrame(2))
    }

    @Test
    fun stereo_audio_reports_the_frame_count_the_playhead_will_reach() {
        // One second of 48 kHz stereo: 48000 frames, four bytes each.
        val bytes = 48_000 * 4

        val frames = bytes / bytesPerFrame(2)

        // Dividing by a fixed two would claim 96,000 frames — twice what the
        // playhead can ever report, so the drain guard would never be
        // satisfied.
        assertEquals(48_000, frames)
    }

    @Test
    fun mono_audio_is_unchanged_by_the_channel_aware_arithmetic() {
        val bytes = 48_000 * 2

        assertEquals(48_000, bytes / bytesPerFrame(1))
    }

    @Test
    fun playback_waits_for_a_buffer_before_starting() {
        val driver = FakeAudioTrackDriver(playbackHead = { 8_000 })
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, size ->
                assertTrue(size >= 16_000)
                driver
            },
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        assertTrue(sink.start(AndroidAudioFormat(8_000, 1, 2, "pcm_s16le")))
        assertEquals(0, driver.playCalls.get())
        sink.write(ByteArray(8_000))
        assertTrue(driver.firstWrite.await(1, TimeUnit.SECONDS))
        assertEquals(0, driver.playCalls.get())
        sink.write(ByteArray(8_000))
        sink.finish(drained::countDown) { throw AssertionError("unexpected audio failure") }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(1, driver.playCalls.get())
        sink.close()
    }

    @Test
    fun delayed_drain_requires_playback_advancement() {
        val driver = FakeAudioTrackDriver(playbackHead = { calls -> if (calls < 3) 0 else 2 })
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            drainStallDeadlineMillis = 200,
            drainTimeoutMillis = 1_000,
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)

        assertTrue(sink.start(AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")))
        sink.write(ByteArray(4))
        sink.finish(drained::countDown) { failed.countDown() }

        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertFalse(failed.await(20, TimeUnit.MILLISECONDS))
        assertTrue(driver.playbackHeadCalls.get() >= 3)
        assertEquals(2, sink.snapshotTelemetry().acceptedFrames)
        assertTrue(sink.snapshotTelemetry().drained)
        assertEquals(1, driver.playCalls.get())
        assertFalse(sink.snapshotTelemetry().failed)
        sink.close()
    }

    @Test
    fun a_stranded_playback_head_fails_without_claiming_drain() {
        val driver = FakeAudioTrackDriver(playbackHead = { 0 })
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            drainStallDeadlineMillis = 20,
            drainTimeoutMillis = 200,
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)

        assertTrue(sink.start(AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")))
        sink.write(ByteArray(4))
        sink.finish(drained::countDown) { failed.countDown() }

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(drained.await(20, TimeUnit.MILLISECONDS))
        assertEquals(AudioSinkFailureKind.PlaybackStalled, sink.snapshotTelemetry().failureKind)
        assertFalse(sink.snapshotTelemetry().drained)
        sink.close()
    }

    @Test
    fun an_underrun_fails_even_when_the_playback_head_advances() {
        val underrunCalls = AtomicInteger(0)
        val driver = FakeAudioTrackDriver(
            playbackHead = { 2 },
            underruns = { if (underrunCalls.incrementAndGet() == 1) 0 else 1 },
        )
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            drainStallDeadlineMillis = 200,
            drainTimeoutMillis = 1_000,
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)

        assertTrue(sink.start(AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")))
        sink.write(ByteArray(4))
        sink.finish(drained::countDown) { failed.countDown() }

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(drained.await(20, TimeUnit.MILLISECONDS))
        assertEquals(AudioSinkFailureKind.Underrun, sink.snapshotTelemetry().failureKind)
        sink.close()
    }

    @Test
    fun final_response_frame_drains_before_the_stream_runs_empty() {
        val queued = AtomicInteger(0)
        val position = AtomicInteger(0)
        val driver = object : AudioTrackDriver {
            override fun state() = android.media.AudioTrack.STATE_INITIALIZED
            override fun play() = Unit
            override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
                queued.addAndGet(size / 2)
                return size
            }
            override fun playbackHeadFrames(): Long {
                position.set(2)
                return 2
            }
            override fun underrunCount() = if (position.get() >= queued.get() && queued.get() > 0) 1 else 0
            override fun stop() = Unit
            override fun release() = Unit
        }
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            minBufferSizeProvider = { 4 },
        )
        val drained = CountDownLatch(1)
        val failed = CountDownLatch(1)
        assertTrue(sink.start(AndroidAudioFormat(8_000, 1, 2, "pcm_s16le")))
        sink.write(byteArrayOf(1, 0, 1, 0))
        sink.finish(drained::countDown) { failed.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(1L, failed.count)
        assertEquals(4, sink.snapshotTelemetry().acceptedBytes)
        assertEquals(2, sink.snapshotTelemetry().acceptedFrames)
        assertEquals(0, sink.snapshotTelemetry().underrunCount)
        sink.close()
    }

    private class FakeAudioTrackDriver(
        private val playbackHead: (Int) -> Long = { 0 },
        private val underruns: () -> Int = { 0 },
    ) : AudioTrackDriver {
        val playbackHeadCalls = AtomicInteger(0)
        val playCalls = AtomicInteger(0)
        val firstWrite = CountDownLatch(1)
        private val queuedFrames = AtomicInteger(0)
        private var startThreshold = Int.MAX_VALUE

        override fun state(): Int = android.media.AudioTrack.STATE_INITIALIZED

        override fun play() { playCalls.incrementAndGet() }

        override fun preparePlayback(queuedFrames: Int) { startThreshold = queuedFrames }

        override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
            queuedFrames.addAndGet(size / 2)
            firstWrite.countDown()
            return size
        }

        override fun playbackHeadFrames(): Long =
            if (playCalls.get() > 0 && queuedFrames.get() >= startThreshold) {
                playbackHead(playbackHeadCalls.incrementAndGet())
            } else {
                0
            }

        override fun underrunCount(): Int = underruns()

        override fun stop() = Unit

        override fun release() = Unit
    }
}
