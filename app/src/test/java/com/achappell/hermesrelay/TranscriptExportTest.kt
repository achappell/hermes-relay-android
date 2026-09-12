package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TranscriptExportTest {
    private val exporter = TranscriptExporter(
        timeZone = TimeZone.getTimeZone("UTC"),
        locale = Locale.UK,
    )

    private val history = AndroidLocalHistory(
        entries = listOf(
            AndroidTranscriptEntry(AndroidTranscriptRole.User, "check the weather", 1_700_000_000_000),
            AndroidTranscriptEntry(AndroidTranscriptRole.Assistant, "Rain later", 1_700_000_060_000),
        ),
    )

    @Test
    fun plain_text_carries_both_sides_in_order() {
        val text = exporter.export(history, AndroidExportFormat.PlainText, "Amanda")

        assertTrue(text.startsWith("Hermes conversation with Amanda"))
        assertTrue(text.contains("You"))
        assertTrue(text.contains("check the weather"))
        assertTrue(text.contains("Hermes"))
        assertTrue(text.contains("Rain later"))
        assertTrue(
            "the exchange is out of order",
            text.indexOf("check the weather") < text.indexOf("Rain later"),
        )
    }

    @Test
    fun markdown_uses_headings_per_entry() {
        val text = exporter.export(history, AndroidExportFormat.Markdown, "Amanda")

        assertTrue(text.startsWith("# Hermes conversation with Amanda"))
        assertTrue(text.contains("### You - "))
        assertTrue(text.contains("### Hermes - "))
    }

    @Test
    fun an_empty_history_exports_an_honest_placeholder() {
        assertEquals(
            "No conversation yet.\n",
            exporter.export(AndroidLocalHistory(), AndroidExportFormat.PlainText, "Amanda"),
        )
        assertTrue(
            exporter.export(AndroidLocalHistory(), AndroidExportFormat.Markdown, null)
                .contains("_No conversation yet._"),
        )
    }

    @Test
    fun an_export_never_carries_a_credential_endpoint_or_session() {
        val text = exporter.export(history, AndroidExportFormat.Markdown, "Amanda") +
            exporter.export(history, AndroidExportFormat.PlainText, "Amanda")

        listOf("token", "wss://", "session", "bearer", "pcm").forEach { forbidden ->
            assertTrue(
                "an export leaked $forbidden",
                !text.contains(forbidden, ignoreCase = true),
            )
        }
    }

    @Test
    fun a_missing_profile_name_still_produces_a_usable_header() {
        val text = exporter.export(history, AndroidExportFormat.PlainText, null)
        assertTrue(text.startsWith("Hermes conversation\n"))
    }
}
