package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real sink behind the real protocol clients; no PCM is inspected or retained. */
internal class HeldAudioAdmissionFixture : AutoCloseable {
    private val boundary = LinkedBlockingQueue<String>()
    private val release = CountDownLatch(1)
    val plays = AtomicInteger()
    private val volumes = Collections.synchronizedList(mutableListOf<Float>())
    private val driver = object : AudioTrackDriver {
        override fun state() = android.media.AudioTrack.STATE_INITIALIZED
        override fun play() { plays.incrementAndGet(); boundary.put("played") }
        override fun setVolume(volume: Float): Int { volumes += volume; return android.media.AudioTrack.SUCCESS }
        override fun write(buffer: ByteArray, offset: Int, size: Int) = size
        override fun playbackHeadFrames() = 1_000_000L
        override fun underrunCount() = 0
        override fun stop() = Unit
        override fun release() = Unit
    }
    val sink = AudioTrackAudioSink(
        driverFactory = AudioTrackDriverFactory { _, _ -> driver },
        minBufferSizeProvider = { 4 },
        clock = ManualClock(),
        sleeper = Sleeper {
            boundary.put("held")
            check(release.await(5, TimeUnit.SECONDS)) { "admission test did not release held audio" }
        },
    )

    fun assertHeldBeforeFirstPlayback() {
        assertEquals("held", boundary.poll(5, TimeUnit.SECONDS))
        assertEquals(0, plays.get())
        assertEquals(listOf(0.2f), volumes.toList())
    }

    fun releaseWorker() { release.countDown() }
    fun assertPlayed() { assertTrue(plays.get() > 0) }
    override fun close() { release.countDown(); sink.close() }
}
