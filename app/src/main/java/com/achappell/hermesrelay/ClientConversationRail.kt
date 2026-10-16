package com.achappell.hermesrelay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.HermesSpacing

@Composable
internal fun ClientConversationRail(
    runtime: HomeRuntime,
    snapshot: AndroidClientSnapshot,
    doorwayState: AndroidDoorwayState?,
    recoveryState: AndroidRecoveryState,
    canConfigure: Boolean,
    onConfigure: () -> Unit,
    showConversations: () -> Unit,
    pendingApprovals: Int,
    approvalsVisible: Boolean,
    onShowApprovals: () -> Unit,
    conversationMessage: String?,
    onConversationMessage: (String?) -> Unit,
    standardMode: Boolean,
    hasUnresolvedTurn: Boolean,
    initiationInFlight: Boolean,
    resendResult: AndroidResendResult?,
    isAuthorized: Boolean,
    canAttemptConnection: Boolean,
    isConnected: Boolean,
    initiationState: AndroidInitiationState,
    turnState: AndroidTurnState,
    hasAcceptedTurn: Boolean,
    motionMode: AndroidMotionMode,
    userDisconnected: Boolean,
    interruptStatus: InterruptStatus,
) {
    val clientPort = runtime.clientPort
    val homeConversations = runtime.homeConversations
    val resources = androidx.compose.ui.platform.LocalResources.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .widthIn(max = 720.dp).testTag("android_conversation_rail"),
            contentPadding = PaddingValues(horizontal = HermesSpacing.xl, vertical = HermesSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HermesSpacing.lg),
        ) {
            item {
                Text(
                    stringResource(
                        if (doorwayState is AndroidDoorwayState.NoProfile) R.string.android_connection_choose
                        else when (clientPort.connectionMode()) {
                            RelayConnectionMode.Standard -> R.string.android_mode_standard
                            RelayConnectionMode.HomeBridge -> R.string.android_mode_home
                            RelayConnectionMode.Legacy -> R.string.android_mode_legacy
                        },
                    ),
                    Modifier.testTag("android_connection_mode"),
                )
            }
            doorwayState?.let { currentState ->
                item {
                    DoorwayStateZone(
                        state = currentState,
                        connectionState = recoveryState.connection,
                        canConfigure = canConfigure && currentState is AndroidDoorwayState.NoProfile,
                        // A connected conversation is edited from the header menu.
                        canEditRelay = false,
                        onConfigure = onConfigure,
                        onEditRelay = onConfigure,
                    )
                }
            }
            if (snapshot.unavailableReason == AndroidHomeUnavailableReason.ClaimLimit &&
                homeConversations?.selectedIsPaired() == true
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("android_home_claim_limit_notice"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.android_home_claim_limit_notice),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.TextButton(
                            modifier = Modifier.testTag("android_home_claims_manage"),
                            onClick = showConversations,
                        ) { Text(stringResource(R.string.android_home_claims_manage)) }
                    }
                }
            }
            if (pendingApprovals > 0 && !approvalsVisible) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("android_approvals_banner")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.android_approvals_banner),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.TextButton(onClick = onShowApprovals) {
                            Text(stringResource(R.string.android_approvals_review))
                        }
                    }
                }
            }
            conversationMessage?.let { message ->
                item {
                    Text(
                        modifier = Modifier.testTag("android_conversation_notice")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (standardMode) {
                item {
                    Column {
                        if (hasUnresolvedTurn) {
                            Text(stringResource(R.string.android_standard_uncertain))
                        } else if (clientPort.hasActiveTurn()) {
                            Text(stringResource(R.string.android_standard_finishing))
                        }
                        androidx.compose.material3.TextButton(
                            enabled = !initiationInFlight && !recoveryState.isRecovering,
                            modifier = Modifier.testTag("android_standard_new_conversation"),
                            onClick = {
                                runtime.newStandardConversation { success ->
                                    onConversationMessage(
                                        if (success) null else resources.getString(R.string.android_standard_new_failed),
                                    )
                                }
                            },
                        ) { Text(stringResource(R.string.android_standard_new)) }
                    }
                }
            }
            item {
                Column {
                    ConnectionRecoveryZone(
                        recoveryState = recoveryState,
                        resendResult = resendResult,
                        isAuthorized = isAuthorized,
                        canAttemptConnection = canAttemptConnection,
                        isConnected = isConnected,
                        canEditRelay = canConfigure && snapshot.selectedProfile != null,
                        onRecover = runtime::connect,
                        userDisconnected = userDisconnected,
                        onEditRelay = onConfigure,
                        onResend = runtime::resendUnconfirmedTurn,
                        onDiscard = runtime::discardUnconfirmedTurn,
                        standardMode = standardMode,
                    )
                }
            }
            item {
                Column {
                    TurnZone(
                        initiationState = initiationState,
                        turnState = turnState,
                        snapshot = snapshot,
                        hasAcceptedTurn = hasAcceptedTurn,
                        isConnected = isConnected,
                        supportsInterrupt = clientPort.supportsInterrupt(),
                        motionMode = motionMode,
                        onInterrupt = runtime::interruptAndListen,
                        interruptStatus = interruptStatus,
                    )
                }
            }
        }
    }
}
