package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standard-only setup (`ANDROID-STD-01`): endpoint, Hermes Profile and token for
 * unmodified Hermes at `/api/ws`, with no Home.
 *
 * Nothing is saved until the connection check (`session.create`) succeeds. The
 * token field is write-only like the relay form's: never seeded from storage,
 * never rendered back, never held in saved instance state. Changing the
 * endpoint or Hermes Profile of an existing Profile requires entering the token
 * again; the old token is never reused for a different identity.
 */
@Composable
internal fun StandardSetupSection(
    controller: RelayConfigurationController,
    editing: RelayProfile? = null,
    onSaved: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable(editing?.id) { mutableStateOf(editing?.endpoint.orEmpty()) }
    var hermesProfile by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.hermesProfile.orEmpty())
    }
    var token by remember(editing?.id) { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<RelayProfileField, RelayProfileError>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_standard_setup"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (editing == null) {
                    R.string.android_standard_setup_title
                } else {
                    R.string.android_standard_setup_edit_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (editing == null) {
                    R.string.android_standard_setup_description
                } else {
                    R.string.android_standard_token_reenter
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        StandardField(
            tag = "android_standard_endpoint",
            value = endpoint,
            onValueChange = { endpoint = it },
            labelRes = R.string.android_standard_endpoint,
            error = errors[RelayProfileField.Endpoint],
        )
        StandardField(
            tag = "android_standard_hermes_profile",
            value = hermesProfile,
            onValueChange = { hermesProfile = it },
            labelRes = R.string.android_standard_hermes_profile,
            error = errors[RelayProfileField.HermesProfile],
        )
        StandardField(
            tag = "android_standard_token",
            value = token,
            onValueChange = { token = it },
            labelRes = R.string.android_standard_token,
            error = errors[RelayProfileField.Token],
            masked = true,
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_standard_save"),
            enabled = !busy,
            onClick = {
                busy = true
                message = R.string.android_standard_checking
                errors = emptyMap()
                val submittedEndpoint = endpoint
                val submittedProfile = hermesProfile
                val submittedToken = token
                scope.launch {
                    // The check opens a socket: never on the main thread.
                    val result = withContext(Dispatchers.IO) {
                        if (editing == null) {
                            controller.setupStandard(
                                submittedEndpoint,
                                submittedProfile,
                                submittedToken,
                            )
                        } else {
                            controller.updateStandard(
                                editing.id,
                                submittedEndpoint,
                                submittedProfile,
                                submittedToken.ifBlank { null },
                            )
                        }
                    }
                    busy = false
                    message = null
                    when (result) {
                        is StandardSetupResult.Saved -> {
                            token = ""
                            if (editing == null) {
                                endpoint = ""
                                hermesProfile = ""
                            }
                            message = R.string.android_standard_saved
                            onSaved()
                        }

                        is StandardSetupResult.Invalid -> errors = result.errors
                        is StandardSetupResult.CheckFailed ->
                            message = result.reason.checkMessageRes()

                        StandardSetupResult.StorageUnavailable ->
                            message = R.string.android_relay_error_storage

                        StandardSetupResult.Blocked -> message = R.string.android_standard_blocked
                        StandardSetupResult.NotStandard ->
                            message = R.string.android_standard_not_standard
                    }
                }
            },
        ) {
            Text(stringResource(R.string.android_standard_save))
        }
        if (onCancel != null) {
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("android_standard_cancel"),
                enabled = !busy,
                onClick = onCancel,
            ) {
                Text(stringResource(R.string.android_standard_cancel))
            }
        }
        message?.let { res ->
            Text(
                modifier = Modifier.testTag("android_standard_message"),
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StandardField(
    tag: String,
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    error: RelayProfileError?,
    masked: Boolean = false,
) {
    Column {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
            value = value,
            onValueChange = onValueChange,
            isError = error != null,
            singleLine = true,
            label = { Text(stringResource(labelRes)) },
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        )
        error?.let {
            Text(
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun AndroidHomeUnavailableReason.checkMessageRes(): Int = when (this) {
    AndroidHomeUnavailableReason.Unauthorized,
    AndroidHomeUnavailableReason.InvalidCredential,
    -> R.string.android_standard_check_unauthorized

    AndroidHomeUnavailableReason.TransportTimeout,
    AndroidHomeUnavailableReason.HermesTimeout,
    -> R.string.android_standard_check_timeout

    AndroidHomeUnavailableReason.ProtocolError,
    AndroidHomeUnavailableReason.ConversationMismatch,
    -> R.string.android_standard_check_protocol

    AndroidHomeUnavailableReason.RequestRejected -> R.string.android_standard_check_rejected
    else -> R.string.android_standard_check_unreachable
}
