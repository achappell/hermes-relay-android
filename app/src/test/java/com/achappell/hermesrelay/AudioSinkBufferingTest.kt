package com.achappell.hermesrelay

import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioSinkBufferingTest {
    private val format = AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")
    private class Driver : AudioTrackDriver {
        val plays = AtomicInteger()
        val prepared = CopyOnWriteArrayList<Int>()
        var head = 0L
        var onPlay: () -> Unit = {}
        var readUnderruns: () -> Int = { 0 }
        val released = CountDownLatch(1)
        override fun state() = AudioTrack.STATE_INITIALIZED
        override fun preparePlayback(queuedFrames: Int) { prepared += queuedFrames }
        override fun play() { plays.incrementAndGet(); onPlay() }
        override fun pause() = Unit
        override fun write(buffer: ByteArray, offset: Int, size: Int) = size
        override fun playbackHeadFrames() = head
        override fun underrunCount() = readUnderruns()
        override fun stop() = Unit
        override fun release() { released.countDown() }
    }

    private class Fixture(
        lead: Long = 1_000,
        cap: Long = 1_500,
        controls: java.util.concurrent.Executor? = java.util.concurrent.Executor { it.run() },
        sleeper: Sleeper = Sleeper.Real,
    ) : AutoCloseable {
        val timer = ManualAudioScheduler()
        val drivers = CopyOnWriteArrayList<Driver>()
        val events = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> Driver().also { drivers += it } },
            clock = timer.clock,
            sleeper = sleeper,
            controlExecutor = controls,
            startupScheduler = timer,
            startupLeadMillis = lead,
            maximumHoldMillis = cap,
            minBufferSizeProvider = { 4 },
        )
        val driver get() = drivers.last()
        fun start() = assertTrue(sink.start(AndroidAudioFormat(16_000, 1, 2, "pcm_s16le")) {
            events += "started"
            started.countDown()
        })
        fun awaitStart() = assertTrue("playback starts", started.await(5, TimeUnit.SECONDS))
        override fun close() { sink.close(); timer.shutdownNow() }
    }

    @Test
    fun cap_starts_short_queued_audio_without_more_pcm_or_finish() {
        Fixture().use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            f.timer.awaitScheduled()
            f.timer.advanceMillis(1_499)
            assertFalse(f.sink.snapshotTelemetry().started)
            assertTrue(f.events.isEmpty())
            f.timer.advanceMillis(1)
            f.awaitStart()
            assertEquals(listOf(160), f.driver.prepared)
            assertEquals(listOf("started"), f.events)
        }
    }

    @Test
    fun threshold_starts_before_cap_and_cancels_pending_timer() {
        Fixture().use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            val timer = f.timer.awaitScheduled()
            f.sink.write(ByteArray(31_680))
            f.awaitStart()
            assertEquals(listOf(16_000), f.driver.prepared)
            assertTrue(timer.isCancelled)
            timer.runStale()
            assertEquals(1, f.driver.plays.get())
            assertEquals(listOf("started"), f.events)
        }
    }

    @Test
    fun zero_lead_starts_first_buffer_not_format_admission() {
        Fixture(lead = 0).use { f ->
            f.start()
            assertEquals(0, f.driver.plays.get())
            assertTrue(f.events.isEmpty())
            f.sink.write(ByteArray(2))
            f.awaitStart()
            assertEquals(listOf(1), f.driver.prepared)
        }
    }

    @Test
    fun injected_cap_and_lead_are_behavioral_thresholds() {
        Fixture(lead = 100, cap = 40).use { f ->
            f.start()
            f.sink.write(ByteArray(318))
            f.timer.awaitScheduled()
            f.timer.advanceMillis(39)
            assertTrue(f.events.isEmpty())
            f.timer.advanceMillis(1)
            f.awaitStart()
            assertEquals(listOf(159), f.driver.prepared)
        }
    }

    @Test
    fun pause_keeps_audio_held_past_cap_and_resume_starts_it_without_a_new_cap() {
        Fixture().use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            f.timer.awaitScheduled()
            f.timer.advanceMillis(400)
            f.sink.pause()
            f.timer.advanceMillis(5_000)
            assertEquals(0, f.driver.plays.get())
            assertTrue(f.events.isEmpty())
            f.sink.resume()
            f.awaitStart()
            assertEquals(1, f.driver.plays.get())
            assertEquals(listOf("started"), f.events)
        }
    }

    @Test
    fun cancel_discards_deadline_and_late_dispatch_cannot_play_the_replacement() {
        Fixture().use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            val stale = f.timer.awaitScheduled()
            val old = f.driver
            f.sink.cancel()
            assertTrue(stale.isCancelled)
            f.start()
            stale.runStale()
            assertEquals(0, old.plays.get())
            assertEquals(0, f.driver.plays.get())
            assertTrue(f.events.isEmpty())
            f.sink.write(ByteArray(320))
            f.timer.awaitScheduled()
            f.timer.advanceMillis(1_500)
            f.awaitStart()
            assertEquals(0, old.plays.get())
            assertEquals(1, f.driver.plays.get())
        }
    }

    @Test
    fun replacement_cancels_old_deadline_even_without_explicit_cancel() {
        Fixture().use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            val stale = f.timer.awaitScheduled()
            f.start()
            assertTrue(stale.isCancelled)
            stale.runStale()
            assertTrue(f.drivers.all { it.plays.get() == 0 })
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun short_finish_starts_and_drains_before_cap_without_late_started_event() {
        Fixture().use { f ->
            f.start()
            f.driver.head = 160
            f.sink.write(ByteArray(320))
            val timer = f.timer.awaitScheduled()
            val drained = CountDownLatch(1)
            f.sink.finish({ f.events += "ended"; drained.countDown() }) { f.events += "failed"; drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("started", "ended"), f.events)
            assertTrue(timer.isCancelled)
            timer.runStale()
            assertEquals(listOf("started", "ended"), f.events)
            assertTrue(f.sink.snapshotTelemetry().drained)
        }
    }

    @Test
    fun old_postwrite_query_cannot_pollute_replacement_first_buffer_or_strand_its_cap() {
        Fixture().use { f ->
            f.start()
            val old = f.driver
            val queryEntered = CountDownLatch(1)
            val releaseQuery = CountDownLatch(1)
            val queries = AtomicInteger()
            old.readUnderruns = {
                if (queries.incrementAndGet() == 2) {
                    queryEntered.countDown()
                    check(releaseQuery.await(5, TimeUnit.SECONDS))
                }
                0
            }
            try {
                f.sink.write(ByteArray(320))
                assertTrue("old postwrite native query entered", queryEntered.await(5, TimeUnit.SECONDS))
                f.sink.cancel()
                f.start()
                f.sink.write(ByteArray(320))
                releaseQuery.countDown()
                f.timer.awaitScheduled()
                assertEquals("only replacement PCM is counted", 160, f.sink.snapshotTelemetry().acceptedFrames)
                f.timer.advanceMillis(1_500)
                f.awaitStart()
                assertEquals(0, old.plays.get())
                assertEquals(1, f.driver.plays.get())
            } finally {
                releaseQuery.countDown()
            }
        }
    }

    @Test
    fun cancel_while_native_play_is_returning_suppresses_old_started_callback() {
        Fixture(controls = null).use { f ->
            f.start()
            val old = f.driver
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            old.onPlay = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                f.sink.write(ByteArray(320))
                f.timer.awaitScheduled()
                f.timer.advanceMillis(1_500)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.sink.cancel()
                release.countDown()
                assertTrue("cancelled native work releases track", old.released.await(5, TimeUnit.SECONDS))
                assertTrue(f.events.isEmpty())
            } finally {
                release.countDown()
            }
        }
    }
    @Test
    fun blocked_started_delivery_holds_terminal_order_but_not_native_pause() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            assertTrue(f.sink.start(format) {
                f.events += "started"
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            })
            f.driver.head = 160
            f.sink.write(ByteArray(320))
            f.timer.awaitScheduled()
            val cap = Thread { f.timer.advanceMillis(1_500) }
            cap.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val controlReturned = CountDownLatch(1)
                Thread { f.sink.pause(); f.sink.resume(); controlReturned.countDown() }.start()
                assertTrue("callbacks hold no native lock", controlReturned.await(5, TimeUnit.SECONDS))
                val drained = CountDownLatch(1)
                f.sink.finish({ f.events += "ended"; drained.countDown() }) { drained.countDown() }
                assertEquals(listOf("started"), f.events)
                release.countDown()
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("started", "ended"), f.events)
            } finally {
                release.countDown()
                cap.join(5_000)
                assertFalse(cap.isAlive)
            }
        }
    }

    @Test
    fun resume_releases_submitted_pcm_even_when_control_runs_before_native_acceptance() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(sleeper = Sleeper {
            held.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }).use { f ->
            f.start()
            f.sink.pause()
            try {
                f.sink.write(ByteArray(320))
                assertTrue(held.await(5, TimeUnit.SECONDS))
                assertEquals(0, f.sink.snapshotTelemetry().acceptedFrames)
                f.sink.resume()
                release.countDown()
                f.awaitStart()
                assertEquals("resume starts without advancing the cap clock", 0L, f.timer.clock.nanoTime())
                assertEquals(1, f.driver.plays.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun delayed_old_resume_cannot_release_replacement_cushion() {
        val blockNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controls = java.util.concurrent.Executor {
            if (blockNext.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            it.run()
        }
        Fixture(controls = controls).use { f ->
            f.start()
            f.sink.write(ByteArray(320))
            f.timer.awaitScheduled()
            f.sink.pause()
            blockNext.set(true)
            val resume = Thread { f.sink.resume() }
            resume.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.sink.cancel()
                f.start()
                f.sink.write(ByteArray(320))
                f.timer.awaitScheduled()
                release.countDown()
                resume.join(5_000)
                assertFalse(resume.isAlive)
                assertEquals("old resume cannot skip new stream's cushion", 0, f.driver.plays.get())
                assertTrue(f.events.isEmpty())
                f.timer.advanceMillis(1_500)
                f.awaitStart()
            } finally {
                release.countDown()
                resume.join(5_000)
            }
        }
    }


    @Test
    fun resume_starts_already_queued_subthreshold_audio_without_another_chunk() {
        val secondWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        val plays = AtomicInteger()
        val driver = object : AudioTrackDriver {
            override fun state() = AudioTrack.STATE_INITIALIZED
            override fun play() { plays.incrementAndGet() }
            override fun pause() = Unit
            override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
                if (writes.incrementAndGet() == 2) {
                    secondWrite.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS))
                }
                return size
            }
            override fun playbackHeadFrames() = 0L
            override fun underrunCount() = 0
            override fun stop() = Unit
            override fun release() = Unit
        }
        val timer = ManualAudioScheduler()
        val sink = AudioTrackAudioSink(
            driverFactory = AudioTrackDriverFactory { _, _ -> driver },
            controlExecutor = java.util.concurrent.Executor { it.run() },
            clock = timer.clock,
            startupScheduler = timer,
            minBufferSizeProvider = { 4 },
        )
        try {
            assertTrue(sink.start(format))
            sink.write(ByteArray(4))
            sink.write(ByteArray(4))
            assertTrue("first chunk is accepted", secondWrite.await(5, TimeUnit.SECONDS))
            assertEquals(0, plays.get())
            sink.pause()
            sink.resume()
            assertEquals("explicit resume releases the startup cushion", 1, plays.get())
        } finally {
            releaseWrite.countDown()
            sink.close()
            timer.shutdownNow()
        }
    }
}
