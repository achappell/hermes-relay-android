package com.achappell.hermesrelay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ANDROID-DIAG-01`: append, cap, persistence, export format and the bounded
 * writer. A fake clock and a manual executor mean nothing here sleeps.
 */
class DiagnosticsJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun file() = File(folder.root, "diagnostics/connection-journal.jsonl")
    private val header = DiagnosticsHeader(
        appVersion = "0.3.1",
        versionCode = 301,
        androidRelease = "14",
        apiLevel = 34,
        model = "Pixel 6a",
        exportedAtMillis = 1_700_000_100_000L,
    )

    private fun journal(
        executor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
        maxEntries: Int = FileDiagnosticsJournal.DEFAULT_MAX_ENTRIES,
        queueCapacity: Int = FileDiagnosticsJournal.DEFAULT_QUEUE_CAPACITY,
    ) = FileDiagnosticsJournal(
        file = file(),
        writer = executor,
        maxEntries = maxEntries,
        queueCapacity = queueCapacity,
        clock = { now++ },
    )

    @Test
    fun entries_are_appended_in_order() {
        val journal = journal()

        journal.record("app phase=started")
        journal.record("home connect conversation.open result=connected")

        assertEquals(
            listOf("app phase=started", "home connect conversation.open result=connected"),
            journal.snapshot().map { it.event },
        )
    }

    @Test
    fun the_journal_keeps_the_newest_two_thousand_entries() {
        val journal = journal()

        repeat(2_100) { journal.record("entry=$it") }

        val kept = journal.snapshot()
        assertEquals(2_000, kept.size)
        assertEquals("entry=100", kept.first().event)
        assertEquals("entry=2099", kept.last().event)
    }

    @Test
    fun a_trimmed_journal_stays_capped_after_a_relaunch() {
        val first = journal(maxEntries = 10)
        repeat(40) { first.record("entry=$it") }
        first.snapshot()

        val relaunched = journal(maxEntries = 10)

        assertEquals((30..39).map { "entry=$it" }, relaunched.snapshot().map { it.event })
    }

    @Test
    fun entries_survive_a_new_instance() {
        journal().apply {
            record("app phase=started")
            snapshot()
        }

        val relaunched = journal()
        relaunched.record("app phase=stopped")

        assertEquals(
            listOf("app phase=started", "app phase=stopped"),
            relaunched.snapshot().map { it.event },
        )
    }

    @Test
    fun a_line_torn_by_a_crash_is_skipped() {
        journal().apply {
            record("kept=1")
            snapshot()
        }
        file().appendText("{\"t\":\"2026-10-0")

        val relaunched = journal()

        assertEquals(listOf("kept=1"), relaunched.snapshot().map { it.event })
    }

    @Test
    fun recording_only_queues_until_the_writer_runs() {
        val executor = ManualExecutor()
        val journal = journal(executor)

        journal.record("app phase=started")

        assertFalse("record must not touch the disk", file().exists())
        assertEquals(1, executor.pending)
        executor.runAll()
        assertTrue(file().exists())
    }

    @Test
    fun queue_overflow_drops_the_oldest_and_leaves_a_marker() {
        val executor = ManualExecutor()
        val journal = journal(executor, queueCapacity = 3)

        repeat(6) { journal.record("entry=$it") }
        executor.runAll()

        assertEquals(
            listOf("journal dropped=3", "entry=3", "entry=4", "entry=5"),
            journal.snapshot().map { it.event },
        )
    }

    @Test
    fun the_export_has_a_header_then_one_json_line_per_entry() {
        val journal = journal()
        journal.record("app phase=started")
        journal.record("home bridge transport lost")

        val lines = journal.exportText(header).trimEnd('\n').split('\n')

        assertEquals(3, lines.size)
        val head = JSONObject(lines[0])
        assertEquals("hermes-relay-diagnostics/1", head.getString("kind"))
        assertEquals("0.3.1", head.getString("app_version"))
        assertEquals(301L, head.getLong("version_code"))
        assertEquals("Android 14 (API 34)", head.getString("system"))
        assertEquals("Pixel 6a", head.getString("model"))
        assertEquals("2023-11-14T22:15:00Z", head.getString("t"))
        assertEquals("app phase=started", JSONObject(lines[1]).getString("e"))
        assertEquals("home bridge transport lost", JSONObject(lines[2]).getString("e"))
        assertTrue(JSONObject(lines[1]).getString("t").endsWith("Z"))
    }

    @Test
    fun the_header_carries_no_identity_beyond_build_and_device_model() {
        val keys = header.toJson().keys().asSequence().toSet()

        assertEquals(setOf("kind", "app_version", "version_code", "system", "model", "t"), keys)
    }

    @Test
    fun an_empty_journal_still_exports_offline_with_no_profile() {
        val text = journal().exportText(header)

        assertEquals(1, text.trimEnd('\n').split('\n').size)
        assertTrue(text.startsWith("{"))
    }

    @Test
    fun a_newline_in_an_event_cannot_forge_a_second_entry() {
        val journal = journal()

        journal.record("first\nforged")

        assertEquals(listOf("first forged"), journal.snapshot().map { it.event })
    }

    @Test
    fun an_event_containing_a_backslash_and_slash_survives_the_line_encoding() {
        val journal = journal()
        journal.record("path a\\/b")

        val relaunched = journal()

        assertEquals(listOf("path a\\/b"), relaunched.snapshot().map { it.event })
    }

    @Test
    fun the_debug_echo_writes_every_line_and_still_journals_it() {
        val echoed = mutableListOf<String>()
        val inner = RecordingJournal()
        val journal = LogcatEchoJournal(inner) { echoed += it }

        journal.record("app phase=started")

        assertEquals(listOf("app phase=started"), echoed)
        assertEquals(listOf("app phase=started"), inner.lines)
    }

    @Test
    fun the_share_export_is_one_text_file_that_a_second_share_overwrites() {
        val journal = RecordingJournal().apply { record("app phase=started") }
        val directory = File(folder.root, "cache/diagnostics-export")

        val first = DiagnosticsShare.writeExport(directory, journal, header)
        journal.record("app phase=stopped")
        val second = DiagnosticsShare.writeExport(directory, journal, header)

        assertEquals(first, second)
        assertEquals("Hermes Relay diagnostics.txt", second.name)
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(second.readText().contains("app phase=stopped"))
    }
}
