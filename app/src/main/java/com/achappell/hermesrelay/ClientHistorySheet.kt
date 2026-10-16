package com.achappell.hermesrelay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

@Composable
internal fun ClientHistorySheet(
    recorder: AndroidHistoryRecorder,
    exporter: TranscriptExporter,
    profileDisplayName: String?,
    revision: Int,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The file-backed history is reread when clearing recreates this content.
    key(revision) {
        LocalHistoryZone(
            recorder = recorder,
            exporter = exporter,
            profileDisplayName = profileDisplayName,
            onClear = { recorder.clear(); onChanged() },
            sheetVisibility = true,
            onDismiss = onDismiss,
        )
    }
}
