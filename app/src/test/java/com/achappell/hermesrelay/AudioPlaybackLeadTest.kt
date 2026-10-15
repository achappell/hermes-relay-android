package com.achappell.hermesrelay

import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class AudioPlaybackLeadTest {
    private class Driver : AudioTrackDriver {
        @Volatile var head = 0L
        @Volatile var underruns = 0
        @Volatile var playing = false
        var writeAction: (Int) -> Int = { it }
        val controls = CopyOnWriteArrayList<String>()
        var onPlay: () -> Unit = {}
        var readHead: () -> Long = { head }
        var readUnderruns: () -> Int = { underruns }
        override fun state() = AudioTrack.STATE_INITIALIZED
        override fun play() { controls += "play"; playing = true; onPlay() }
        override fun preparePlayback(queuedFrames: Int) { controls += "prepare" }
        override fun pause() { controls += "pause"; playing = false }
        override fun setVolume(volume: Float): Int { controls += "volume"; return AudioTrack.SUCCESS }
        override fun write(buffer: ByteArray, offset: Int, size: Int) = writeAction(size)
        override fun playbackHeadFrames() = readHead()
        override fun isPlaying() = playing
        override fun underrunCount() = readUnderruns()
        override fun stop() { controls += "stop"; playing = false }
        override fun release() { controls += "release" }
    }
    private class Fixture(val factory: AudioTrackDriverFactory? = null) : AutoCloseable {
        val driver = Driver()
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        val started = CountDownLatch(1)
        val sink = AudioTrackAudioSink(
            driverFactory = factory ?: AudioTrackDriverFactory { _, _ -> driver },
            startupScheduler = timer, clock = timer.clock,
            controlExecutor = Executor { it.run() }, journal = journal,
            minBufferSizeProvider = { 4 },
        )
        fun start() { assertTrue(sink.start(AndroidAudioFormat(16000, 1, 2, "pcm_s16le")) { started.countDown() }) }
        fun queueShort() { sink.write(ByteArray(320)); timer.awaitScheduled() }
        override fun close() { sink.close(); timer.shutdownNow() }
    }

    @Test fun unsigned_head_crosses_signed_boundary_and_full_wrap_without_negative_frames() {
        val counter = PlaybackHeadCounter()
        assertEquals(0x7fff_ffffL, counter.frames(Int.MAX_VALUE))
        assertEquals(0x8000_0000L, counter.frames(Int.MIN_VALUE))
        assertEquals(0xffff_ffffL, counter.frames(-1))
        assertEquals(0x1_0000_0000L, counter.frames(0))
        assertEquals(0x1_0000_0007L, counter.frames(7))
        assertEquals(7L, PlaybackHeadCounter().frames(7))
    }

    @Test fun stalled_head_after_underrun_recovers_true_refilled_queue_not_elapsed_timeline() {
        Fixture().use { f ->
            val sentinel = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.driver.readUnderruns = {
                if (f.sink.snapshotTelemetry().acceptedFrames == 480) {
                    sentinel.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                f.driver.underruns
            }
            try {
                f.start(); f.queueShort()
                f.timer.advanceMillis(1500)
                assertTrue(f.started.await(5, TimeUnit.SECONDS))
                f.driver.head = 160
                f.driver.underruns = 1
                assertEquals(0L, f.sink.playbackLead()!!.frames)
                f.timer.advanceMillis(4000)
                f.sink.write(ByteArray(640))
                f.sink.write(ByteArray(2))
                assertTrue(sentinel.await(5, TimeUnit.SECONDS))
                assertEquals(320L, f.sink.playbackLead()!!.frames)
                assertEquals(20L, f.sink.playbackLead()!!.millis)
                f.driver.head = 240
                assertEquals(240L, f.sink.playbackLead()!!.frames)
            } finally { release.countDown() }
        }
    }

    @Test fun background_samples_do_not_mutate_native_controls_and_report_native_play_state() {
        Fixture().use { f ->
            f.start(); f.queueShort()
            f.timer.advanceMillis(1500)
            assertTrue(f.started.await(5, TimeUnit.SECONDS))
            // A native stop is observable even though the logical stream has already started.
            f.driver.playing = false
            val before = f.driver.controls.toList()
            val samples = ManualAudioScheduler()
            val sampler = AudioLeadSampler(f.sink::playbackLead, f.journal, samples.clock, samples, f.sink::playbackLeadStream)
            try {
                sampler.backgroundEntered(f.sink.playbackLeadStream()); samples.advanceMillis(0)
                repeat(20) { samples.advanceMillis(250) }
                assertEquals(before, f.driver.controls.toList())
                assertEquals(21, f.journal.count("audio output lead_ms="))
                assertTrue(f.journal.lines.last().endsWith("state=playing playing=false"))
            } finally { sampler.close(); samples.shutdownNow() }
        }
    }

    @Test fun cap_and_resume_report_actual_first_trigger_once() {
        Fixture().use { f ->
            f.start(); f.queueShort(); f.timer.advanceMillis(1500)
            assertEquals(listOf("audio output cushion started lead_ms=10 reason=cap"), f.journal.lines.filter { it.contains("cushion") })
            f.sink.pause(); f.sink.resume()
            assertEquals(1, f.journal.count("audio output cushion started"))
        }
        Fixture().use { f ->
            f.start(); f.queueShort(); f.sink.pause(); f.sink.resume()
            assertEquals(listOf("audio output cushion started lead_ms=10 reason=resume"), f.journal.lines.filter { it.contains("cushion") })
        }
    }

    @Test fun cancellation_queries_final_underruns_before_release_and_logs_once() {
        Fixture().use { f ->
            f.start(); f.queueShort()
            f.driver.underruns = 3 // No next PCM/drain poll has observed this count.
            f.sink.cancel(); f.sink.cancel()
            assertEquals(listOf("voice response ended underruns=3"), f.journal.lines.filter { it.startsWith("voice response ended") })
            assertNull(f.sink.playbackLead())
        }
    }

    @Test fun cancelled_or_replaced_snapshot_cannot_publish_stale_diagnostics() {
        Fixture().use { f ->
            f.start(); f.queueShort()
            val old = f.sink.playbackLead()!!
            f.sink.cancel()
            var publications = 0
            old.publish { publications++ }
            f.start()
            old.publish { publications++ }
            assertEquals(0, publications)
        }
    }

    @Test fun replacement_lead_uses_only_new_driver_queue_and_first_play_is_logged_once() {
        val old = Driver()
        val replacement = Driver()
        var creates = 0
        Fixture(AudioTrackDriverFactory { _, _ -> if (creates++ == 0) old else replacement }).use { f ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            replacement.readUnderruns = {
                if (f.sink.snapshotTelemetry().acceptedFrames == 480) {
                    accepted.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                0
            }
            try {
                f.start(); f.queueShort(); f.timer.advanceMillis(1500)
                old.head = 160
                old.writeAction = { AudioTrack.ERROR_DEAD_OBJECT }
                f.sink.write(ByteArray(640)); f.sink.write(ByteArray(2))
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                replacement.head = 80
                assertEquals(240L, f.sink.playbackLead()!!.frames)
                assertEquals(1, f.journal.count("audio output cushion started"))
            } finally { release.countDown() }
        }
    }

    @Test fun short_finish_excludes_padding_and_reports_finish_trigger() {
        Fixture().use { f ->
            f.start(); f.queueShort()
            var leadAtPlay = -1L
            f.driver.onPlay = {
                leadAtPlay = f.sink.playbackLead()!!.frames
                f.driver.head = 160
            }
            val ended = CountDownLatch(1)
            f.sink.finish({ ended.countDown() }, { fail(it) })
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(160L, leadAtPlay)
            assertEquals(listOf("audio output cushion started lead_ms=10 reason=finish"),
                f.journal.lines.filter { it.contains("cushion") })
        }
    }

    @Test fun threshold_and_buffer_full_are_distinct_actual_winning_triggers() {
        Fixture().use { f ->
            f.start(); f.sink.write(ByteArray(32000))
            assertTrue(f.started.await(5, TimeUnit.SECONDS))
            assertEquals("audio output cushion started lead_ms=1000 reason=threshold",
                f.journal.lines.single { it.contains("cushion") })
        }
        Fixture().use { f ->
            f.start(); f.queueShort()
            var remainingFull = true
            f.driver.writeAction = { size -> if (remainingFull) { remainingFull = false; 0 } else size }
            f.sink.write(ByteArray(320))
            assertTrue(f.started.await(5, TimeUnit.SECONDS))
            assertEquals("audio output cushion started lead_ms=10 reason=buffer_full",
                f.journal.lines.single { it.contains("cushion") })
        }
    }

    @Test fun actual_runtime_background_foreground_and_reentry_own_sampling_without_native_controls() {
        Fixture().use { f ->
            val work = QueuedExecutorService()
            val samples = ManualAudioScheduler()
            val binding = AndroidTurnBinding("profile", "connection", "turn")
            var observer: ((AndroidNormalizedEvent) -> Unit)? = null
            val client = object : AndroidClientPort {
                override fun snapshot() = AndroidClientSnapshot(
                    titleRes = BootstrapState.titleRes, descriptionRes = BootstrapState.descriptionRes,
                    boundaryRes = BootstrapState.boundaryRes, selectedProfile = AndroidProfile("profile", "Profile"),
                    authorizationState = AndroidAuthorizationState.Verified,
                )
                override fun reconnect() = AndroidReconnectOutcome.Connected("connection")
                override fun beginTurn(request: AndroidTurnRequest) = AndroidInitiationResult.Accepted(binding)
                override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
                    observer = onEvent
                    return AndroidTurnObservation { observer = null }
                }
                override fun hasActiveTurn() = true
                override fun hasLiveTransport() = true
                override fun playbackLeadStream() = f.sink.playbackLeadStream()
                override fun playbackLead() = f.sink.playbackLead()
            }
            val runtime = HomeRuntime(
                clientPort = client, speechInput = null, historyStore = null,
                postToMain = { it.run() }, workExecutor = work, journal = f.journal,
                clock = samples.clock, leadScheduler = samples,
            )
            try {
                runtime.appStarted(); runtime.recover(); work.drain()
                runtime.initiate(AndroidTurnInput.Typed("fixture request")); work.drain()
                f.start(); f.queueShort(); f.timer.advanceMillis(1500)
                observer!!(AndroidNormalizedEvent.AudioStarted(binding))
                val before = f.driver.controls.toList()
                runtime.appBackgrounded(false)
                samples.advanceMillis(0)
                assertEquals(1, f.journal.count("audio output lead_ms="))
                runtime.appStarted()
                samples.advanceMillis(250)
                assertEquals(1, f.journal.count("audio output lead_ms="))
                runtime.appBackgrounded(false)
                samples.advanceMillis(0)
                assertEquals(2, f.journal.count("audio output lead_ms="))
                assertEquals(before, f.driver.controls.toList())
            } finally { runtime.appStarted(); samples.shutdownNow(); work.shutdownNow() }
        }
    }

    @Test fun concurrent_refill_discards_old_queue_new_head_snapshot_without_blocking_pcm_worker() {
        Fixture().use { f ->
            f.start(); f.queueShort(); f.timer.advanceMillis(1500)
            val enteredHead = CountDownLatch(1)
            val releaseHead = CountDownLatch(1)
            val refillAccepted = CountDownLatch(1)
            val releaseSentinel = CountDownLatch(1)
            val queryDone = CountDownLatch(1)
            var result: AudioPlaybackLead? = null
            f.driver.readHead = {
                enteredHead.countDown()
                check(releaseHead.await(5, TimeUnit.SECONDS))
                f.driver.head
            }
            val query = Thread {
                try { result = f.sink.playbackLead() } finally { queryDone.countDown() }
            }
            try {
                query.start()
                assertTrue(enteredHead.await(5, TimeUnit.SECONDS))
                f.driver.readUnderruns = {
                    if (f.sink.snapshotTelemetry().acceptedFrames == 480) {
                        refillAccepted.countDown()
                        check(releaseSentinel.await(5, TimeUnit.SECONDS))
                    }
                    0
                }
                f.sink.write(ByteArray(640)); f.sink.write(ByteArray(2))
                assertTrue("native query must not block PCM publication", refillAccepted.await(5, TimeUnit.SECONDS))
                f.driver.head = 240
                releaseHead.countDown()
                assertTrue(queryDone.await(5, TimeUnit.SECONDS))
                assertNull("old queued=160 minus newer head=240 is not valid zero lead", result)
                f.driver.readHead = { f.driver.head }
                assertEquals(240L, f.sink.playbackLead()!!.frames)
            } finally {
                releaseHead.countDown(); releaseSentinel.countDown()
                query.join(5000)
            }
        }
    }

    @Test fun native_accepted_refill_is_unavailable_until_its_counter_is_published() {
        Fixture().use { f ->
            f.start(); f.queueShort(); f.timer.advanceMillis(1500)
            val postwrite = CountDownLatch(1)
            val releasePostwrite = CountDownLatch(1)
            val accepted = CountDownLatch(1)
            val releaseSentinel = CountDownLatch(1)
            var blockUnderrun = false
            var writes = 0
            f.driver.writeAction = { size ->
                if (++writes == 1) blockUnderrun = true else {
                    accepted.countDown()
                    check(releaseSentinel.await(5, TimeUnit.SECONDS))
                }
                size
            }
            f.driver.readUnderruns = {
                if (blockUnderrun) {
                    blockUnderrun = false
                    postwrite.countDown()
                    check(releasePostwrite.await(5, TimeUnit.SECONDS))
                }
                0
            }
            try {
                f.sink.write(ByteArray(640))
                assertTrue(postwrite.await(5, TimeUnit.SECONDS))
                f.driver.head = 240
                assertEquals(160, f.sink.snapshotTelemetry().acceptedFrames)
                assertNull("native accepted 320 more frames, but its counter is not published yet", f.sink.playbackLead())
                releasePostwrite.countDown()
                f.sink.write(ByteArray(2))
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                assertEquals(480, f.sink.snapshotTelemetry().acceptedFrames)
            } finally { releasePostwrite.countDown(); releaseSentinel.countDown() }
        }
    }
}
