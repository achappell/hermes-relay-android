package com.achappell.hermesrelay

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.achappell.hermesrelay.ui.theme.HermesShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Relay configuration surface.
 *
 * The token field is write-only: it is never seeded from storage and never
 * rendered back, so a stored credential cannot be read off the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelayConfigurationScreen(
    controller: RelayConfigurationController,
    homeAdministration: HomeDeviceAdministrationController? = null,
    onHomeAdministrationChanged: () -> Unit = {},
    onHomeCredentialChanged: () -> Unit = {},
    homePairing: HomeClientPairingCoordinator? = null,
    pendingPairingLink: String? = null,
    onPendingPairingLinkConsumed: () -> Unit = {},
    onShareDiagnostics: (() -> Unit)? = null,
    versionLabel: String? = null,
    onChanged: () -> Unit,
    onSetupCompleted: () -> Unit = {},
    canReconfigure: Boolean = true,
) {
    if (!canReconfigure) {
        Text(stringResource(R.string.android_connection_switch_blocked))
        return
    }
    var setupMode by rememberSaveable {
        mutableStateOf(controller.collection.selected?.mode?.takeIf { it == RelayConnectionMode.HomeBridge })
    }
    var editingStandardId by rememberSaveable { mutableStateOf<String?>(null) }
    var setupChecking by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf(controller.collection) }
    val editingStandard = collection.profiles.firstOrNull { it.id == editingStandardId }
    var pairedHomesRevision by remember { mutableStateOf(0) }

    fun refresh() {
        collection = controller.collection
        pairedHomesRevision += 1
        onChanged()
    }

    fun completeSetup() {
        refresh()
        onSetupCompleted()
    }

    Column(
        modifier = Modifier.testTag("android_relay_configuration"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionCard(SettingsSection.Profiles) {
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
                        Text(stringResource(when (profile.mode) {
                            RelayConnectionMode.HomeBridge -> R.string.android_mode_home
                            RelayConnectionMode.Standard -> R.string.android_mode_standard
                            RelayConnectionMode.Legacy -> R.string.android_mode_legacy
                        }))
                        Text(profile.endpoint, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = stringResource(
                                R.string.android_relay_identity,
                                profile.clientId,
                                profile.deviceId,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = !setupChecking && profile.mode != RelayConnectionMode.Legacy,
                                onClick = {
                                    controller.select(profile.id)
                                    completeSetup()
                                },
                            ) {
                                Text(stringResource(R.string.android_relay_select))
                            }
                            TextButton(
                                enabled = !setupChecking,
                                onClick = {
                                    controller.delete(profile.id)
                                    completeSetup()
                                },
                            ) {
                                Text(stringResource(R.string.android_relay_delete))
                            }
                            if (profile.mode == RelayConnectionMode.Standard) {
                                TextButton(
                                    enabled = !setupChecking,
                                    onClick = {
                                        editingStandardId = profile.id
                                        setupMode = RelayConnectionMode.Standard
                                    },
                                ) { Text(stringResource(R.string.android_standard_edit)) }
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

            Text(stringResource(R.string.android_connection_choose))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !setupChecking,
                    onClick = { setupMode = RelayConnectionMode.HomeBridge; editingStandardId = null },
                    modifier = Modifier.testTag("android_choose_home"),
                ) { Text(stringResource(R.string.android_mode_home)) }
                TextButton(
                    enabled = !setupChecking,
                    onClick = { setupMode = RelayConnectionMode.Standard; editingStandardId = null },
                    modifier = Modifier.testTag("android_choose_standard"),
                ) { Text(stringResource(R.string.android_mode_standard)) }
            }
            if (setupMode == RelayConnectionMode.Standard) {
                StandardSetupSection(
                    controller = controller,
                    editing = editingStandard,
                    onChecking = { setupChecking = it },
                    onSaved = ::completeSetup,
                )
            }
        }

        if (setupMode == RelayConnectionMode.HomeBridge) {
            homePairing?.let { coordinator ->
                SettingsSectionCard(SettingsSection.HomePairing) {
                    HomePairingSection(
                        coordinator = coordinator,
                        pendingLink = pendingPairingLink,
                        onPendingLinkConsumed = onPendingPairingLinkConsumed,
                        onPaired = ::completeSetup,
                    )
                    PairedHomesSection(
                        coordinator = coordinator,
                        pairingRevision = pairedHomesRevision,
                        onChanged = ::refresh,
                    )
                }
            }
        }

        SettingsSectionCard(SettingsSection.Voice) {
            VoiceSettings()
        }

        // Retain the existing operator/rollback path only for an existing legacy configuration.
        val operatorHandleVisible = setupMode == RelayConnectionMode.HomeBridge && collection.profiles.any {
            it.mode == RelayConnectionMode.Legacy || it.homeBinding != null
        }
        // Device administration belongs to operator-handle Profiles; a paired
        // Profile has no administration binding to act on.
        val administration = homeAdministration?.takeIf {
            collection.selected?.mode == RelayConnectionMode.HomeBridge &&
                collection.selected?.homeClientGrant == null
        }
        if (operatorHandleVisible || administration != null) {
            var advancedExpanded by rememberSaveable { mutableStateOf(false) }
            SettingsSectionCard(SettingsSection.Advanced) {
                Text(
                    text = stringResource(R.string.android_settings_advanced_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("android_settings_advanced_toggle"),
                    onClick = { advancedExpanded = !advancedExpanded },
                ) {
                    Text(
                        stringResource(
                            if (advancedExpanded) {
                                R.string.android_settings_advanced_hide
                            } else {
                                R.string.android_settings_advanced_show
                            },
                        ),
                    )
                }
                if (advancedExpanded) {
                    if (operatorHandleVisible) {
                        OperatorHandleFields(controller = controller, onSaved = ::completeSetup)
                    }
                    administration?.let {
                        HomeDeviceAdministrationScreen(
                            controller = it,
                            selectedProfileId = collection.selectedId,
                            onChanged = onHomeAdministrationChanged,
                            onCredentialChanged = onHomeCredentialChanged,
                        )
                    }
                }
            }
        }

        if (onShareDiagnostics != null || versionLabel != null) {
            SettingsSectionCard(SettingsSection.Troubleshooting) {
                versionLabel?.let { label ->
                    val description = stringResource(R.string.android_version_description, label)
                    SelectionContainer {
                        Text(
                            modifier = Modifier
                                .testTag("app-version")
                                .semantics { contentDescription = description },
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                onShareDiagnostics?.let { share ->
                    Text(
                        text = stringResource(R.string.android_diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("android_diagnostics_share"),
                        onClick = share,
                    ) {
                        Text(stringResource(R.string.android_diagnostics_share))
                    }
                }
            }
        }
    }
}

/** One grouped Settings card; its title is a TalkBack heading. */
@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(section.tag),
        shape = HermesShapes.card,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

/** Microphone access for tap-to-speak, with the system settings route (`ANDROID-VOICE-02`). */
@Composable
private fun VoiceSettings() {
    val context = LocalContext.current
    val source = remember(context) {
        PlatformRuntimePermissionSource(context, Manifest.permission.RECORD_AUDIO)
    }
    var permission by remember { mutableStateOf(source.state()) }
    LifecycleResumeEffect(source) {
        permission = source.state()
        onPauseOrDispose { }
    }
    Text(
        modifier = Modifier.testTag("android_settings_microphone_state"),
        text = stringResource(
            if (permission == RuntimePermissionState.Granted) {
                R.string.android_settings_microphone_allowed
            } else {
                R.string.android_settings_microphone_off
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_settings_microphone_open"),
        onClick = { context.startActivity(applicationDetailsSettingsIntent(context.packageName)) },
    ) {
        Text(stringResource(R.string.android_permission_open_settings))
    }
}

/** The retained legacy operator/rollback Profile form. */
@Composable
private fun OperatorHandleFields(controller: RelayConfigurationController, onSaved: () -> Unit) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var clientId by rememberSaveable { mutableStateOf("") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var errors by remember {
        mutableStateOf<Map<RelayProfileField, RelayProfileError>>(emptyMap())
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.android_settings_operator_handle),
            style = MaterialTheme.typography.titleSmall,
        )
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
                    onSaved()
                }
            },
        ) {
            Text(stringResource(R.string.android_relay_save))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeDeviceAdministrationScreen(
    controller: HomeDeviceAdministrationController,
    selectedProfileId: String?,
    onChanged: () -> Unit,
    onCredentialChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var redraw by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var adminCredential by remember { mutableStateOf("") }
    var endpointId by rememberSaveable { mutableStateOf("") }
    var deviceLabel by rememberSaveable { mutableStateOf("") }
    var offerCode by remember { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf("") }
    var requestedRooms by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var roomEntry by rememberSaveable { mutableStateOf("") }
    var confirmRevoke by remember { mutableStateOf(false) }
    var requestedCapabilities by rememberSaveable { mutableStateOf("wake_claim") }
    var wakeMappingIds by rememberSaveable { mutableStateOf("") }
    var roomId by rememberSaveable { mutableStateOf("") }
    var roomName by rememberSaveable { mutableStateOf("") }
    var mappingId by rememberSaveable { mutableStateOf("") }
    var mappingPhrase by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(controller, selectedProfileId) {
        controller.synchronizeSelectedProfile()
        redraw += 1
    }
    redraw
    val state = controller.state
    val configuration = state.configuration

    fun runAsync(credentialChanged: Boolean = false, action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { action() }
            withContext(Dispatchers.Main) {
                busy = false
                if (result.isSuccess) {
                    onChanged()
                    if (credentialChanged) onCredentialChanged()
                }
                message = result.fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        when (error) {
                            is HomeAdministrationException -> "Home: ${error.reason.name}"
                            else -> "Home: transport unavailable"
                        }
                    },
                )
                redraw += 1
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_home_administration"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.android_home_admin_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.android_home_admin_state,
                state.phase?.name ?: stringResource(R.string.android_home_admin_not_started),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        HomeField(
            tag = "android_home_admin_credential",
            value = adminCredential,
            onValueChange = { adminCredential = it },
            label = stringResource(R.string.android_home_admin_credential),
            masked = true,
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_save_admin"),
            enabled = !busy,
            onClick = {
                runAsync {
                    if (!controller.saveAdminCredential(adminCredential)) {
                        throw HomeAdministrationException(
                            HomeAdministrationError.SecureStorageUnavailable,
                        )
                    }
                    withContext(Dispatchers.Main) { adminCredential = "" }
                    "Home admin credential saved."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_save_admin))
        }

        Text(
            text = stringResource(R.string.android_home_discovery_title),
            style = MaterialTheme.typography.titleSmall,
        )
        HomeField(
            tag = "android_home_device_id",
            value = endpointId,
            onValueChange = { endpointId = it },
            label = stringResource(R.string.android_home_device_id),
        )
        HomeField(
            tag = "android_home_device_label",
            value = deviceLabel,
            onValueChange = { deviceLabel = it },
            label = stringResource(R.string.android_home_device_label),
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_discover"),
            enabled = !busy,
            onClick = {
                runAsync {
                    controller.discover(endpointId, deviceLabel)
                    "Device discovered. It is not authorized."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_discover))
        }

        Text(
            text = stringResource(R.string.android_home_enrollment_title),
            style = MaterialTheme.typography.titleSmall,
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_create_offer"),
            enabled = !busy,
            onClick = {
                runAsync {
                    val offer = controller.createOffer()
                    withContext(Dispatchers.Main) { offerCode = offer.enrollmentCode }
                    "Enrollment offer created. Keep its code private."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_create_offer))
        }
        HomeField(
            tag = "android_home_offer_code",
            value = offerCode,
            onValueChange = { offerCode = it },
            label = stringResource(R.string.android_home_offer_code),
            masked = true,
        )
        HomeField(
            tag = "android_home_request_id",
            value = requestId,
            onValueChange = { requestId = it },
            label = stringResource(R.string.android_home_request_id),
        )
        Text(
            text = stringResource(R.string.android_home_requested_rooms),
            style = MaterialTheme.typography.bodyMedium,
        )
        val knownRooms = configuration?.rooms.orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (knownRooms.map { it.id } + requestedRooms).distinct().forEach { id ->
                FilterChip(
                    modifier = Modifier.testTag("android_home_requested_room_$id"),
                    selected = id in requestedRooms,
                    onClick = { requestedRooms = RoomSelection.toggle(requestedRooms, id) },
                    label = { Text(knownRooms.firstOrNull { it.id == id }?.name ?: id) },
                )
            }
        }
        HomeField(
            tag = "android_home_requested_rooms",
            value = roomEntry,
            onValueChange = { roomEntry = it },
            label = stringResource(R.string.android_home_requested_room_entry),
        )
        TextButton(
            modifier = Modifier.testTag("android_home_requested_room_add"),
            enabled = roomEntry.isNotBlank() && roomEntry.trim() !in requestedRooms,
            onClick = {
                requestedRooms = RoomSelection.toggle(requestedRooms, roomEntry)
                roomEntry = ""
            },
        ) {
            Text(stringResource(R.string.android_home_requested_room_add))
        }
        HomeField(
            tag = "android_home_requested_capabilities",
            value = requestedCapabilities,
            onValueChange = { requestedCapabilities = it },
            label = stringResource(R.string.android_home_requested_capabilities),
        )
        HomeField(
            tag = "android_home_wake_mapping_ids",
            value = wakeMappingIds,
            onValueChange = { wakeMappingIds = it },
            label = stringResource(R.string.android_home_wake_mapping_ids),
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_submit_request"),
            enabled = !busy,
            onClick = {
                runAsync {
                    val submission = controller.submitEnrollmentRequest(
                        offerCode = offerCode,
                        requestedRooms = requestedRooms,
                        requestedCapabilities = csv(requestedCapabilities),
                    )
                    withContext(Dispatchers.Main) { requestId = submission.requestId }
                    "Enrollment request submitted for approval."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_submit_request))
        }
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_approve"),
            enabled = !busy,
            onClick = {
                runAsync {
                    controller.approveEnrollmentRequest(
                        requestId = requestId,
                        scope = HomeCredentialScope(
                            rooms = requestedRooms,
                            capabilities = csv(requestedCapabilities),
                            wakeMappings = csv(wakeMappingIds),
                        ),
                    )
                    "Enrollment approved. Consume the offer to store the Device credential."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_approve))
        }
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_consume"),
            enabled = !busy && state.deviceId == null,
            onClick = {
                runAsync(credentialChanged = true) {
                    controller.consumeEnrollment(offerCode = offerCode, requestId = requestId)
                    withContext(Dispatchers.Main) { offerCode = "" }
                    "Device credential stored securely. Configuration is still pending."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_consume))
        }

        Text(
            text = stringResource(R.string.android_home_configuration_title),
            style = MaterialTheme.typography.titleSmall,
        )
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_home_fetch_configuration"),
            enabled = !busy,
            onClick = {
                runAsync {
                    val snapshot = controller.fetchConfiguration()
                    "Home configuration loaded at revision ${snapshot.revision}."
                }
            },
        ) {
            Text(stringResource(R.string.android_home_fetch_configuration))
        }
        configuration?.let { snapshot ->
            Text(
                text = stringResource(
                    R.string.android_home_configuration_revision,
                    snapshot.revision,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = snapshot.rooms.joinToString { "${it.id}: ${it.name}" },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = snapshot.wakeMappings.joinToString { "${it.id}: ${it.phrase}" },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.android_home_room_id),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.rooms.forEach { room ->
                    FilterChip(
                        modifier = Modifier.testTag("android_home_room_${room.id}"),
                        selected = roomId == room.id,
                        onClick = { roomId = if (roomId == room.id) "" else room.id },
                        label = { Text(room.name) },
                    )
                }
            }
            HomeField(
                tag = "android_home_room_name",
                value = roomName,
                onValueChange = { roomName = it },
                label = stringResource(R.string.android_home_room_name),
            )
            HomeField(
                tag = "android_home_mapping_id",
                value = mappingId,
                onValueChange = { mappingId = it },
                label = stringResource(R.string.android_home_mapping_id),
            )
            HomeField(
                tag = "android_home_mapping_phrase",
                value = mappingPhrase,
                onValueChange = { mappingPhrase = it },
                label = stringResource(R.string.android_home_mapping_phrase),
            )
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("android_home_publish_configuration"),
                enabled = !busy,
                onClick = {
                    runAsync {
                        val edited = snapshot.withEdits(roomId, roomName, mappingId, mappingPhrase)
                        val published = controller.publishConfiguration(edited)
                        "Home configuration published at revision ${published.revision}. Device is ready."
                    }
                },
            ) {
                Text(stringResource(R.string.android_home_publish_configuration))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !busy,
                onClick = {
                    runAsync(credentialChanged = true) {
                        controller.renewDevice()
                        "Device credential renewed."
                    }
                },
            ) {
                Text(stringResource(R.string.android_home_renew))
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    runAsync(credentialChanged = true) {
                        controller.reEnrollDevice()
                        "Device re-enrollment started."
                    }
                },
            ) {
                Text(stringResource(R.string.android_home_reenroll))
            }
            TextButton(
                modifier = Modifier.testTag("android_home_revoke"),
                enabled = !busy,
                onClick = { confirmRevoke = true },
            ) {
                Text(stringResource(R.string.android_home_revoke))
            }
        }

        if (message.isNotBlank()) {
            Text(
                modifier = Modifier.testTag("android_home_message"),
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            modifier = Modifier.testTag("android_home_revoke_confirm"),
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.android_home_revoke_title)) },
            text = { Text(stringResource(R.string.android_home_revoke_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("android_home_revoke_confirm_button"),
                    onClick = {
                        confirmRevoke = false
                        runAsync(credentialChanged = true) {
                            controller.revokeDevice()
                            "Device access revoked."
                        }
                    },
                ) { Text(stringResource(R.string.android_home_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) {
                    Text(stringResource(R.string.android_home_revoke_keep))
                }
            },
        )
    }
}

@Composable
private fun HomeField(
    tag: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    masked: Boolean = false,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (masked) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
    )
}

private fun csv(value: String): List<String> = value
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)

private fun HomeConfigurationSnapshot.withEdits(
    roomId: String,
    roomName: String,
    mappingId: String,
    mappingPhrase: String,
): HomeConfigurationSnapshot {
    val rooms = if (roomId.isBlank() && roomName.isBlank()) {
        this.rooms
    } else {
        if (roomId.isBlank() || roomName.isBlank()) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
        var found = false
        this.rooms.map { room ->
            if (room.id == roomId.trim()) {
                found = true
                room.copy(name = roomName.trim())
            } else {
                room
            }
        }.also {
            if (!found) throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
    }
    val mappings = if (mappingId.isBlank() && mappingPhrase.isBlank()) {
        wakeMappings
    } else {
        if (mappingId.isBlank() || mappingPhrase.isBlank()) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
        var found = false
        wakeMappings.map { mapping ->
            if (mapping.id == mappingId.trim()) {
                found = true
                mapping.copy(phrase = mappingPhrase.trim())
            } else {
                mapping
            }
        }.also {
            if (!found) throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
    }
    return copy(rooms = rooms, wakeMappings = mappings)
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
