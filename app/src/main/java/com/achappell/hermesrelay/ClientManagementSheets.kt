package com.achappell.hermesrelay

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun ClientConversationsSheet(
    conversations: AndroidHomeConversations,
    holder: ConversationsHolder,
    state: ConversationsHolder.State,
    actionsEnabled: Boolean,
    switchConversation: (HomeConversationIntent, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val renamedText = stringResource(R.string.android_conversations_renamed)
    val renameFailedText = stringResource(R.string.android_conversations_rename_failed)
    val newDivider = stringResource(R.string.android_conversations_new)
    HomeConversationsSheet(
        state = state.conversationsState,
        currentRef = conversations.currentConversationRef(),
        canRename = conversations.canRenameConversation(),
        actionsEnabled = actionsEnabled,
        renameMessage = state.renameMessage,
        onNew = { switchConversation(HomeConversationIntent.New, newDivider) },
        onResume = { session ->
            switchConversation(HomeConversationIntent.Resume(session.sessionRef), HomeConversationRows.resumedDivider(session.title))
        },
        onRename = { holder.rename(it, renamedText, renameFailedText) },
        onRefresh = holder::loadConversations,
        onDismiss = onDismiss,
        openClaimsState = state.openClaimsState,
        currentClaimRef = conversations.currentClaimRef(),
        claimManagementMessage = state.claimManagementMessage,
        onCloseClaims = holder::submitClaimClose,
        onRefreshOpenClaims = holder::loadOpenClaims,
    )
}

@Composable
internal fun ClientApprovalsSheet(
    holder: ApprovalsHolder,
    state: ApprovalsHolder.State,
    onDismiss: () -> Unit,
) {
    val messages = ApprovalsHolder.Messages(
        mapOf(
            HomeGrantAction.Approve to stringResource(R.string.android_approvals_done_approve),
            HomeGrantAction.Reject to stringResource(R.string.android_approvals_done_reject),
            HomeGrantAction.Revoke to stringResource(R.string.android_approvals_done_revoke),
        ),
        stringResource(R.string.android_approvals_gone),
        stringResource(R.string.android_approvals_not_allowed),
        stringResource(R.string.android_approvals_unreachable),
        stringResource(R.string.android_approvals_failed),
    )
    HomeApprovalsSheet(
        state = state.approvalsState,
        message = state.approvalsMessage,
        busy = state.approvalsBusy,
        onDecide = { grant, action -> holder.decide(grant.grantId, action, messages) },
        onRefresh = { holder.refresh() },
        onDismiss = onDismiss,
    )
}
