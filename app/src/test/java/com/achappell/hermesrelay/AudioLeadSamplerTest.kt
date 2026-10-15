package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioLeadSamplerTest {
    @Test fun background_window_has_exactly_21_actual_elapsed_samples_and_no_duplicate_loop() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        val lead = AudioPlaybackLead(Any(), 1600, 16000, "playing", true)
        val sampler = AudioLeadSampler({ lead }, journal, timer.clock, timer) { lead.stream }
        try {
            sampler.backgroundEntered(lead.stream)
            sampler.backgroundEntered(lead.stream)
            timer.advanceMillis(0)
            repeat(20) { timer.advanceMillis(250) }
            timer.advanceMillis(10000)
            assertEquals(21, journal.lines.size)
            assertEquals("audio output lead_ms=100 t_ms=0 state=playing playing=true", journal.lines.first())
            assertEquals("audio output lead_ms=100 t_ms=5000 state=playing playing=true", journal.lines.last())
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun delayed_dispatch_records_actual_elapsed_and_pause_keeps_observation_only() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        val sampler = AudioLeadSampler({ AudioPlaybackLead(this, 32, 16000, "paused", false) }, journal, timer.clock, timer) { this }
        try {
            sampler.backgroundEntered(this)
            timer.advanceMillis(90)
            timer.advanceMillis(260)
            assertEquals(listOf(
                "audio output lead_ms=2 t_ms=90 state=paused playing=false",
                "audio output lead_ms=2 t_ms=350 state=paused playing=false",
            ), journal.lines)
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun foreground_cancelled_dispatch_cannot_log_or_restart_window() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        var reads = 0
        val sampler = AudioLeadSampler({ reads++; AudioPlaybackLead(this, 0, 16000, "buffering", false) }, journal, timer.clock, timer) { this }
        try {
            sampler.backgroundEntered(this)
            val stale = timer.awaitScheduled()
            sampler.stop()
            stale.runStale()
            assertEquals(0, reads)
            assertTrue(journal.lines.isEmpty())
            sampler.backgroundEntered(this)
            timer.advanceMillis(0)
            assertEquals(1, journal.lines.size)
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun terminal_or_new_stream_stops_old_window_without_adopting_new_reply() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        var lead: AudioPlaybackLead? = AudioPlaybackLead(Any(), 1, 16000, "playing", true)
        val sampler = AudioLeadSampler({ lead }, journal, timer.clock, timer) { lead?.stream }
        try {
            sampler.backgroundEntered(lead!!.stream)
            timer.advanceMillis(0)
            lead = AudioPlaybackLead(Any(), 1, 16000, "playing", true)
            timer.advanceMillis(250)
            assertEquals(1, journal.lines.size)
            sampler.stop()
            sampler.backgroundEntered(lead!!.stream)
            timer.advanceMillis(0)
            lead = null
            timer.advanceMillis(250)
            timer.advanceMillis(5000)
            assertEquals(2, journal.lines.size)
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun native_queries_run_on_diagnostic_worker_not_background_entry_caller() {
        val journal = RecordingJournal()
        val called = CountDownLatch(1)
        val caller = Thread.currentThread()
        var queryThread: Thread? = null
        val sampler = AudioLeadSampler({ queryThread = Thread.currentThread(); called.countDown(); null }, journal, MonotonicClock.Real) { this }
        try {
            sampler.backgroundEntered(this)
            assertTrue(called.await(5, TimeUnit.SECONDS))
            assertTrue(queryThread !== caller)
            assertEquals("hermes-audio-diagnostics", queryThread?.name)
        } finally { sampler.close() }
    }

    @Test fun delayed_initial_dispatch_skips_missed_slots_instead_of_zero_delay_catchup() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        val lead = AudioPlaybackLead(this, 1, 16000, "playing", true)
        val sampler = AudioLeadSampler({ lead }, journal, timer.clock, timer) { lead.stream }
        try {
            sampler.backgroundEntered(this)
            timer.advanceMillis(1000)
            repeat(5) { timer.advanceMillis(0) }
            assertEquals(1, journal.lines.size)
            timer.advanceMillis(250)
            assertEquals(2, journal.lines.size)
            assertTrue(journal.lines.last().contains("t_ms=1250 "))
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun expired_prefinal_and_final_callbacks_do_not_even_query_native_output() {
        for (final in listOf(false, true)) {
            val timer = ManualAudioScheduler()
            val journal = RecordingJournal()
            var queries = 0
            val sampler = AudioLeadSampler({
                queries++
                AudioPlaybackLead(this, 1, 16000, "playing", true)
            }, journal, timer.clock, timer) { this }
            try {
                sampler.backgroundEntered(this)
                if (final) {
                    timer.advanceMillis(0)
                    repeat(19) { timer.advanceMillis(250) }
                }
                val before = queries
                timer.advanceMillis(if (final) 25250 else 5001)
                repeat(5) { timer.advanceMillis(0) }
                assertEquals(before, queries)
                assertEquals(if (final) 20 else 0, journal.lines.size)
            } finally { sampler.close(); timer.shutdownNow() }
        }
    }

    @Test fun final_deadline_accepts_only_one_cadence_of_actual_dispatch_jitter() {
        for (jitter in listOf(1L, 250L, 251L)) {
            val timer = ManualAudioScheduler()
            val journal = RecordingJournal()
            val sampler = AudioLeadSampler({ AudioPlaybackLead(this, 1, 16000, "playing", true) }, journal, timer.clock, timer) { this }
            try {
                sampler.backgroundEntered(this)
                timer.advanceMillis(0)
                repeat(19) { timer.advanceMillis(250) }
                timer.advanceMillis(250 + jitter)
                assertEquals(if (jitter <= 250) 21 else 20, journal.lines.size)
                if (jitter <= 250) assertTrue(journal.lines.last().contains("t_ms=${5000+jitter} "))
            } finally { sampler.close(); timer.shutdownNow() }
        }
    }

    @Test fun query_returning_after_final_jitter_budget_cannot_publish() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        var queries = 0
        val sampler = AudioLeadSampler({
            if (++queries == 21) timer.clock.advanceMillis(251)
            AudioPlaybackLead(this, 1, 16000, "playing", true)
        }, journal, timer.clock, timer) { this }
        try {
            sampler.backgroundEntered(this)
            timer.advanceMillis(0)
            repeat(20) { timer.advanceMillis(250) }
            assertEquals(20, journal.lines.size)
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun transient_inconsistent_snapshot_skips_only_one_slot_not_remaining_window() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        var queries = 0
        val sampler = AudioLeadSampler({
            if (++queries == 1) null else AudioPlaybackLead(this, 16, 16000, "playing", true)
        }, journal, timer.clock, timer) { this }
        try {
            sampler.backgroundEntered(this); timer.advanceMillis(0)
            assertTrue(journal.lines.isEmpty())
            timer.advanceMillis(250)
            assertEquals(1, journal.lines.size)
            assertTrue(journal.lines.single().contains("t_ms=250 "))
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun no_active_stream_or_new_reply_before_initial_dispatch_cannot_start_old_window() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        var stream: Any? = null
        var queries = 0
        val sampler = AudioLeadSampler({
            queries++; AudioPlaybackLead(checkNotNull(stream), 0, 16000, "buffering", false)
        }, journal, timer.clock, timer) { stream }
        try {
            sampler.backgroundEntered(null); timer.advanceMillis(0)
            stream = Any()
            sampler.backgroundEntered(stream); timer.advanceMillis(0)
            assertEquals(0, queries)
            sampler.stop(); sampler.backgroundEntered(stream)
            stream = Any()
            timer.advanceMillis(0)
            assertEquals(0, queries)
        } finally { sampler.close(); timer.shutdownNow() }
    }

    @Test fun blocked_native_read_returning_after_expiry_cannot_publish_or_continue() {
        val timer = ManualAudioScheduler()
        val journal = RecordingJournal()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val sampler = AudioLeadSampler({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            AudioPlaybackLead(this, 1, 16000, "playing", true)
        }, journal, timer.clock, timer) { this }
        val dispatch = Thread { try { timer.advanceMillis(0) } finally { completed.countDown() } }
        try {
            sampler.backgroundEntered(this)
            dispatch.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            timer.clock.advanceMillis(5001)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            timer.advanceMillis(0)
            assertTrue(journal.lines.isEmpty())
        } finally { release.countDown(); dispatch.join(5000); sampler.close(); timer.shutdownNow() }
    }
}
