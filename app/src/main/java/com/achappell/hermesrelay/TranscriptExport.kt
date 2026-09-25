package com.achappell.hermesrelay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class AndroidExportFormat {
    PlainText,
    Markdown,
}

/**
 * Renders Local History for sharing.
 *
 * Export carries only what Local History holds — the text of an exchange. No
 * credential, endpoint, Session identity, or audio is included, because a
 * shared transcript travels further than the device it came from.
 */
internal class TranscriptExporter(
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
) {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).apply {
        this.timeZone = timeZone
    }

    fun export(
        history: AndroidLocalHistory,
        format: AndroidExportFormat,
        profileName: String?,
    ): String = when (format) {
        AndroidExportFormat.PlainText -> plainText(history, profileName)
        AndroidExportFormat.Markdown -> markdown(history, profileName)
    }

    private fun plainText(history: AndroidLocalHistory, profileName: String?): String {
        if (history.entries.isEmpty()) return EMPTY_PLAIN

        val header = profileName?.takeIf { it.isNotBlank() }
            ?.let { "Hermes conversation with $it" }
            ?: "Hermes conversation"

        return buildString {
            appendLine(header)
            appendLine()
            history.entries.forEachIndexed { index, entry ->
                if (index > 0) appendLine()
                append('[')
                append(stamp.format(Date(entry.createdAtMillis)))
                append("] ")
                appendLine(entry.role.exportLabel())
                append(entry.text)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    private fun markdown(history: AndroidLocalHistory, profileName: String?): String {
        if (history.entries.isEmpty()) return EMPTY_MARKDOWN

        val header = profileName?.takeIf { it.isNotBlank() }
            ?.let { "# Hermes conversation with $it" }
            ?: "# Hermes conversation"

        return buildString {
            appendLine(header)
            appendLine()
            history.entries.forEach { entry ->
                append("### ")
                append(entry.role.exportLabel())
                append(" - ")
                appendLine(stamp.format(Date(entry.createdAtMillis)))
                appendLine()
                appendLine(entry.text)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    private fun AndroidTranscriptRole.exportLabel(): String = when (this) {
        AndroidTranscriptRole.User -> "You"
        AndroidTranscriptRole.Assistant -> "Hermes"
        AndroidTranscriptRole.Divider -> "—"
    }

    private companion object {
        const val EMPTY_PLAIN = "No conversation yet.\n"
        const val EMPTY_MARKDOWN = "# Hermes conversation\n\n_No conversation yet._\n"
    }
}
