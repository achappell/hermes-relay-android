package com.achappell.hermesrelay

import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material3.Text
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import com.achappell.hermesrelay.ui.theme.HermesSpacing
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors

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
    }

    override fun onStop() {
        runtime.journal.record("app phase=stopped")
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val clientPort = runtime.clientPort
    val context = androidx.compose.ui.platform.LocalContext.current
    // Computed once from the installed package (ANDROID-REL-01).
    val versionLabel = remember { AppBuildIdentity.current(context).label() }
    val claimsListFailedMessage = stringResource(R.string.android_home_claims_list_failed)
    val claimsCloseUnconfirmedMessage = stringResource(R.string.android_home_claims_close_unconfirmed)
    val claimsRefreshedMessage = stringResource(R.string.android_home_claims_refreshed)
    val resources = androidx.compose.ui.platform.LocalResources.current
    var configurationRevision by remember { mutableStateOf(0) }
    val recoveryState = runtime.recoveryState
    val snapshot = remember(
        clientPort,
        configurationRevision,
        recoveryState.connection,
        recoveryState.connectionId,
    ) { clientPort.snapshot() }
    var configurationVisible by rememberSaveable { mutableStateOf(false) }
    val homeConversations = clientPort as? AndroidHomeConversations
    var conversationsVisible by rememberSaveable { mutableStateOf(false) }
    var disconnectConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var conversationsState by remember {
        mutableStateOf<HomeConversationsState>(HomeConversationsState.Loading)
    }
    val conversationsGeneration = remember { mutableStateOf(0) }
    var openClaimsState by remember {
        mutableStateOf<HomeOpenClaimsState>(HomeOpenClaimsState.Hidden)
    }
    var claimManagementMessage by remember { mutableStateOf<String?>(null) }
    val openClaimsRequests = remember { HomeProfileRequestGate() }
    var renameMessage by remember { mutableStateOf<String?>(null) }
    // The divider to record once the requested conversation actually opens.
    var pendingDivider by remember { mutableStateOf<String?>(null) }
    var conversationMessage by remember { mutableStateOf<String?>(null) }
    val homeApprovals = clientPort as? AndroidHomeApprovals
    var approvalsVisible by rememberSaveable { mutableStateOf(false) }
    var approvalsState by remember { mutableStateOf<HomeApprovalsState>(HomeApprovalsState.Loading) }
    var approvalsMessage by remember { mutableStateOf<String?>(null) }
    var approvalsBusy by remember { mutableStateOf(false) }
    var pendingApprovals by remember { mutableStateOf(0) }
    // A delivered pairing link opens the configuration sheet, which submits it.
    LaunchedEffect(pendingPairingLink) {
        if (pendingPairingLink != null) configurationVisible = true
    }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val initiationState = runtime.initiationState
    val turnState = runtime.turnState
    val resendResult = runtime.resendResult
    val initiationInFlight = runtime.initiationInFlight
    val captureState = runtime.captureState
    val handsFree = runtime.handsFree
    val promptFocus = remember { FocusRequester() }
    val recorder = runtime.recorder
    val captureController = runtime.captureController
    val promptHistory = runtime.promptHistory
    val exporter = remember { TranscriptExporter() }
    val historyRevision = runtime.historyRevision

    // Local History follows the selected Profile: switching Profiles opens that
    // Profile's conversation and never shows another's. A Standard Profile's
    // history is keyed by mode + endpoint + Hermes Profile, not by the Profile id.
    val selectedProfileId = configuration?.collection?.selectedId
    val selectedProfile = configuration?.collection?.selected
    val selectedHistoryKey = selectedProfile?.historyKey
    val standardMode = snapshot.mode == RelayProfileMode.Standard
    LaunchedEffect(recorder, selectedProfileId, selectedHistoryKey, configurationRevision) {
        runtime.profileSelectionChanged(selectedProfileId, selectedProfile?.mode, selectedHistoryKey)
        runtime.openPromptHistory(selectedHistoryKey)
        recorder?.open(selectedHistoryKey)
        recorder?.let { prompt = it.history.draft }
        runtime.historyRevision += 1
    }
    val isAuthorized = snapshot.authorizationState == AndroidAuthorizationState.Verified &&
        snapshot.selectedProfile != null
    val isConnected = recoveryState.connection == AndroidConnectionState.Connected
    val hasUnresolvedTurn = recoveryState.hasUnconfirmedTurn ||
        recoveryState.unresolvedHomeTurn ||
        (standardMode && runtime.standardSession?.hasUncertainTurn() == true)
    val hasAcceptedTurn = runtime.hasAcceptedTurn
    val canAttemptConnection = snapshot.selectedProfile != null &&
        snapshot.unavailableReason !in setOf(
            AndroidHomeUnavailableReason.MissingBinding,
            AndroidHomeUnavailableReason.InvalidBinding,
            AndroidHomeUnavailableReason.InvalidCredential,
            AndroidHomeUnavailableReason.SecureStorageUnavailable,
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
            AndroidHomeUnavailableReason.Unauthorized,
            AndroidHomeUnavailableReason.StaleConversation,
            AndroidHomeUnavailableReason.ConversationMismatch,
            AndroidHomeUnavailableReason.RequestRejected,
            AndroidHomeUnavailableReason.ProtocolError,
            AndroidHomeUnavailableReason.CapabilityUnavailable,
            AndroidHomeUnavailableReason.ClaimLimit,
        )


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
        permissionRevision,
        captureState,
    ) { captureController?.blockingReason() }
    val doorwayState = resolveAndroidDoorwayState(
        snapshot = snapshot,
        isConnected = isConnected,
        hasAcceptedTurn = hasAcceptedTurn,
        isCapturing = captureController?.isCapturing == true,
        hasUnconfirmedTurn = hasUnresolvedTurn,
        captureBlock = captureBlock,
    )
    val composerBlock = resolveAndroidComposerBlock(
        hasProfile = snapshot.selectedProfile != null,
        isAuthorized = isAuthorized,
        isConnected = isConnected,
        hasAcceptedTurn = hasAcceptedTurn ||
            runtime.newConversationState == StandardNewConversationState.InFlight,
        prompt = prompt,
        hasUnconfirmedTurn = hasUnresolvedTurn,
        isFinishingPreviousResponse = standardMode && runtime.standardFinishing,
    )
    // Editing a local draft does not require a live Home authorization. Sending
    // still does: composerBlock remains based on the verified authorization.
    val canEditPrompt = snapshot.selectedProfile != null

    fun updatePrompt(value: String) {
        prompt = value
        recorder?.recordDraft(value)
    }

    fun recover() = runtime.recover()

    fun loadConversations() {
        val conversations = homeConversations ?: return
        val profileId = configuration?.collection?.selectedId
        val requestId = conversationsGeneration.value + 1
        conversationsGeneration.value = requestId
        conversationsState = HomeConversationsState.Loading
        runtime.runOnWork {
            val listed = when (val result = conversations.listConversations()) {
                is HomeClientClaimProvider.Sessions.Listed -> HomeConversationsState.Listed(result.sessions)
                is HomeClientClaimProvider.Sessions.Unavailable ->
                    HomeConversationsState.Unavailable(result.message)
            }
            runtime.runOnMain {
                if (
                    requestId == conversationsGeneration.value &&
                    profileId == configuration?.collection?.selectedId
                ) {
                    conversationsState = listed
                }
            }
        }
    }

    fun isCurrentOpenClaimsRequest(request: HomeProfileRequestGate.Request): Boolean =
        openClaimsRequests.isCurrent(request, configuration?.collection?.selectedId)

    fun applyOpenClaimsResult(
        request: HomeProfileRequestGate.Request,
        result: HomeClientClaimProvider.OpenClaims,
    ) {
        if (!isCurrentOpenClaimsRequest(request)) return
        when (result) {
            HomeClientClaimProvider.OpenClaims.Unsupported -> {
                openClaimsState = HomeOpenClaimsState.Hidden
                claimManagementMessage = null
            }
            is HomeClientClaimProvider.OpenClaims.Listed -> {
                openClaimsState = HomeOpenClaimsState.Listed(
                    pairingId = result.pairingId,
                    maxClaims = result.maxClaims,
                    claims = result.claims,
                )
                runtime.runOnWork {
                    val enriched = runCatching {
                        homeConversations?.enrichOpenClaimTitles(result.pairingId, result.claims)
                    }.getOrNull() ?: result.claims
                    runtime.runOnMain {
                        if (isCurrentOpenClaimsRequest(request)) {
                            val current = openClaimsState as? HomeOpenClaimsState.Listed
                            if (current?.pairingId == result.pairingId) {
                                openClaimsState = current.copy(claims = enriched)
                            }
                        }
                    }
                }
            }
            is HomeClientClaimProvider.OpenClaims.Unavailable -> {
                openClaimsState = HomeOpenClaimsState.Unavailable(result.message)
            }
        }
    }

    fun loadOpenClaims() {
        val conversations = homeConversations ?: return
        val request = openClaimsRequests.begin(configuration?.collection?.selectedId)
        claimManagementMessage = null
        if (!conversations.selectedIsPaired() || !conversations.supportsOpenClaims()) {
            openClaimsState = HomeOpenClaimsState.Hidden
            return
        }
        openClaimsState = HomeOpenClaimsState.Loading
        runtime.runOnWork {
            val listed = try {
                conversations.listOpenClaims()
            } catch (_: Exception) {
                HomeClientClaimProvider.OpenClaims.Unavailable(
                    claimsListFailedMessage,
                )
            }
            runtime.runOnMain { applyOpenClaimsResult(request, listed) }
        }
    }

    fun submitClaimClose(pairingId: String, claimRefs: List<String>) {
        val conversations = homeConversations ?: return
        if (conversations.selectedPairingId() != pairingId) return
        val request = openClaimsRequests.begin(configuration?.collection?.selectedId)
        openClaimsState = HomeOpenClaimsState.Loading
        claimManagementMessage = null
        runtime.runOnWork {
            val closeAndList = try {
                conversations.closeAndListOpenClaims(pairingId, claimRefs)
            } catch (_: Exception) {
                val refreshed = try {
                    conversations.listOpenClaims(pairingId)
                } catch (_: Exception) {
                    HomeClientClaimProvider.OpenClaims.Unavailable(
                        claimsListFailedMessage,
                    )
                }
                HomeClientClaimProvider.CloseAndList(
                    closeResult = HomeClientClaimProvider.CloseClaims.Unavailable(
                        claimsCloseUnconfirmedMessage,
                    ),
                    listing = refreshed,
                )
            }
            val closeResult = closeAndList.closeResult
            val refreshed = closeAndList.listing
            val message = when (closeResult) {
                HomeClientClaimProvider.CloseClaims.Unsupported -> null
                is HomeClientClaimProvider.CloseClaims.Completed -> {
                    if (closeResult.closed == 0) {
                        claimsRefreshedMessage
                    } else {
                        resources.getQuantityString(
                            R.plurals.android_home_claims_closed,
                            closeResult.closed,
                            closeResult.closed,
                        )
                    }
                }
                is HomeClientClaimProvider.CloseClaims.Unavailable ->
                    claimsCloseUnconfirmedMessage
            }
            runtime.runOnMain {
                if (!isCurrentOpenClaimsRequest(request)) return@runOnMain
                claimManagementMessage = message
                applyOpenClaimsResult(request, refreshed)
            }
        }
    }

    fun showConversations() {
        configurationVisible = false
        historyVisible = false
        renameMessage = null
        conversationsVisible = true
    }

    LaunchedEffect(conversationsVisible, selectedProfileId, configurationRevision) {
        if (conversationsVisible) {
            loadConversations()
            loadOpenClaims()
        } else {
            conversationsGeneration.value += 1
            openClaimsRequests.invalidate()
        }
    }

    fun refreshApprovals(showLoading: Boolean = true) {
        val approvals = homeApprovals ?: return
        if (homeConversations?.selectedIsPaired() != true) {
            pendingApprovals = 0
            return
        }
        if (showLoading) approvalsState = HomeApprovalsState.Loading
        runtime.runOnWork {
            val loaded = when (val result = approvals.approvals()) {
                is HomeClientClaimProvider.Approvals.Loaded ->
                    HomeApprovalsState.Loaded(result.pending, result.holders)
                is HomeClientClaimProvider.Approvals.Unavailable ->
                    HomeApprovalsState.Unavailable(result.message)
            }
            runtime.runOnMain {
                approvalsState = loaded
                if (loaded is HomeApprovalsState.Loaded) pendingApprovals = loaded.pending.size
            }
        }
    }

    fun switchConversation(intent: HomeConversationIntent, divider: String) {
        if (homeConversations == null) return
        conversationsVisible = false
        conversationMessage = null
        pendingDivider = divider
        runtime.switchConversation(intent)
    }

    // A paired Profile claims a fresh conversation on every connect, so there
    // is no single-use handle to protect: connect it as soon as it is selected.
    // Operator-handle Profiles keep the deliberate connect action.
    val selectedIsPaired = configuration?.collection?.selected?.homeClientGrant != null
    LaunchedEffect(selectedProfileId, selectedIsPaired) {
        if (
            selectedIsPaired &&
            canAttemptConnection &&
            recoveryState.connection != AndroidConnectionState.Connected
        ) {
            recover()
        }
    }

    // Report how the last claim opened: a requested switch gets its divider,
    // and a conversation that could not be continued is never silent.
    val fallbackBusyText = stringResource(R.string.android_conversations_fallback_busy)
    val fallbackGoneText = stringResource(R.string.android_conversations_fallback_gone)
    LaunchedEffect(recoveryState.connectionId) {
        val notice = homeConversations?.takeConversationNotice() ?: return@LaunchedEffect
        val fallbackMessage = when (notice.fallback) {
            HomeResumeFallback.Busy -> fallbackBusyText
            HomeResumeFallback.Gone -> fallbackGoneText
            null -> null
        }
        val requested = pendingDivider
        pendingDivider = null
        when {
            fallbackMessage != null -> {
                conversationMessage = fallbackMessage
                recorder?.recordDivider(fallbackMessage)
            }
            requested != null -> recorder?.recordDivider(requested)
        }
        runtime.historyRevision += 1
    }

    // No push yet: check for owner requests on connect and on every return to
    // the foreground.
    LaunchedEffect(recoveryState.connectionId, selectedProfileId) {
        if (recoveryState.connection == AndroidConnectionState.Connected) {
            refreshApprovals(showLoading = false)
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latestRefreshApprovals by rememberUpdatedState({ refreshApprovals(showLoading = false) })
    // Android cuts a background app's network, so a paired Profile usually
    // returns to a dropped connection. Reconnect at once: inside Home's grace
    // this reopens the same conversation with conversation.reconnect.
    val latestResumeConnection by rememberUpdatedState({
        if (
            homeConversations?.selectedIsPaired() == true &&
            canAttemptConnection &&
            recoveryState.connection != AndroidConnectionState.Connected
        ) {
            recover()
        }
    })
    SideEffect {
        runtime.reconnectIfForeground = {
            val resumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            // An unresolved turn waits for the user's explicit resend/discard.
            if (resumed && !hasUnresolvedTurn) latestResumeConnection()
        }
    }
    // No screen, no foreground reconnect: the runtime outlives this composition.
    DisposableEffect(runtime) {
        onDispose { runtime.reconnectIfForeground = {} }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                latestResumeConnection()
                latestRefreshApprovals()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun resendUnconfirmedTurn() = runtime.resendUnconfirmedTurn()

    fun discardUnconfirmedTurn() = runtime.discardUnconfirmedTurn()

    // Focus restoration: when a turn settles, return focus to the composer so
    // the next action is reachable without traversing the whole screen again.
    // The runtime settles each turn once; a screen recreated after the settle
    // must not steal focus for it.
    val settleRevisionAtEntry = remember { runtime.settleRevision }
    LaunchedEffect(runtime.settleRevision) {
        if (runtime.settleRevision != settleRevisionAtEntry && !runtime.handsFree) {
            runCatching { promptFocus.requestFocus() }
        }
    }
    // The composer empties once a typed turn is accepted, whichever screen
    // instance is showing when Home accepts it.
    val composerClearRevisionAtEntry = remember { runtime.composerClearRevision }
    LaunchedEffect(runtime.composerClearRevision) {
        if (runtime.composerClearRevision != composerClearRevisionAtEntry) prompt = ""
    }

    val stateColors = LocalHermesStateColors.current
    val motionMode = rememberAndroidMotionMode()

    if (disconnectConfirmVisible) {
        androidx.compose.material3.AlertDialog(
            modifier = Modifier.testTag("android_disconnect_confirm"),
            onDismissRequest = { disconnectConfirmVisible = false },
            title = { Text(stringResource(R.string.android_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.android_disconnect_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    modifier = Modifier.testTag("android_disconnect_confirm_yes"),
                    onClick = {
                        disconnectConfirmVisible = false
                        runtime.disconnect()
                    },
                ) {
                    Text(stringResource(R.string.android_disconnect_confirm_yes))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { disconnectConfirmVisible = false },
                ) {
                    Text(stringResource(R.string.android_disconnect_confirm_no))
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                DoorwayHeaderZone(
                    snapshot = snapshot,
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
                    canDisconnect = runtime.canDisconnect,
                    disconnectEnabled = runtime.disconnectEnabled,
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
                        approvalsMessage = null
                        approvalsVisible = true
                        refreshApprovals()
                    },
                    onShowConversations = { showConversations() },
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("android_action_surface"),
                    color = stateColors.consoleSurface,
                    tonalElevation = 2.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = HermesSpacing.lg, vertical = HermesSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(HermesSpacing.sm),
                        ) {
                            TypedComposerZone(
                                prompt = prompt,
                                onPromptChange = ::updatePrompt,
                                promptFocus = promptFocus,
                                promptHistory = promptHistory,
                                onPromptHistoryChange = { runtime.promptHistory = it },
                                canEditPrompt = canEditPrompt,
                                isConnected = isConnected,
                                composerBlock = composerBlock,
                                isInitiating = initiationInFlight,
                                standardMode = standardMode,
                                onSend = { runtime.initiate(AndroidTurnInput.Typed(prompt)) },
                            )

                            if (standardMode) {
                                // Voice in Standard mode is slice 2 of ANDROID-STD-01.
                                StandardVoiceNote()
                            } else if (captureController == null) {
                                TapToSpeakFallback(
                                    enabled = isAuthorized && isConnected &&
                                        !hasAcceptedTurn &&
                                        !hasUnresolvedTurn &&
                                        !initiationInFlight,
                                    onTapToSpeak = { runtime.initiate(AndroidTurnInput.TapToSpeak) },
                                )
                            } else if (captureController.isCapturing) {
                                ActiveCaptureZone(
                                    captureState = captureState,
                                    handsFree = handsFree,
                                    motionMode = motionMode,
                                    onStop = { captureController.finishCapture() },
                                    onCancel = { captureController.cancelCapture() },
                                    networkRecognitionAllowed = captureController.networkRecognitionAllowed,
                                )
                            } else {
                                IdleCaptureZone(
                                    captureController = captureController,
                                    captureState = captureState,
                                    handsFree = handsFree,
                                    hasAcceptedTurn = hasAcceptedTurn,
                                    hasUnconfirmedTurn = hasUnresolvedTurn,
                                    isAuthorized = isAuthorized,
                                    isConnected = isConnected,
                                    permissionRevision = permissionRevision,
                                    microphoneState = microphone.state,
                                    onRequestMicrophone = microphone::request,
                                    onOpenMicrophoneSettings = microphone::openSettings,
                                    showBlockMessage = doorwayState !is AndroidDoorwayState.NoProfile,
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .testTag("android_conversation_rail"),
                    contentPadding = PaddingValues(horizontal = HermesSpacing.xl, vertical = HermesSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(HermesSpacing.lg),
                ) {
                    doorwayState?.let { currentState ->
                        item {
                            DoorwayStateZone(
                                state = currentState,
                                connectionState = recoveryState.connection,
                                canConfigure = configuration != null &&
                                    currentState is AndroidDoorwayState.NoProfile,
                                // A connected conversation is edited from the
                                // header menu; recovery keeps its explicit
                                // secondary action when the relay is down.
                                canEditRelay = false,
                                onConfigure = { configurationVisible = true },
                                onEditRelay = { configurationVisible = true },
                                standardMode = standardMode,
                            )
                        }
                    }

                    if (
                        snapshot.unavailableReason == AndroidHomeUnavailableReason.ClaimLimit &&
                        homeConversations?.selectedIsPaired() == true
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("android_home_claim_limit_notice"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.android_home_claim_limit_notice),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                androidx.compose.material3.TextButton(
                                    modifier = Modifier.testTag("android_home_claims_manage"),
                                    onClick = { showConversations() },
                                ) {
                                    Text(stringResource(R.string.android_home_claims_manage))
                                }
                            }
                        }
                    }

                    if (pendingApprovals > 0 && !approvalsVisible) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("android_approvals_banner")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = stringResource(R.string.android_approvals_banner),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        approvalsMessage = null
                                        approvalsVisible = true
                                        refreshApprovals()
                                    },
                                ) {
                                    Text(stringResource(R.string.android_approvals_review))
                                }
                            }
                        }
                    }

                    conversationMessage?.let { message ->
                        item {
                            Text(
                                modifier = Modifier
                                    .testTag("android_conversation_notice")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
                                canEditRelay = configuration != null &&
                                    snapshot.selectedProfile != null,
                                onRecover = { runtime.connect() },
                                userDisconnected = runtime.userDisconnected,
                                onEditRelay = { configurationVisible = true },
                                onResend = { resendUnconfirmedTurn() },
                                onDiscard = { discardUnconfirmedTurn() },
                                standardMode = standardMode,
                            )
                            if (standardMode) {
                                StandardConversationZone(
                                    finishing = runtime.standardFinishing,
                                    uncertain = hasUnresolvedTurn,
                                    state = runtime.newConversationState,
                                    canStart = snapshot.selectedProfile != null &&
                                        snapshot.unavailableReason !=
                                        AndroidHomeUnavailableReason.InvalidCredential &&
                                        !hasAcceptedTurn && !initiationInFlight,
                                    onNewConversation = { runtime.startNewConversation() },
                                )
                            }
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
                                onInterrupt = { binding ->
                                    if (standardMode) {
                                        clientPort.interruptTurn(binding)
                                    } else {
                                        runtime.interruptAndListen(binding)
                                    }
                                },
                                standardMode = standardMode,
                                interruptStatus = runtime.interruptStatus,
                            )
                        }
                    }

                }
            }
        }

        if (configurationVisible) {
            configuration?.let { configurationController ->
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                )
                ModalBottomSheet(
                    modifier = Modifier.testTag("android_relay_configuration_sheet"),
                    onDismissRequest = { configurationVisible = false },
                    sheetState = sheetState,
                    containerColor = stateColors.panel,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 720.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = HermesSpacing.xl, vertical = HermesSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(HermesSpacing.md),
                    ) {
                        RelayConfigurationScreen(
                            controller = configurationController,
                            homeAdministration = homeAdministration,
                            onHomeAdministrationChanged = { configurationRevision += 1 },
                            onHomeCredentialChanged = {
                                clientPort.close()
                                configurationRevision += 1
                            },
                            homePairing = homePairing,
                            versionLabel = versionLabel,
                            onShareDiagnostics = {
                                context.startActivity(
                                    DiagnosticsShare.chooserIntent(context, runtime.journal),
                                )
                            },
                            pendingPairingLink = pendingPairingLink,
                            onPendingPairingLinkConsumed = onPendingPairingLinkConsumed,
                            onChanged = {
                                configurationRevision += 1
                                if (configurationController.collection.selectedId != null) {
                                    configurationVisible = false
                                }
                            },
                        )
                    }
                }
            }
        }

        if (conversationsVisible && homeConversations != null) {
            val renamedText = stringResource(R.string.android_conversations_renamed)
            val renameFailedText = stringResource(R.string.android_conversations_rename_failed)
            val newDivider = stringResource(R.string.android_conversations_new)
            HomeConversationsSheet(
                state = conversationsState,
                currentRef = homeConversations.currentConversationRef(),
                canRename = homeConversations.canRenameConversation(),
                actionsEnabled = !hasAcceptedTurn && !hasUnresolvedTurn,
                renameMessage = renameMessage,
                onNew = { switchConversation(HomeConversationIntent.New, newDivider) },
                onResume = { session ->
                    switchConversation(
                        HomeConversationIntent.Resume(session.sessionRef),
                        HomeConversationRows.resumedDivider(session.title),
                    )
                },
                onRename = { title ->
                    renameMessage = null
                    runtime.runOnWork {
                        val renamed = homeConversations.renameConversation(title)
                        runtime.runOnMain {
                            renameMessage = if (renamed) renamedText else renameFailedText
                            if (renamed) loadConversations()
                        }
                    }
                },
                onRefresh = { loadConversations() },
                onDismiss = { conversationsVisible = false },
                openClaimsState = openClaimsState,
                currentClaimRef = homeConversations.currentClaimRef(),
                claimManagementMessage = claimManagementMessage,
                onCloseClaims = { pairingId, refs -> submitClaimClose(pairingId, refs) },
                onRefreshOpenClaims = { loadOpenClaims() },
            )
        }

        if (approvalsVisible && homeApprovals != null) {
            val doneTexts = mapOf(
                HomeGrantAction.Approve to stringResource(R.string.android_approvals_done_approve),
                HomeGrantAction.Reject to stringResource(R.string.android_approvals_done_reject),
                HomeGrantAction.Revoke to stringResource(R.string.android_approvals_done_revoke),
            )
            val goneText = stringResource(R.string.android_approvals_gone)
            val notAllowedText = stringResource(R.string.android_approvals_not_allowed)
            val unreachableText = stringResource(R.string.android_approvals_unreachable)
            val failedText = stringResource(R.string.android_approvals_failed)
            HomeApprovalsSheet(
                state = approvalsState,
                message = approvalsMessage,
                busy = approvalsBusy,
                onDecide = { holder, action ->
                    approvalsBusy = true
                    approvalsMessage = null
                    runtime.runOnWork {
                        val result = homeApprovals.decideGrant(holder.grantId, action)
                        runtime.runOnMain {
                            approvalsBusy = false
                            approvalsMessage = when (result) {
                                HomeGrantActionResult.Done -> doneTexts.getValue(action)
                                HomeGrantActionResult.Gone -> goneText
                                HomeGrantActionResult.NotAllowed -> notAllowedText
                                HomeGrantActionResult.Unreachable -> unreachableText
                                HomeGrantActionResult.Failed -> failedText
                            }
                            refreshApprovals(showLoading = false)
                        }
                    }
                },
                onRefresh = { refreshApprovals() },
                onDismiss = { approvalsVisible = false },
            )
        }

        if (historyVisible) {
            recorder?.let { history ->
                // Recreate the sheet content after a clear so the recorder's
                // file-backed, non-Compose history is read again.
                key(historyRevision) {
                    LocalHistoryZone(
                        recorder = history,
                        exporter = exporter,
                        profileDisplayName = snapshot.selectedProfile?.displayName,
                        onClear = {
                            history.clear()
                            runtime.historyRevision += 1
                        },
                        sheetVisibility = true,
                        onDismiss = { historyVisible = false },
                    )
                }
            }
        }
    }
}
