package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** What the Devices & approvals sheet is showing. */
internal sealed interface HomeApprovalsState {
    data object Loading : HomeApprovalsState

    data class Loaded(
        val pending: List<HomeProfileHolder>,
        val holders: List<HomeProfileHolder>,
    ) : HomeApprovalsState

    data class Unavailable(val message: String) : HomeApprovalsState
}

internal object HomeApprovalsText {
    /** Holders grouped by Profile label, in first-seen order. */
    fun byProfile(holders: List<HomeProfileHolder>): Map<String, List<HomeProfileHolder>> =
        holders.groupBy { it.profileLabel }

    fun deviceType(type: String): String = when (type) {
        "android" -> "Android"
        "ios" -> "iPhone or iPad"
        "macos" -> "Mac"
        "tui" -> "Terminal"
        else -> type
    }
}

/**
 * Requests from other devices to use a Profile this phone owns, and the devices
 * already holding this phone's Profiles. Approve and Decline act at once;
 * removing a device asks first, because it ends that device's conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeApprovalsSheet(
    state: HomeApprovalsState,
    message: String?,
    busy: Boolean,
    onDecide: (HomeProfileHolder, HomeGrantAction) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<HomeProfileHolder?>(null) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    fun whenText(seconds: Double) =
        if (seconds > 0) dateFormat.format(Date((seconds * 1000).toLong())) else null

    ModalBottomSheet(
        modifier = Modifier.testTag("android_approvals_sheet"),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.android_approvals_label),
                style = MaterialTheme.typography.titleMedium,
            )
            message?.let {
                Text(
                    modifier = Modifier
                        .testTag("android_approvals_message")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (state) {
                HomeApprovalsState.Loading -> Text(
                    text = stringResource(R.string.android_approvals_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is HomeApprovalsState.Unavailable -> {
                    Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.android_conversations_retry))
                    }
                }
                is HomeApprovalsState.Loaded -> {
                    Text(
                        text = stringResource(R.string.android_approvals_pending_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.pending.isEmpty()) {
                        Text(
                            text = stringResource(R.string.android_approvals_pending_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.pending.forEach { request ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("android_approval_request"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.android_approvals_request,
                                        request.deviceLabel,
                                        request.profileLabel,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = listOfNotNull(
                                        HomeApprovalsText.deviceType(request.deviceType),
                                        whenText(request.createdAt),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        modifier = Modifier.testTag("android_approval_approve"),
                                        enabled = !busy,
                                        onClick = { onDecide(request, HomeGrantAction.Approve) },
                                    ) {
                                        Text(stringResource(R.string.android_approvals_approve))
                                    }
                                    TextButton(
                                        modifier = Modifier.testTag("android_approval_decline"),
                                        enabled = !busy,
                                        onClick = { onDecide(request, HomeGrantAction.Reject) },
                                    ) {
                                        Text(stringResource(R.string.android_approvals_decline))
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.android_approvals_holders_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    HomeApprovalsText.byProfile(state.holders).forEach { (profile, holders) ->
                        Text(text = profile, style = MaterialTheme.typography.labelLarge)
                        holders.forEach { holder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("android_approval_holder"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = holder.deviceLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            HomeApprovalsText.deviceType(holder.deviceType),
                                            stringResource(R.string.android_approvals_this_phone)
                                                .takeIf { holder.thisDevice },
                                            stringResource(R.string.android_approvals_first_device)
                                                .takeIf { holder.bootstrap },
                                            stringResource(R.string.android_approvals_waiting)
                                                .takeIf { holder.status == HomeClientGrantStatus.PendingOwner },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!holder.thisDevice) {
                                    TextButton(
                                        modifier = Modifier.testTag("android_approval_remove"),
                                        enabled = !busy,
                                        onClick = { confirmRemove = holder },
                                    ) {
                                        Text(stringResource(R.string.android_approvals_remove))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { holder ->
        AlertDialog(
            modifier = Modifier.testTag("android_approval_remove_confirm"),
            onDismissRequest = { confirmRemove = null },
            title = {
                Text(
                    stringResource(
                        R.string.android_approvals_remove_title,
                        holder.deviceLabel,
                        holder.profileLabel,
                    ),
                )
            },
            text = { Text(stringResource(R.string.android_approvals_remove_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("android_approval_remove_yes"),
                    onClick = {
                        confirmRemove = null
                        onDecide(holder, HomeGrantAction.Revoke)
                    },
                ) {
                    Text(stringResource(R.string.android_approvals_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.android_approvals_keep))
                }
            },
        )
    }
}
