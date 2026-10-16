package com.achappell.hermesrelay

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

@Composable
internal fun ClientDisconnectConfirmation(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("android_disconnect_confirm"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.android_disconnect_confirm_title)) },
        text = { Text(stringResource(R.string.android_disconnect_confirm_body)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("android_disconnect_confirm_yes"), onClick = onConfirm) {
                Text(stringResource(R.string.android_disconnect_confirm_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.android_disconnect_confirm_no)) }
        },
    )
}
