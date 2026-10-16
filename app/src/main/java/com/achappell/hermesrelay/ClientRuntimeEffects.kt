package com.achappell.hermesrelay

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Composition lifetime integration; controllers and in-flight state remain runtime-owned. */
@Composable
internal fun ClientRuntimeEffects(
    runtime: HomeRuntime,
    configuration: RelayConfigurationController?,
    recoveryState: AndroidRecoveryState,
    standardMode: Boolean,
    canAttemptConnection: Boolean,
    connectionRevision: Int,
    configurationRevision: Int,
    conversationsVisible: Boolean,
    conversationsHolder: ConversationsHolder,
    approvalsHolder: ApprovalsHolder,
    promptFocus: FocusRequester,
    onPromptRestored: (String) -> Unit,
    openApplicationSettings: ((android.content.Intent) -> Unit)?,
) {
    val turnUi by runtime.turnHolder.state.collectAsStateWithLifecycle()
    val captureUi by runtime.captureHolder.state.collectAsStateWithLifecycle()
    val recorder = runtime.recorder
    val selectedProfileId = configuration?.collection?.selectedId
    val selectedHistoryKey = configuration?.collection?.selected?.historyKey
    LaunchedEffect(recorder, selectedHistoryKey) {
        runtime.openHistory(selectedHistoryKey)
        recorder?.let { onPromptRestored(it.history.draft) }
        runtime.historyRevision += 1
    }
    val notifications = rememberRuntimePermission(
        permission = android.Manifest.permission.POST_NOTIFICATIONS,
        openSettings = openApplicationSettings,
    )
    LaunchedEffect(runtime.retention) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            runtime.retention == BackgroundRetention.Reply &&
            notifications.state == RuntimePermissionState.NotAsked
        ) notifications.request()
    }
    LaunchedEffect(conversationsVisible, selectedProfileId, configurationRevision) {
        if (conversationsVisible) {
            conversationsHolder.loadConversations()
            conversationsHolder.loadOpenClaims()
        } else conversationsHolder.hide()
    }
    val selectedIsPaired = configuration?.collection?.selected?.homeClientGrant != null
    LaunchedEffect(selectedProfileId, selectedIsPaired, standardMode, connectionRevision) {
        runtime.profileSelected(selectedProfileId)
        if ((selectedIsPaired || standardMode) && canAttemptConnection &&
            recoveryState.connection != AndroidConnectionState.Connected
        ) runtime.recover()
    }
    val fallbackBusyText = stringResource(R.string.android_conversations_fallback_busy)
    val fallbackGoneText = stringResource(R.string.android_conversations_fallback_gone)
    LaunchedEffect(recoveryState.connectionId) {
        if (conversationsHolder.consumeConversationNotice(fallbackBusyText, fallbackGoneText) {
            recorder?.recordDivider(it)
        }) runtime.historyRevision += 1
    }
    LaunchedEffect(recoveryState.connectionId, selectedProfileId) {
        if (recoveryState.connection == AndroidConnectionState.Connected) approvalsHolder.refresh(false)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestRefreshApprovals by rememberUpdatedState({ approvalsHolder.refresh(false) })
    val latestResumeConnection by rememberUpdatedState({
        if ((runtime.homeConversations?.selectedIsPaired() == true || standardMode) &&
            canAttemptConnection && runtime.needsReconnect
        ) runtime.recover()
    })
    SideEffect {
        runtime.reconnectIfForeground = {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                latestResumeConnection()
            }
        }
    }
    DisposableEffect(runtime) {
        onDispose { runtime.reconnectIfForeground = {} }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                runtime.appForegrounded()
                latestResumeConnection()
                latestRefreshApprovals()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Recreated screens must not steal focus or clear a new draft for an earlier turn.
    val settleRevisionAtEntry = remember { runtime.settleRevision }
    LaunchedEffect(turnUi.settleRevision) {
        if (turnUi.settleRevision != settleRevisionAtEntry && !captureUi.handsFree) {
            runCatching { promptFocus.requestFocus() }
        }
    }
    val composerClearRevisionAtEntry = remember { runtime.composerClearRevision }
    LaunchedEffect(turnUi.composerClearRevision) {
        if (turnUi.composerClearRevision != composerClearRevisionAtEntry) onPromptRestored("")
    }
}
