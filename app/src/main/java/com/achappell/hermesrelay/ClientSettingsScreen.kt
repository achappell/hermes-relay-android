package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.achappell.hermesrelay.ui.theme.HermesSpacing
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors

/**
 * `ANDROID-UX-11`: full-screen Settings destination. Back and predictive back
 * dismiss it through the dialog window's back dispatcher.
 */
@Composable
internal fun ClientSettingsScreen(
    configuration: RelayConfigurationController,
    canReconfigure: Boolean,
    homeAdministration: HomeDeviceAdministrationController?,
    homePairing: HomeClientPairingCoordinator?,
    versionLabel: String,
    pendingPairingLink: String?,
    onPendingPairingLinkConsumed: () -> Unit,
    onHomeAdministrationChanged: () -> Unit,
    onHomeCredentialChanged: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("android_settings_screen"),
            color = LocalHermesStateColors.current.panel,
        ) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HermesSpacing.lg, vertical = HermesSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        text = stringResource(R.string.android_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        modifier = Modifier.testTag("android_settings_done"),
                        onClick = onDismiss,
                    ) { Text(stringResource(R.string.android_settings_done)) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = HermesSpacing.lg, vertical = HermesSpacing.md),
                ) {
                    RelayConfigurationScreen(
                        controller = configuration,
                        canReconfigure = canReconfigure,
                        homeAdministration = homeAdministration,
                        onHomeAdministrationChanged = onHomeAdministrationChanged,
                        onHomeCredentialChanged = onHomeCredentialChanged,
                        homePairing = homePairing,
                        versionLabel = versionLabel,
                        onShareDiagnostics = onShareDiagnostics,
                        pendingPairingLink = pendingPairingLink,
                        onPendingPairingLinkConsumed = onPendingPairingLinkConsumed,
                        onChanged = onChanged,
                        onSetupCompleted = { if (configuration.collection.selectedId != null) onDismiss() },
                    )
                }
            }
        }
    }
}
