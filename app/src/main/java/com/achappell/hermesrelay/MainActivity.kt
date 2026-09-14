package com.achappell.hermesrelay

import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = clientPort,
                    configuration = configuration,
                    speechInput = PlatformSpeechInput(applicationContext),
                    historyStore = historyStore,
                )
            }
        }
    }
}

@Composable
internal fun AndroidClientScreen(
    clientPort: AndroidClientPort,
    configuration: RelayConfigurationController? = null,
    speechInput: AndroidSpeechInput? = null,
    historyStore: AndroidHistoryStore? = null,
) {
    var configurationRevision by remember { mutableStateOf(0) }
    val snapshot = remember(clientPort, configurationRevision) { clientPort.snapshot() }
    var configurationVisible by rememberSaveable {
        mutableStateOf(snapshot.selectedProfile == null)
    }
    val controller = remember(clientPort) { AndroidInitiationController(clientPort) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var initiationState by remember { mutableStateOf<AndroidInitiationState>(AndroidInitiationState.Idle) }
    var turnState by remember { mutableStateOf(AndroidTurnState()) }
    var recoveryState by remember { mutableStateOf(AndroidRecoveryState()) }
    var lastRequest by remember { mutableStateOf<AndroidTurnRequest?>(null) }
    var resendResult by remember { mutableStateOf<AndroidResendResult?>(null) }
    val recoveryController = remember(clientPort) {
        AndroidRecoveryController(clientPort) { changed -> recoveryState = changed }
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
    LaunchedEffect(snapshot.selectedProfile?.id) {
        if (snapshot.selectedProfile == null) {
            configurationVisible = true
        }
    }
    val isAuthorized = snapshot.authorizationState == AndroidAuthorizationState.Verified &&
        snapshot.selectedProfile != null
    val isConnected = recoveryState.connection == AndroidConnectionState.Connected
    val acceptedBinding = (initiationState as? AndroidInitiationState.Accepted)?.binding
    val hasAcceptedTurn = acceptedBinding != null && !turnState.isTerminal

    DisposableEffect(clientPort, acceptedBinding) {
        val observation = acceptedBinding?.let { binding ->
            clientPort.observeTurn(binding) { event ->
                val previous = turnState
                val next = AndroidTurnStateReducer.reduce(previous, event)
                turnState = next
                if (next.phase == AndroidTurnPhase.Disconnected &&
                    previous.phase != AndroidTurnPhase.Disconnected
                ) {
                    recoveryController.transportLost(
                        reason = next.unavailableReason.orEmpty(),
                        inFlightTurn = lastRequest?.let { request ->
                            AndroidUnconfirmedTurn(binding, request)
                        },
                    )
                }
            }
        }
        onDispose {
            observation?.cancel()
        }
    }

    fun initiate(input: AndroidTurnInput) {
        val profile = snapshot.selectedProfile
        val state = controller.initiate(input)
        initiationState = state
        if (state is AndroidInitiationState.Accepted) {
            lastRequest = profile?.let { AndroidTurnRequest(it, input) }
            resendResult = null
            turnState = AndroidTurnState.awaitingEvents(state.binding)
            (input as? AndroidTurnInput.Typed)?.let { typed ->
                recorder?.recordDraft("")
                recorder?.recordUserTurn(typed.text)
                promptHistory = promptHistory.record(typed.text)
                // The composer empties once the turn is accepted; a sent
                // prompt lingering in the box reads as unsent.
                prompt = ""
                historyRevision += 1
            }
        }
    }

    val captureController = remember(clientPort, speechInput) {
        speechInput?.let { input ->
            AndroidCaptureController(
                speech = input,
                initiation = controller,
                isConnected = { recoveryState.connection == AndroidConnectionState.Connected },
                isAuthorized = {
                    val current = clientPort.snapshot()
                    current.selectedProfile != null &&
                        current.authorizationState == AndroidAuthorizationState.Verified
                },
                currentSessionId = { recoveryController.state.sessionId },
                onStateChange = { changed ->
                    captureState = changed
                    // Hands-free reopens the microphone after a turn settles.
                    // Leaving the settled turn's phase on screen would claim the
                    // conversation had ended while the microphone was live, so
                    // the reopened window becomes the next turn's Listening
                    // phase. Only a settled turn is replaced; an active one is
                    // never overwritten.
                    val capturing = changed == AndroidCaptureState.Starting ||
                        changed == AndroidCaptureState.Listening ||
                        changed is AndroidCaptureState.Transcribing
                    if (handsFree && capturing && turnState.isTerminal) {
                        turnState = AndroidTurnState(phase = AndroidTurnPhase.Listening)
                    }
                },
                onHandsFreeChange = { armed -> handsFree = armed },
                onInitiation = { result ->
                    initiationState = result
                    if (result is AndroidInitiationState.Accepted) {
                        turnState = AndroidTurnState.awaitingEvents(result.binding)
                        (captureState as? AndroidCaptureState.Submitted)?.let { submitted ->
                            recorder?.recordUserTurn(submitted.transcript)
                            historyRevision += 1
                        }
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
        hasUnconfirmedTurn = recoveryState.hasUnconfirmedTurn,
        captureBlock = captureBlock,
    )
    val composerBlock = resolveAndroidComposerBlock(
        hasProfile = snapshot.selectedProfile != null,
        isAuthorized = isAuthorized,
        isConnected = isConnected,
        hasAcceptedTurn = hasAcceptedTurn,
        prompt = prompt,
    )

    fun updatePrompt(value: String) {
        prompt = value
        recorder?.recordDraft(value)
    }

    fun resendUnconfirmedTurn() {
        val result = recoveryController.resendUnconfirmedTurn()
        resendResult = result
        if (result is AndroidResendResult.Sent) {
            initiationState = AndroidInitiationState.Accepted(result.binding)
            turnState = AndroidTurnState.awaitingEvents(result.binding)
        }
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
                DoorwayHeaderZone(snapshot = snapshot)
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
                                isAuthorized = isAuthorized,
                                isConnected = isConnected,
                                composerBlock = composerBlock,
                                onSend = { initiate(AndroidTurnInput.Typed(prompt)) },
                            )

                            if (captureController == null) {
                                TapToSpeakFallback(
                                    enabled = isAuthorized && isConnected && !hasAcceptedTurn,
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
                    item {
                        DoorwayBoundaryZone(snapshot = snapshot)
                    }

                    doorwayState?.let { currentState ->
                        item {
                            DoorwayStateZone(
                                state = currentState,
                                connectionState = recoveryState.connection,
                                canConfigure = configuration != null &&
                                    currentState is AndroidDoorwayState.NoProfile,
                                canEditRelay = configuration != null &&
                                    snapshot.selectedProfile != null &&
                                    !configurationVisible &&
                                    isConnected &&
                                    !recoveryState.hasUnconfirmedTurn,
                                onConfigure = { configurationVisible = true },
                                onEditRelay = { configurationVisible = true },
                            )
                        }
                    }

                    // With a selected Profile the live doorway owns the first
                    // screen. Configuration remains in the same rail, but it
                    // no longer pushes the conversation below the fold.
                    if (configurationVisible && snapshot.selectedProfile == null) {
                        configuration?.let { configurationController ->
                            item {
                                RelayConfigurationScreen(
                                    controller = configurationController,
                                    onChanged = {
                                        configurationRevision += 1
                                        configurationVisible =
                                            configurationController.collection.selectedId == null
                                    },
                                )
                            }
                        }
                    }

                    item {
                        Column {
                            ConnectionRecoveryZone(
                                recoveryState = recoveryState,
                                resendResult = resendResult,
                                isAuthorized = isAuthorized,
                                isConnected = isConnected,
                                canEditRelay = configuration != null &&
                                    snapshot.selectedProfile != null &&
                                    !configurationVisible,
                                onRecover = { recoveryController.recover() },
                                onEditRelay = { configurationVisible = true },
                                onResend = { resendUnconfirmedTurn() },
                                onDiscard = { recoveryController.discardUnconfirmedTurn() },
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

                    if (configurationVisible && snapshot.selectedProfile != null) {
                        configuration?.let { configurationController ->
                            item {
                                RelayConfigurationScreen(
                                    controller = configurationController,
                                    onChanged = {
                                        configurationRevision += 1
                                        configurationVisible =
                                            configurationController.collection.selectedId == null
                                    },
                                )
                            }
                        }
                    }

                    recorder?.let { history ->
                        @Suppress("UNUSED_EXPRESSION")
                        historyRevision // re-read the recorder when it changes

                        item(key = "android_history_$historyRevision") {
                            Column {
                                LocalHistoryZone(
                                    recorder = history,
                                    exporter = exporter,
                                    profileDisplayName = snapshot.selectedProfile?.displayName,
                                    onClear = {
                                        history.clear()
                                        historyRevision += 1
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
