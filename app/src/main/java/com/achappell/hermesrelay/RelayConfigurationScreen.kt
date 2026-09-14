package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Relay configuration surface.
 *
 * The token field is write-only: it is never seeded from storage and never
 * rendered back, so a stored credential cannot be read off the screen.
 */
@Composable
internal fun RelayConfigurationScreen(
    controller: RelayConfigurationController,
    onChanged: () -> Unit,
) {
    var collection by remember { mutableStateOf(controller.collection) }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var clientId by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var errors by remember {
        mutableStateOf<Map<RelayProfileField, RelayProfileError>>(emptyMap())
    }

    fun refresh() {
        collection = controller.collection
        onChanged()
    }

    Column(
        modifier = Modifier.testTag("android_relay_configuration"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.android_relay_config_title),
            style = MaterialTheme.typography.titleMedium,
        )

        collection.profiles.forEach { profile ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (profile.id == collection.selectedId) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(
                            R.string.android_relay_identity,
                            profile.clientId,
                            profile.deviceId,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                controller.select(profile.id)
                                refresh()
                            },
                        ) {
                            Text(stringResource(R.string.android_relay_select))
                        }
                        TextButton(
                            onClick = {
                                controller.delete(profile.id)
                                refresh()
                            },
                        ) {
                            Text(stringResource(R.string.android_relay_delete))
                        }
                    }
                }
            }
        }

        if (collection.profiles.isEmpty()) {
            Text(
                modifier = Modifier.testTag("android_relay_none"),
                text = stringResource(R.string.android_relay_none),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        RelayField(
            tag = "android_relay_endpoint",
            value = endpoint,
            onValueChange = { endpoint = it },
            labelRes = R.string.android_relay_endpoint,
            error = errors[RelayProfileField.Endpoint],
        )
        RelayField(
            tag = "android_relay_client_id",
            value = clientId,
            onValueChange = { clientId = it },
            labelRes = R.string.android_relay_client_id,
            error = errors[RelayProfileField.ClientId],
        )
        RelayField(
            tag = "android_relay_device_id",
            value = deviceId,
            onValueChange = { deviceId = it },
            labelRes = R.string.android_relay_device_id,
            error = errors[RelayProfileField.DeviceId],
        )
        RelayField(
            tag = "android_relay_display_name",
            value = displayName,
            onValueChange = { displayName = it },
            labelRes = R.string.android_relay_display_name,
            error = errors[RelayProfileField.DisplayName],
        )
        RelayField(
            tag = "android_relay_token",
            value = token,
            onValueChange = { token = it },
            labelRes = R.string.android_relay_token,
            error = errors[RelayProfileField.Token],
            masked = true,
        )

        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_relay_save"),
            onClick = {
                val result = controller.save(endpoint, clientId, deviceId, displayName, token)
                errors = result
                if (result.isEmpty()) {
                    endpoint = ""
                    clientId = ""
                    deviceId = ""
                    displayName = ""
                    token = ""
                    refresh()
                }
            },
        ) {
            Text(stringResource(R.string.android_relay_save))
        }
    }
}

@Composable
private fun RelayField(
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

private fun RelayProfileError.messageRes(): Int = when (this) {
    RelayProfileError.Required -> R.string.android_relay_error_required
    RelayProfileError.EndpointMalformed -> R.string.android_relay_error_malformed
    RelayProfileError.EndpointNotSecure -> R.string.android_relay_error_not_secure
    RelayProfileError.EndpointBareAddress -> R.string.android_relay_error_bare_address
    RelayProfileError.StorageUnavailable -> R.string.android_relay_error_storage
}
