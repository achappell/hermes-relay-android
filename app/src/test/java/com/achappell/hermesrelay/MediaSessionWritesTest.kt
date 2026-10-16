package com.achappell.hermesrelay

import org.junit.Assert.*
import org.junit.Test

class MediaSessionWritesTest {
    @Test fun foreground_without_observation_is_distinct_from_observed_zero() {
        val counter = MediaSessionWrites()
        val foreground = counter.begin()
        counter.written(false)
        assertNull(foreground.baseline)
        assertEquals(0L, counter.end(foreground))
        val background = counter.begin()
        counter.backgroundEntered()
        assertNotNull(background.baseline)
        assertEquals(0L, counter.end(background))
        assertEquals(1L to 0L, counter.snapshot())
    }

    @Test fun completion_after_entry_counts_even_when_request_predates_entry() {
        val counter = MediaSessionWrites()
        val response = counter.begin()
        counter.written(false)
        val queuedBeforeEntry = { counter.written(false) }
        counter.backgroundEntered()
        queuedBeforeEntry()
        assertEquals(1L, counter.end(response))
    }

    @Test fun reentry_resets_baseline_but_foreground_keeps_since_entry_window() {
        val counter = MediaSessionWrites()
        val response = counter.begin()
        counter.backgroundEntered()
        counter.written(false)
        counter.foregroundEntered()
        counter.written(false)
        val queuedBeforeReentry = { counter.written(false) }
        counter.backgroundEntered()
        queuedBeforeReentry()
        counter.written(false)
        assertEquals(2L, counter.end(response))
    }

    @Test fun old_finalization_never_resets_new_response_or_process_counter() {
        val counter = MediaSessionWrites()
        counter.backgroundEntered()
        val first = counter.begin()
        counter.written(false)
        assertEquals(1L, counter.end(first))
        val second = counter.begin()
        counter.written(false)
        assertEquals(1L, counter.end(first))
        counter.written(false)
        assertEquals(3L, counter.end(second))
        counter.written(false)
        assertEquals(3L, counter.end(second))
        assertEquals(4L to 0L, counter.snapshot())
    }

    @Test fun service_recreation_has_no_accounting_reset_and_main_writes_are_observable() {
        val counter = MediaSessionWrites()
        val response = counter.begin()
        counter.backgroundEntered()
        counter.written(false)
        counter.written(true)
        assertEquals(2L, counter.end(response))
        assertEquals(2L to 1L, counter.snapshot())
    }
}
