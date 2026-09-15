package com.achappell.hermesrelay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val credentials = KeystoreRelayCredentialStore(applicationContext)
        val historyStore = FileAndroidHistoryStore(applicationContext)
        val configuration = RelayConfigurationController(
            profiles = FileRelayProfileStore(applicationContext),
            credentials = credentials,
            history = historyStore,
        )
        // One port serves both states: it reports NotConfigured until a profile
        // with a stored credential exists, so the shell stays honest about an
        // unconfigured relay without needing a separate bootstrap adapter.
        val clientPort = OkHttpRelaySessionClient(
            collection = { configuration.collection },
            credentials = credentials,
            audioSink = AudioTrackAudioSink(),
        )

        setContent {
            val speechInput = remember { PlatformSpeechInput(applicationContext) }
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = clientPort,
                    configuration = configuration,
                    speechInput = speechInput,
                    historyStore = historyStore,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidClientScreen(
    clientPort: AndroidClientPort,
    configuration: RelayConfigurationController? = null,
    speechInput: AndroidSpeechInput? = null,
    historyStore: AndroidHistoryStore? = null,
) {
    var configurationRevision by remember { mutableStateOf(0) }
    var recoveryState by remember { mutableStateOf(AndroidRecoveryState()) }
    val snapshot = remember(
        clientPort,
        configurationRevision,
        recoveryState.connection,
        recoveryState.connectionId,
    ) { clientPort.snapshot() }
    var configurationVisible by rememberSaveable { mutableStateOf(false) }
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    val controller = remember(clientPort) { AndroidInitiationController(clientPort) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var initiationState by remember { mutableStateOf<AndroidInitiationState>(AndroidInitiationState.Idle) }
    var turnState by remember { mutableStateOf(AndroidTurnState()) }
    var lastRequest by remember { mutableStateOf<AndroidTurnRequest?>(null) }
    var resendResult by remember { mutableStateOf<AndroidResendResult?>(null) }
    val mainHandler = remember(clientPort) { Handler(Looper.getMainLooper()) }
    val workExecutor = remember(clientPort) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hermes-android-work").apply { isDaemon = true }
        }
    }
    var initiationInFlight by remember { mutableStateOf(false) }
    var resendInFlight by remember { mutableStateOf(false) }
    val recoveryController = remember(clientPort) {
        AndroidRecoveryController(clientPort) { changed ->
            mainHandler.post { recoveryState = changed }
        }
    }
    var captureState by remember { mutableStateOf<AndroidCaptureState>(AndroidCaptureState.Idle) }
    var permissionRevision by remember { mutableStateOf(0) }
    var handsFree by remember { mutableStateOf(false) }
    val promptFocus = remember { FocusRequester() }
    val recorder = remember(historyStore) { historyStore?.let { AndroidHistoryRecorder(it) } }
    var promptHistory by remember { mutableStateOf(AndroidPromptHistory()) }
    val exporter = remember { TranscriptExporter() }
    var historyRevision by remember { mutableStateOf(0) }

    // Local History follows the selected Profile: switching Profiles opens that
    // Profile's conversation and never shows another's.
    val selectedProfileId = configuration?.collection?.selectedId
    LaunchedEffect(recorder, selectedProfileId, configurationRevision) {
        recorder?.open(selectedProfileId)
        recorder?.let { prompt = it.history.draft }
        historyRevision += 1
    }
    val isAuthorized = snapshot.authorizationState == AndroidAuthorizationState.Verified &&
        snapshot.selectedProfile != null
    val isConnected = recoveryState.connection == AndroidConnectionState.Connected
    val hasUnresolvedTurn = recoveryState.hasUnconfirmedTurn ||
        recoveryState.unresolvedHomeTurn
    val latestIsAuthorized by rememberUpdatedState(isAuthorized)
    val latestIsConnected by rememberUpdatedState(isConnected)
    val latestTurnState by rememberUpdatedState(turnState)
    val latestHandsFree by rememberUpdatedState(handsFree)
    val acceptedBinding = (initiationState as? AndroidInitiationState.Accepted)?.binding
    val latestAcceptedBinding by rememberUpdatedState(acceptedBinding)
    val latestRequest by rememberUpdatedState(lastRequest)
    val hasAcceptedTurn = acceptedBinding != null &&
        (!turnState.isTerminal || clientPort.hasActiveTurn())
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
        )

    DisposableEffect(clientPort) {
        val connectionObservation = clientPort.observeConnection { event ->
            mainHandler.post {
                recoveryController.transportLost(
                    reason = event.reason,
                    inFlightTurn = latestRequest?.let { request ->
                        AndroidUnconfirmedTurn(latestAcceptedBinding, request)
                    },
                )
            }
        }
        onDispose {
            connectionObservation.cancel()
            workExecutor.shutdownNow()
            clientPort.close()
        }
    }

    DisposableEffect(clientPort, acceptedBinding) {
        val observation = acceptedBinding?.let { binding ->
            clientPort.observeTurn(binding) { event ->
                mainHandler.post {
                    val previous = turnState
                    turnState = AndroidTurnStateReducer.reduce(previous, event)
                }
            }
        }
        onDispose {
            observation?.cancel()
        }
    }

    fun applyInitiationState(
        state: AndroidInitiationState,
        recordAcceptedInput: Boolean,
        clearTypedPrompt: Boolean = false,
    ) {
        initiationState = state
        if (state is AndroidInitiationState.Accepted) {
            resendResult = null
            turnState = AndroidTurnState.awaitingEvents(state.binding)
            if (recordAcceptedInput) {
                (lastRequest?.input as? AndroidTurnInput.Typed)?.let { typed ->
                    recorder?.recordDraft("")
                    recorder?.recordUserTurn(typed.text)
                    promptHistory = promptHistory.record(typed.text)
                    // The composer empties once the turn is accepted; a sent
                    // prompt lingering in the box reads as unsent.
                    if (clearTypedPrompt) prompt = ""
                    historyRevision += 1
                }
            }
        } else if (state is AndroidInitiationState.Uncertain) {
            lastRequest = state.request
            turnState = AndroidTurnState()
            recoveryController.transportLost(
                reason = state.reason.name,
                inFlightTurn = AndroidUnconfirmedTurn(
                    binding = null,
                    request = state.request,
                ),
            )
        } else if (state is AndroidInitiationState.Rejected) {
            lastRequest = null
        }
    }

    fun initiate(input: AndroidTurnInput) {
        if (initiationInFlight) return
        initiationInFlight = true
        val profile = snapshot.selectedProfile
        profile?.let { lastRequest = AndroidTurnRequest(it, input) }
        workExecutor.execute {
            val state = controller.initiate(input)
            mainHandler.post {
                initiationInFlight = false
                applyInitiationState(
                    state = state,
                    recordAcceptedInput = true,
                    clearTypedPrompt = input is AndroidTurnInput.Typed,
                )
            }
        }
    }

    val captureController = remember(clientPort, speechInput) {
        speechInput?.let { input ->
            AndroidCaptureController(
                speech = input,
                initiation = controller,
                isConnected = { latestIsConnected },
                isAuthorized = {
                    val current = clientPort.snapshot()
                    latestIsAuthorized && current.selectedProfile != null &&
                        current.authorizationState == AndroidAuthorizationState.Verified
                },
                currentSessionId = { recoveryController.state.connectionId },
                onStateChange = { changed ->
                    mainHandler.post {
                        captureState = changed
                        if (changed is AndroidCaptureState.Submitted) {
                            clientPort.snapshot().selectedProfile?.let { profile ->
                                lastRequest = AndroidTurnRequest(
                                    profile,
                                    AndroidTurnInput.Typed(changed.transcript),
                                )
                            }
                        }
                        // Hands-free reopens the microphone after a turn settles.
                        // Leaving the settled turn's phase on screen would claim the
                        // conversation had ended while the microphone was live, so
                        // the reopened window becomes the next turn's Listening
                        // phase. Only a settled turn is replaced; an active one is
                        // never overwritten.
                        val capturing = changed == AndroidCaptureState.Starting ||
                            changed == AndroidCaptureState.Listening ||
                            changed is AndroidCaptureState.Transcribing
                        if (latestHandsFree && capturing && latestTurnState.isTerminal) {
                            turnState = AndroidTurnState(phase = AndroidTurnPhase.Listening)
                        }
                    }
                },
                onHandsFreeChange = { armed -> mainHandler.post { handsFree = armed } },
                onInitiation = { result ->
                    mainHandler.post {
                        applyInitiationState(
                            state = result,
                            recordAcceptedInput = true,
                        )
                    }
                },
            )
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRevision += 1
        if (granted) captureController?.beginCapture()
    }

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

    fun recover() {
        if (recoveryState.isRecovering) return
        workExecutor.execute { recoveryController.recover() }
    }

    fun resendUnconfirmedTurn() {
        if (resendInFlight) return
        resendInFlight = true
        workExecutor.execute {
            val result = recoveryController.resendUnconfirmedTurn()
            mainHandler.post {
                resendInFlight = false
                resendResult = result
                if (result is AndroidResendResult.Sent) {
                    lastRequest = recoveryController.state.unconfirmedTurn?.request ?: lastRequest
                    initiationState = AndroidInitiationState.Accepted(result.binding)
                    turnState = AndroidTurnState.awaitingEvents(result.binding)
                }
            }
        }
    }

    fun discardUnconfirmedTurn() {
        recoveryController.discardUnconfirmedTurn()
        lastRequest = null
        initiationState = AndroidInitiationState.Idle
        turnState = AndroidTurnState()
        resendResult = null
    }

    // Focus restoration: when a turn settles, return focus to the composer so
    // the next action is reachable without traversing the whole screen again.
    LaunchedEffect(turnState.isTerminal, initiationState) {
        if (turnState.isTerminal && initiationState is AndroidInitiationState.Accepted) {
            // Whatever was said is kept, including a partial answer from an
            // interrupted turn.
            if (turnState.responseText.isNotBlank()) {
                recorder?.recordResponse(turnState.responseText)
                historyRevision += 1
            }
            if (turnState.phase != AndroidTurnPhase.Disconnected) {
                lastRequest = null
            }
            // FR5: a completed turn reopens the window; anything else ends it.
            captureController?.onTurnSettled(turnState.phase)
            if (!handsFree) {
                runCatching { promptFocus.requestFocus() }
            }
        }
    }

    val stateColors = LocalHermesStateColors.current
    val motionMode = rememberAndroidMotionMode()

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
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TypedComposerZone(
                                prompt = prompt,
                                onPromptChange = ::updatePrompt,
                                promptFocus = promptFocus,
                                promptHistory = promptHistory,
                                onPromptHistoryChange = { promptHistory = it },
                                canEditPrompt = canEditPrompt,
                                isConnected = isConnected,
                                composerBlock = composerBlock,
                                isInitiating = initiationInFlight,
                                onSend = { initiate(AndroidTurnInput.Typed(prompt)) },
                            )

                            if (captureController == null) {
                                TapToSpeakFallback(
                                    enabled = isAuthorized && isConnected &&
                                        !hasAcceptedTurn &&
                                        !hasUnresolvedTurn &&
                                        !initiationInFlight,
                                    onTapToSpeak = { initiate(AndroidTurnInput.TapToSpeak) },
                                )
                            } else if (captureController.isCapturing) {
                                ActiveCaptureZone(
                                    captureState = captureState,
                                    handsFree = handsFree,
                                    motionMode = motionMode,
                                    onStop = { captureController.finishCapture() },
                                    onCancel = { captureController.cancelCapture() },
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
                                    microphonePermission = microphonePermission,
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
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
                                onRecover = { recover() },
                                onEditRelay = { configurationVisible = true },
                                onResend = { resendUnconfirmedTurn() },
                                onDiscard = { discardUnconfirmedTurn() },
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
                                onInterrupt = { binding -> clientPort.interruptTurn(binding) },
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
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RelayConfigurationScreen(
                            controller = configurationController,
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
                            historyRevision += 1
                        },
                        sheetVisibility = true,
                        onDismiss = { historyVisible = false },
                    )
                }
            }
        }
    }
}
