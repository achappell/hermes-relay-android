package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A connection check uses a temporary private credential store, never a saved Home token. */
@Composable
internal fun StandardSetupSection(
    controller: RelayConfigurationController,
    editing: RelayProfile? = null,
    onChecking: (Boolean) -> Unit = {},
    onSaved: () -> Unit,
) {
    var endpoint by remember(editing?.id) { mutableStateOf(editing?.endpoint.orEmpty()) }
    var name by remember(editing?.id) { mutableStateOf(editing?.displayName.orEmpty()) }
    var profile by remember(editing?.id) { mutableStateOf(editing?.hermesProfile ?: "default") }
    // Neither saved state nor a UI field ever reads an existing secret.
    var token by remember(editing?.id) { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.android_standard_limits))
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; token = "" },
            label = { Text(stringResource(R.string.android_standard_endpoint)) },
            enabled = !checking,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("android_standard_endpoint"),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.android_standard_name)) },
            enabled = !checking,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("android_standard_name"),
        )
        OutlinedTextField(
            value = profile,
            onValueChange = { profile = it },
            label = { Text(stringResource(R.string.android_standard_profile)) },
            enabled = !checking,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("android_standard_profile"),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.android_standard_token)) },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !checking,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("android_standard_token"),
        )
        message?.let { Text(stringResource(it), Modifier.testTag("android_standard_setup_result")) }
        FilledTonalButton(
            enabled = !checking,
            modifier = Modifier.fillMaxWidth().testTag("android_standard_save"),
            onClick = {
                if (StandardEndpoint.validate(endpoint) != null || name.isBlank() || token.isBlank()) {
                    message = R.string.android_standard_invalid
                } else {
                    checking = true
                    onChecking(true)
                    message = null
                    scope.launch {
                        try {
                            val candidate = RelayProfile(
                                id = editing?.id ?: "standard-setup-check",
                                endpoint = StandardEndpoint.canonicalize(endpoint),
                                clientId = "",
                                deviceId = "",
                                displayName = name.trim(),
                                mode = RelayConnectionMode.Standard,
                                hermesProfile = StandardEndpoint.normalizeHermesProfile(profile),
                            )
                            val verified = withContext(Dispatchers.IO) {
                                val credentials = InMemoryRelayCredentialStore()
                                credentials.putStandardCredential(candidate.id, token)
                                val candidateCollection = { RelayProfileCollection(listOf(candidate), candidate.id) }
                                val client = controller.standardSetupHttpClient?.let { httpClient ->
                                    OkHttpStandardSessionClient(
                                        collection = candidateCollection,
                                        credentials = credentials,
                                        restartStore = InMemoryStandardRestartStore(),
                                        httpClient = httpClient,
                                    )
                                } ?: OkHttpStandardSessionClient(
                                    collection = candidateCollection,
                                    credentials = credentials,
                                    restartStore = InMemoryStandardRestartStore(),
                                )
                                try {
                                    client.reconnect() is AndroidReconnectOutcome.Connected
                                } catch (_: Exception) {
                                    false
                                } finally {
                                    client.close()
                                    credentials.delete(candidate.id)
                                }
                            }
                            if (!verified) {
                                message = R.string.android_standard_failed
                            } else {
                                val errors = controller.saveStandard(endpoint, name, profile, token, editing?.id)
                                if (errors.isEmpty()) {
                                    token = ""
                                    onSaved()
                                } else {
                                    message = R.string.android_standard_save_failed
                                }
                            }
                        } finally {
                            checking = false
                            onChecking(false)
                        }
                    }
                }
            },
        ) {
            Text(stringResource(if (checking) R.string.android_standard_checking else R.string.android_standard_check_save))
        }
    }
}
