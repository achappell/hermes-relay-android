package com.achappell.hermesrelay

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Settings -> Troubleshooting -> Share diagnostics (`ANDROID-DIAG-01`).
 *
 * The journal is written to one text file in the app cache at the moment of
 * sharing and handed to the Android share sheet through a [FileProvider] URI.
 * It needs no network and no Hermes Profile. Delivery is the share sheet only;
 * nothing is uploaded.
 */
internal object DiagnosticsShare {
    internal const val EXPORT_DIRECTORY = "diagnostics-export"
    internal const val EXPORT_NAME = "Hermes Relay diagnostics.txt"

    fun authority(context: Context): String = "${context.packageName}.diagnostics"

    /** Writes the export copy and returns it. Overwrites any earlier copy. */
    fun writeExport(directory: File, journal: DiagnosticsJournal, header: DiagnosticsHeader): File {
        directory.mkdirs()
        val file = File(directory, EXPORT_NAME)
        file.writeText(journal.exportText(header))
        return file
    }

    /** Removes a leftover export copy, e.g. at app start. */
    fun clearExport(context: Context) {
        File(context.cacheDir, EXPORT_DIRECTORY).deleteRecursively()
    }

    /** The chooser intent that shares the current journal. */
    fun chooserIntent(context: Context, journal: DiagnosticsJournal): Intent {
        val file = writeExport(
            directory = File(context.cacheDir, EXPORT_DIRECTORY),
            journal = journal,
            header = DiagnosticsHeader.current(context),
        )
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, EXPORT_NAME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, EXPORT_NAME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
