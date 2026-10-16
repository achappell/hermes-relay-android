package com.achappell.hermesrelay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.HermesSpacing
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClientConfigurationSheet(
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
    ModalBottomSheet(
        modifier = Modifier.testTag("android_relay_configuration_sheet"),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalHermesStateColors.current.panel,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesSpacing.xl, vertical = HermesSpacing.md),
            verticalArrangement = Arrangement.spacedBy(HermesSpacing.md),
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
