package com.achappell.hermesrelay

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * What a Paired Homes action reports. [quantity] is set when the text is a
 * plural. Carries no handle, credential or grant ID.
 */
internal data class PairedHomesMessage(
    @StringRes val textRes: Int? = null,
    @PluralsRes val pluralRes: Int? = null,
    val quantity: Int? = null,
)

/** Maps coordinator outcomes to user-facing messages; free of Compose so it is unit-testable. */
internal object PairedHomesMessages {
    fun refresh(outcome: HomeRefreshOutcome): PairedHomesMessage = when (outcome) {
        is HomeRefreshOutcome.Refreshed ->
            if (outcome.added == 0) {
                PairedHomesMessage(textRes = R.string.android_paired_homes_no_new)
            } else {
                PairedHomesMessage(
                    pluralRes = R.plurals.android_paired_homes_added,
                    quantity = outcome.added,
                )
            }
        HomeRefreshOutcome.PairAgain -> PairedHomesMessage(R.string.android_paired_homes_pair_again)
        HomeRefreshOutcome.NotPaired -> PairedHomesMessage(R.string.android_paired_homes_gone)
        is HomeRefreshOutcome.Failed -> when (outcome.reason) {
            HomeAdministrationError.TransportUnavailable,
            HomeAdministrationError.ServiceUnavailable,
            -> PairedHomesMessage(R.string.android_home_pair_unreachable)
            HomeAdministrationError.SecureStorageUnavailable ->
                PairedHomesMessage(R.string.android_paired_homes_storage_failed)
            else -> PairedHomesMessage(R.string.android_paired_homes_refresh_failed)
        }
    }

    fun unpair(outcome: HomeUnpairOutcome): PairedHomesMessage = when (outcome) {
        is HomeUnpairOutcome.Unpaired -> PairedHomesMessage(R.string.android_paired_homes_unpaired)
        HomeUnpairOutcome.CredentialNotRemoved ->
            PairedHomesMessage(R.string.android_paired_homes_unpair_credential_failed)
        HomeUnpairOutcome.RecordNotRemoved ->
            PairedHomesMessage(R.string.android_paired_homes_unpair_record_failed)
        HomeUnpairOutcome.NotPaired -> PairedHomesMessage(R.string.android_paired_homes_gone)
    }
}

/**
 * Paired Homes: status, granted Profiles and expiry per Home, with Refresh
 * Profiles and Unpair this Home (`ANDROID-HOME-13`). Calls block on IO; the
 * logic lives in [HomeClientPairingCoordinator].
 */
@Composable
internal fun PairedHomesSection(
    coordinator: HomeClientPairingCoordinator,
    pairingRevision: Int,
    onChanged: () -> Unit,
) {
    var homes by remember { mutableStateOf<List<PairedHome>>(emptyList()) }
    var needingPairing by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<PairedHomesMessage?>(null) }
    var busyPairingId by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf<PairedHome?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            coordinator.pairedHomes() to coordinator.profilesNeedingPairing().size
        }
        homes = loaded.first
        needingPairing = loaded.second
    }
    LaunchedEffect(pairingRevision) { reload() }

    if (homes.isEmpty() && needingPairing == 0 && message == null) return

    Card(
        modifier = Modifier.testTag("android_paired_homes"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.android_paired_homes_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (needingPairing > 0) {
                Text(
                    modifier = Modifier.testTag("android_paired_homes_needs_pairing"),
                    text = pluralStringResource(
                        R.plurals.android_paired_homes_needs_pairing,
                        needingPairing,
                        needingPairing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            homes.forEach { home ->
                Column(
                    modifier = Modifier.testTag("android_paired_home_${home.pairingId}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(home.host, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(
                            when (home.status) {
                                PairedHomeStatus.Active -> R.string.android_paired_homes_status_active
                                PairedHomeStatus.Pending -> R.string.android_paired_homes_status_pending
                                PairedHomeStatus.Expired -> R.string.android_paired_homes_status_expired
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.android_paired_homes_expires,
                            DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date((home.credentialExpiresAt * 1000).toLong())),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    home.grants.forEach { grant ->
                        Text(
                            text = stringResource(
                                when {
                                    grant.status == HomeClientGrantStatus.PendingOwner ->
                                        R.string.android_paired_homes_grant_pending
                                    grant.hasProfile -> R.string.android_paired_homes_grant_active
                                    else -> R.string.android_paired_homes_grant_no_profile
                                },
                                grant.label,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            modifier = Modifier.testTag("android_paired_home_refresh"),
                            enabled = busyPairingId == null,
                            onClick = {
                                busyPairingId = home.pairingId
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        coordinator.refreshGrants(home.pairingId)
                                    }
                                    message = PairedHomesMessages.refresh(outcome)
                                    reload()
                                    busyPairingId = null
                                    onChanged()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.android_paired_homes_refresh))
                        }
                        OutlinedButton(
                            modifier = Modifier.testTag("android_paired_home_unpair"),
                            enabled = busyPairingId == null,
                            onClick = { confirmUnpair = home },
                        ) {
                            Text(stringResource(R.string.android_paired_homes_unpair))
                        }
                    }
                }
            }
            message?.let { shown ->
                Text(
                    modifier = Modifier
                        .testTag("android_paired_home_message")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    text = if (shown.pluralRes != null && shown.quantity != null) {
                        pluralStringResource(shown.pluralRes, shown.quantity, shown.quantity)
                    } else {
                        stringResource(checkNotNull(shown.textRes))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    confirmUnpair?.let { home ->
        AlertDialog(
            modifier = Modifier.testTag("android_paired_home_unpair_confirm"),
            onDismissRequest = { confirmUnpair = null },
            title = { Text(stringResource(R.string.android_paired_homes_unpair_title, home.host)) },
            text = { Text(stringResource(R.string.android_paired_homes_unpair_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("android_paired_home_unpair_yes"),
                    onClick = {
                        confirmUnpair = null
                        busyPairingId = home.pairingId
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                coordinator.unpair(home.pairingId)
                            }
                            message = PairedHomesMessages.unpair(outcome)
                            reload()
                            busyPairingId = null
                            onChanged()
                        }
                    },
                ) {
                    Text(stringResource(R.string.android_paired_homes_unpair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = null }) {
                    Text(stringResource(R.string.android_paired_homes_unpair_cancel))
                }
            },
        )
    }
}
