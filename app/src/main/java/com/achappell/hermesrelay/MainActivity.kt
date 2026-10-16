package com.achappell.hermesrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme

class MainActivity : ComponentActivity() {
    /** A `hermes-home://pair` link waiting for the configuration sheet. */
    private var pendingPairingLink by mutableStateOf<String?>(null)

    /** The process's single Home runtime; this Activity observes it and never owns it. */
    private lateinit var runtime: HomeRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptPairingLink(intent)

        val application = application as HermesRelayApplication
        val services = application.services
        runtime = application.homeRuntimeBox.resolve()
        runtime.activityCreated()

        setContent {
            val selectedProfileId = services.configuration.collection.selectedId
            val homeAdministration = remember(selectedProfileId) {
                HomeDeviceAdministrationController(services.configuration, services.credentials)
            }
            HermesRelayTheme {
                AndroidClientScreen(
                    runtime = runtime,
                    configuration = services.configuration,
                    homeAdministration = homeAdministration,
                    homePairing = services.homePairing,
                    pendingPairingLink = pendingPairingLink,
                    onPendingPairingLinkConsumed = { pendingPairingLink = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.journal.record("app phase=started")
        runtime.appStarted()
    }

    override fun onStop() {
        runtime.journal.record("app phase=stopped")
        // A recreation is not a background; the runtime ignores it.
        runtime.appBackgrounded(isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        // A recreation (rotation, font scale, theme, window resize) keeps the
        // runtime; only a finishing Activity can end it, and only when no reply
        // is in flight.
        if (::runtime.isInitialized) {
            runtime.activityDestroyed(
                isFinishing = isFinishing,
                isChangingConfigurations = isChangingConfigurations,
            )
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        acceptPairingLink(intent)
    }

    private fun acceptPairingLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (intent.action != android.content.Intent.ACTION_VIEW) return
        if (!data.scheme.equals(HomePairingLink.SCHEME, ignoreCase = true)) return
        pendingPairingLink = data.toString()
    }
}

@Composable
internal fun AndroidClientScreen(
    runtime: HomeRuntime,
    configuration: RelayConfigurationController? = null,
    homeAdministration: HomeDeviceAdministrationController? = null,
    homePairing: HomeClientPairingCoordinator? = null,
    pendingPairingLink: String? = null,
    onPendingPairingLinkConsumed: () -> Unit = {},
    microphonePermissionSource: RuntimePermissionSource? = null,
    openApplicationSettings: ((android.content.Intent) -> Unit)? = null,
) {
    RevealAccessibilityFocus()
    val clientPort = runtime.clientPort
    val context = androidx.compose.ui.platform.LocalContext.current
    // Computed once from the installed package (ANDROID-REL-01).
    val versionLabel = remember { AppBuildIdentity.current(context).label() }
    val claimsListFailedMessage = stringResource(R.string.android_home_claims_list_failed)
    val claimsCloseUnconfirmedMessage = stringResource(R.string.android_home_claims_close_unconfirmed)
    val claimsRefreshedMessage = stringResource(R.string.android_home_claims_refreshed)
    val resources = androidx.compose.ui.platform.LocalResources.current
    var configurationRevision by remember { mutableStateOf(0) }
    var connectionRevision by remember { mutableStateOf(0) }
    val turnUi by runtime.turnHolder.state.collectAsStateWithLifecycle()
    val captureUi by runtime.captureHolder.state.collectAsStateWithLifecycle()
    val recoveryUi by runtime.recoveryHolder.state.collectAsStateWithLifecycle()
    val recoveryState = recoveryUi.recoveryState
    val snapshot = remember(
        clientPort,
        configurationRevision,
        recoveryState.connection,
        recoveryState.connectionId,
    ) { clientPort.snapshot() }
    var configurationVisible by rememberSaveable { mutableStateOf(false) }
    val standardMode = clientPort.connectionMode() == RelayConnectionMode.Standard
    val homeConversations = clientPort.homeConversations()
    var conversationsVisible by rememberSaveable { mutableStateOf(false) }
    var disconnectConfirmVisible by rememberSaveable { mutableStateOf(false) }
    val conversationsHolder = remember(runtime, configuration, homeConversations) {
        ConversationsHolder(
            homeConversations, { configuration?.collection?.selectedId },
            runtime::runOnWork, runtime::runOnMain,
            ConversationsHolder.Messages(
                claimsListFailedMessage, claimsCloseUnconfirmedMessage, claimsRefreshedMessage,
                { count -> resources.getQuantityString(R.plurals.android_home_claims_closed, count, count) },
            ),
        )
    }
    val conversationsUi by conversationsHolder.state.collectAsStateWithLifecycle()
    val homeApprovals = clientPort.homeApprovals()
    var approvalsVisible by rememberSaveable { mutableStateOf(false) }
    val approvalsHolder = remember(runtime, homeApprovals, homeConversations) {
        ApprovalsHolder(homeApprovals, { homeConversations?.selectedIsPaired() == true }, runtime::runOnWork, runtime::runOnMain)
    }
    val approvalsUi by approvalsHolder.state.collectAsStateWithLifecycle()
    // A delivered pairing link opens the configuration sheet, which submits it.
    LaunchedEffect(pendingPairingLink) {
        if (pendingPairingLink != null) configurationVisible = true
    }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val captureState = captureUi.captureState
    val promptFocus = remember { FocusRequester() }
    val recorder = runtime.recorder
    val captureController = runtime.captureController
    val exporter = remember { TranscriptExporter() }

    val selectedProfileId = configuration?.collection?.selectedId
    val isAuthorized = snapshot.authorizationState == AndroidAuthorizationState.Verified &&
        snapshot.selectedProfile != null
    val isConnected = recoveryState.connection == AndroidConnectionState.Connected
    val availabilityBlock = resolveAndroidAvailabilityBlock(snapshot, recoveryState.connection)
    val hasUnresolvedTurn = recoveryState.hasUnconfirmedTurn ||
        recoveryState.unresolvedHomeTurn || clientPort.hasUncertainTurn()
    val hasAcceptedTurn = turnUi.hasAcceptedTurn(clientPort) || clientPort.hasActiveTurn()
    val canAttemptConnection = canAttemptClientConnection(snapshot, recoveryState, availabilityBlock)

    val microphone = rememberRuntimePermission(
        permission = android.Manifest.permission.RECORD_AUDIO,
        source = microphonePermissionSource,
        openSettings = openApplicationSettings,
        onResult = { granted -> if (granted) captureController?.beginCapture() },
    )
    val permissionRevision = microphone.revision

    val captureBlock = remember(
        captureController,
        isAuthorized,
        isConnected,
        hasUnresolvedTurn,
        hasAcceptedTurn,
        permissionRevision,
        captureState,
    ) { captureController?.blockingReason() }
    val doorwayState = resolveAndroidDoorwayState(
        hasProfile = snapshot.selectedProfile != null,
        availabilityBlock = availabilityBlock,
        hasAcceptedTurn = hasAcceptedTurn,
        isCapturing = captureController?.isCapturing == true,
        hasUnconfirmedTurn = hasUnresolvedTurn,
        captureBlock = captureBlock,
    )
    val composerBlock = resolveAndroidComposerBlock(
        hasProfile = snapshot.selectedProfile != null,
        availabilityBlock = availabilityBlock,
        hasAcceptedTurn = hasAcceptedTurn,
        prompt = prompt,
        hasUnconfirmedTurn = hasUnresolvedTurn,
    )
    // Editing a local draft does not require a live Home authorization. Sending
    // still does: composerBlock remains based on the verified authorization.
    val canEditPrompt = snapshot.selectedProfile != null

    fun updatePrompt(value: String) {
        prompt = value
        recorder?.recordDraft(value)
    }

    fun showConversations() {
        configurationVisible = false
        historyVisible = false
        conversationsHolder.show()
        conversationsVisible = true
    }

    fun switchConversation(intent: HomeConversationIntent, divider: String) {
        if (homeConversations == null) return
        conversationsVisible = false
        conversationsHolder.switchConversation(intent, divider, runtime::switchConversation)
    }
    ClientRuntimeEffects(
        runtime, configuration, recoveryState, standardMode, canAttemptConnection,
        connectionRevision, configurationRevision, conversationsVisible,
        conversationsHolder, approvalsHolder, promptFocus, { prompt = it },
        openApplicationSettings,
    )

    val motionMode = rememberAndroidMotionMode()

    if (disconnectConfirmVisible) {
        ClientDisconnectConfirmation(
            onDismiss = { disconnectConfirmVisible = false },
            onConfirm = { disconnectConfirmVisible = false; runtime.disconnect() },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        DoorwayLayout(
            modifier = Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true },
            header = {
                DoorwayHeaderZone(
                    snapshot = snapshot,
                    authorizationState = if (
                        snapshot.authorizationState == AndroidAuthorizationState.Verifying &&
                        availabilityBlock == AndroidAvailabilityBlock.Authorization
                    ) AndroidAuthorizationState.Unavailable else snapshot.authorizationState,
                    canConfigure = configuration != null,
                    canShowHistory = recorder != null && snapshot.selectedProfile != null,
                    onConfigure = {
                        historyVisible = false
                        configurationVisible = true
                    },
                    onShowHistory = {
                        configurationVisible = false
                        historyVisible = true
                    },
                    canDisconnect = recoveryUi.canDisconnect,
                    disconnectEnabled = recoveryUi.canDisconnect && !turnUi.initiationInFlight && !recoveryUi.resendInFlight,
                    onDisconnect = {
                        if (runtime.disconnectNeedsConfirmation) {
                            disconnectConfirmVisible = true
                        } else {
                            runtime.disconnect()
                        }
                    },
                    canShowConversations = homeConversations?.selectedIsPaired() == true,
                    canShowApprovals = homeConversations?.selectedIsPaired() == true && homeApprovals != null,
                    onShowApprovals = {
                        configurationVisible = false
                        historyVisible = false
                        conversationsVisible = false
                        approvalsHolder.clearMessage()
                        approvalsVisible = true
                        approvalsHolder.refresh()
                    },
                    onShowConversations = { showConversations() },
                )
            },
            actions = {
                ClientActionSurface(
                    runtime, prompt, ::updatePrompt, promptFocus, turnUi.promptHistory,
                    canEditPrompt, isConnected, composerBlock, turnUi.initiationInFlight,
                    captureController, isAuthorized, hasAcceptedTurn, hasUnresolvedTurn,
                    captureState, captureUi.handsFree, motionMode, availabilityBlock,
                    permissionRevision, microphone, doorwayState,
                )
            },
        ) {
            ClientConversationRail(
                runtime, snapshot, doorwayState, recoveryState, configuration != null,
                { configurationVisible = true }, ::showConversations,
                approvalsUi.pendingApprovals, approvalsVisible,
                { approvalsHolder.clearMessage(); approvalsVisible = true; approvalsHolder.refresh() },
                conversationsUi.conversationMessage, conversationsHolder::setConversationMessage,
                standardMode, hasUnresolvedTurn, turnUi.initiationInFlight, recoveryUi.resendResult,
                isAuthorized, canAttemptConnection, isConnected, turnUi.initiationState,
                turnUi.turnState, hasAcceptedTurn, motionMode, recoveryUi.userDisconnected, captureUi.interruptStatus,
            )
        }

        if (configurationVisible && configuration != null) {
            ClientConfigurationSheet(
                configuration, runtime.canReconfigure && prompt.isBlank(), homeAdministration,
                homePairing, versionLabel, pendingPairingLink, onPendingPairingLinkConsumed,
                onHomeAdministrationChanged = { configurationRevision += 1 },
                onHomeCredentialChanged = {
                    runtime.configurationChanged()
                    connectionRevision += 1
                    configurationRevision += 1
                },
                onShareDiagnostics = { context.startActivity(DiagnosticsShare.chooserIntent(context, runtime.journal)) },
                onChanged = {
                    if (standardMode || configuration.collection.selectedId != selectedProfileId) {
                        runtime.configurationChanged()
                        connectionRevision += 1
                    }
                    configurationRevision += 1
                },
                onDismiss = { configurationVisible = false },
            )
        }

        if (conversationsVisible && homeConversations != null) {
            ClientConversationsSheet(homeConversations, conversationsHolder, conversationsUi,
                !hasAcceptedTurn && !hasUnresolvedTurn, ::switchConversation,
                { conversationsVisible = false })
        }
        if (approvalsVisible && homeApprovals != null) {
            ClientApprovalsSheet(approvalsHolder, approvalsUi) { approvalsVisible = false }
        }

        if (historyVisible && recorder != null) {
            ClientHistorySheet(recorder, exporter, snapshot.selectedProfile?.displayName,
                turnUi.historyRevision, { runtime.historyRevision += 1 }, { historyVisible = false })
        }
    }
}
