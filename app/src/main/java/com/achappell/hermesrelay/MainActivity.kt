package com.achappell.hermesrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val credentials = KeystoreRelayCredentialStore(applicationContext)
        val configuration = RelayConfigurationController(
            profiles = FileRelayProfileStore(applicationContext),
            credentials = credentials,
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
) {
    var configurationRevision by remember { mutableStateOf(0) }
    val snapshot = remember(clientPort, configurationRevision) { clientPort.snapshot() }
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
    val promptFocus = remember { FocusRequester() }
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
                onStateChange = { changed -> captureState = changed },
                onInitiation = { result ->
                    initiationState = result
                    if (result is AndroidInitiationState.Accepted) {
                        turnState = AndroidTurnState.awaitingEvents(result.binding)
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
            runCatching { promptFocus.requestFocus() }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .semantics { isTraversalGroup = true }
                .widthIn(max = 720.dp)
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                modifier = Modifier.a11yHeading(A11yOrder.HEADER),
                text = stringResource(snapshot.titleRes),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                modifier = Modifier.a11yOrder(A11yOrder.HEADER),
                text = stringResource(snapshot.descriptionRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    modifier = Modifier.padding(20.dp),
                    text = stringResource(snapshot.boundaryRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                modifier = Modifier.a11yHeading(A11yOrder.PROFILE),
                text = stringResource(R.string.android_profile_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                modifier = Modifier.a11yOrder(A11yOrder.PROFILE),
                text = snapshot.selectedProfile?.displayName
                    ?: stringResource(R.string.android_profile_none),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                modifier = Modifier.a11yOrder(A11yOrder.PROFILE, LiveRegionMode.Polite),
                text = stringResource(
                    R.string.android_authorization_label,
                    stringResource(snapshot.authorizationState.labelRes()),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            configuration?.let { controller ->
                RelayConfigurationScreen(
                    controller = controller,
                    onChanged = { configurationRevision += 1 },
                )
            }

            if (!isAuthorized) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        modifier = Modifier.padding(20.dp),
                        text = stringResource(R.string.android_initiation_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                modifier = Modifier
                    .testTag("android_typed_prompt")
                    .focusRequester(promptFocus)
                    .a11yOrder(A11yOrder.ACTION),
                value = prompt,
                onValueChange = { prompt = it },
                enabled = isAuthorized,
                label = { Text(stringResource(R.string.android_prompt_label)) },
            )
            if (!isConnected && prompt.isNotBlank()) {
                Text(
                    modifier = Modifier.testTag("android_cached_draft"),
                    text = stringResource(R.string.android_cached_draft),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    initiate(AndroidTurnInput.Typed(prompt))
                },
                modifier = Modifier.a11yOrder(A11yOrder.ACTION),
                enabled = isAuthorized && isConnected && !hasAcceptedTurn && prompt.isNotBlank(),
            ) {
                Text(stringResource(R.string.android_start_typed_turn))
            }
            if (captureController == null) {
                Button(
                    onClick = { initiate(AndroidTurnInput.TapToSpeak) },
                    enabled = isAuthorized && isConnected && !hasAcceptedTurn,
                ) {
                    Text(stringResource(R.string.android_tap_to_speak))
                }
            } else if (captureController.isCapturing) {
                Text(
                    modifier = Modifier
                        .testTag("android_capture_state")
                        .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                    text = stringResource(captureState.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                )

                // The participant's own words, live, before any turn exists.
                // Provisional until the recognizer finalizes them.
                (captureState as? AndroidCaptureState.Transcribing)
                    ?.partial
                    ?.takeIf { it.isNotBlank() }
                    ?.let { partial ->
                        Text(
                            text = stringResource(R.string.android_capture_participant),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            modifier = Modifier
                                .testTag("android_capture_partial")
                                .a11yOrder(A11yOrder.RESPONSE, LiveRegionMode.Polite),
                            text = partial,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                Button(
                    modifier = Modifier
                        .testTag("android_capture_stop")
                        .a11yOrder(A11yOrder.ACTION),
                    onClick = { captureController.finishCapture() },
                ) {
                    Text(stringResource(R.string.android_capture_stop))
                }
                Button(onClick = { captureController.cancelCapture() }) {
                    Text(stringResource(R.string.android_capture_cancel))
                }
            } else {
                val block = remember(
                    isAuthorized,
                    isConnected,
                    permissionRevision,
                    captureState,
                ) { captureController.blockingReason() }

                Button(
                    modifier = Modifier
                        .testTag("android_tap_to_speak")
                        .a11yOrder(A11yOrder.ACTION),
                    onClick = {
                        if (block == AndroidCaptureBlock.PermissionRequired) {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            captureController.beginCapture()
                        }
                    },
                    enabled = !hasAcceptedTurn &&
                        block != AndroidCaptureBlock.ProfileUnavailable &&
                        block != AndroidCaptureBlock.NotConnected &&
                        block != AndroidCaptureBlock.RecognizerUnavailable,
                ) {
                    Text(
                        stringResource(
                            if (block == AndroidCaptureBlock.PermissionRequired) {
                                R.string.android_capture_grant
                            } else {
                                R.string.android_tap_to_speak
                            },
                        ),
                    )
                }

                block?.let { reason ->
                    Text(
                        modifier = Modifier
                            .testTag("android_capture_block")
                            .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                        text = stringResource(reason.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                (captureState as? AndroidCaptureState.Failed)?.let { failed ->
                    Text(
                        modifier = Modifier
                            .testTag("android_capture_failed")
                            .a11yOrder(A11yOrder.STATE, LiveRegionMode.Assertive),
                        text = stringResource(failed.reason.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!isConnected || recoveryState.hasUnconfirmedTurn) {
                Text(
                    modifier = Modifier
                        .testTag("android_connection_state")
                        .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                    text = stringResource(
                        R.string.android_connection_label,
                        recoveryState.connection.label(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (!isConnected && isAuthorized) {
                Button(
                    modifier = Modifier
                        .testTag("android_connect")
                        .a11yOrder(A11yOrder.ACTION),
                    onClick = { recoveryController.recover() },
                    enabled = !recoveryState.isRecovering,
                ) {
                    Text(stringResource(R.string.android_recover))
                }
            }

            if (recoveryState.hasUnconfirmedTurn) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        modifier = Modifier
                            .padding(20.dp)
                            .testTag("android_unconfirmed_turn"),
                        text = stringResource(R.string.android_unconfirmed_turn),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = { resendUnconfirmedTurn() }) {
                    Text(stringResource(R.string.android_resend_unconfirmed_turn))
                }
                Button(onClick = { recoveryController.discardUnconfirmedTurn() }) {
                    Text(stringResource(R.string.android_discard_unconfirmed_turn))
                }
            }

            when (val resend = resendResult) {
                null, is AndroidResendResult.Sent, AndroidResendResult.NothingToResend -> Unit
                AndroidResendResult.NotConnected -> Text(
                    text = stringResource(R.string.android_resend_not_connected),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is AndroidResendResult.Rejected -> Text(
                    text = stringResource(resend.reason.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (val state = initiationState) {
                AndroidInitiationState.Idle -> Unit
                is AndroidInitiationState.Accepted -> {
                    Text(
                        text = stringResource(
                            R.string.android_initiation_accepted,
                            snapshot.selectedProfile?.displayName
                                ?: state.binding.profileId,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (hasAcceptedTurn && clientPort.supportsInterrupt()) {
                        Button(
                            modifier = Modifier
                                .testTag("android_interrupt")
                                .a11yOrder(A11yOrder.ACTION),
                            onClick = { clientPort.interruptTurn(state.binding) },
                        ) {
                            Text(stringResource(R.string.android_interrupt))
                        }
                    }

                    if (turnState.phase == AndroidTurnPhase.Interrupted) {
                        Text(
                            modifier = Modifier
                                .testTag("android_interrupted")
                                .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                            text = stringResource(R.string.android_turn_interrupted),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (turnState.binding == state.binding && turnState.phase != AndroidTurnPhase.Idle) {
                        Text(
                            text = stringResource(
                                R.string.android_turn_phase_label,
                                stringResource(turnState.phase.labelRes()),
                            ),
                            modifier = Modifier
                                .testTag("android_turn_phase")
                                .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (turnState.responseText.isNotEmpty()) {
                        Text(
                            modifier = Modifier.a11yHeading(A11yOrder.RESPONSE),
                            text = stringResource(R.string.android_response_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // Retained text stays visible during an outage, but it
                        // must not read as a live conversation.
                        if (!isConnected) {
                            Text(
                                modifier = Modifier
                                .testTag("android_cached_response")
                                .a11yOrder(A11yOrder.RESPONSE),
                                text = stringResource(R.string.android_cached_response),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            modifier = Modifier
                                .testTag("android_response_text")
                                .a11yOrder(A11yOrder.RESPONSE, LiveRegionMode.Polite),
                            text = turnState.responseText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    if (turnState.audio == AndroidAudioDelivery.Unavailable) {
                        Text(
                            modifier = Modifier
                                .testTag("android_audio_unavailable")
                                .a11yOrder(A11yOrder.STATE, LiveRegionMode.Assertive),
                            text = stringResource(R.string.android_audio_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (turnState.phase == AndroidTurnPhase.Disconnected) {
                        Text(
                            modifier = Modifier
                                .testTag("android_disconnected")
                                .a11yOrder(A11yOrder.STATE, LiveRegionMode.Assertive),
                            text = stringResource(R.string.android_turn_disconnected),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is AndroidInitiationState.Rejected -> {
                    Text(
                        text = stringResource(state.reason.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun AndroidAuthorizationState.labelRes(): Int = when (this) {
    AndroidAuthorizationState.NotConfigured -> R.string.android_authorization_not_configured
    AndroidAuthorizationState.Verifying -> R.string.android_authorization_verifying
    AndroidAuthorizationState.Verified -> R.string.android_authorization_verified
    AndroidAuthorizationState.Unavailable -> R.string.android_authorization_unavailable
}

private fun AndroidInitiationFailure.messageRes(): Int = when (this) {
    AndroidInitiationFailure.ProfileUnavailable -> R.string.android_failure_profile_unavailable
    AndroidInitiationFailure.AuthorizationRequired -> R.string.android_failure_authorization_required
    AndroidInitiationFailure.EmptyTypedPrompt -> R.string.android_failure_empty_prompt
    AndroidInitiationFailure.SessionUnavailable -> R.string.android_failure_session_unavailable
}

@Composable
private fun AndroidConnectionState.label(): String = when (this) {
    AndroidConnectionState.Connected -> stringResource(R.string.android_connection_connected)
    AndroidConnectionState.Disconnected -> stringResource(R.string.android_connection_disconnected)
    is AndroidConnectionState.Reconnecting -> stringResource(
        R.string.android_connection_reconnecting,
        attempt,
        of,
    )

    is AndroidConnectionState.Failed -> stringResource(R.string.android_connection_failed, reason)
}

/**
 * `UX-DR21` fixes the accessibility reading order as
 * Profile -> state -> response/Transcription -> action, which is not the same
 * as the visual order. Every element carries its band explicitly so assistive
 * technology traverses the doorway in the order the requirement names.
 */
private object A11yOrder {
    const val HEADER = -1f
    const val PROFILE = 0f
    const val STATE = 1f
    const val RESPONSE = 2f
    const val ACTION = 3f
}

private fun Modifier.a11yOrder(index: Float): Modifier =
    semantics { traversalIndex = index }

private fun Modifier.a11yOrder(index: Float, announce: LiveRegionMode): Modifier =
    semantics {
        traversalIndex = index
        liveRegion = announce
    }

private fun Modifier.a11yHeading(index: Float): Modifier =
    semantics {
        traversalIndex = index
        heading()
    }

private fun AndroidCaptureState.labelRes(): Int = when (this) {
    AndroidCaptureState.Starting -> R.string.android_capture_starting
    AndroidCaptureState.Listening -> R.string.android_capture_listening
    is AndroidCaptureState.Transcribing -> R.string.android_capture_transcribing
    else -> R.string.android_capture_listening
}

private fun AndroidCaptureBlock.messageRes(): Int = when (this) {
    AndroidCaptureBlock.PermissionRequired -> R.string.android_capture_block_permission
    AndroidCaptureBlock.RecognizerUnavailable -> R.string.android_capture_block_recognizer
    AndroidCaptureBlock.NotConnected -> R.string.android_capture_block_not_connected
    AndroidCaptureBlock.ProfileUnavailable -> R.string.android_capture_block_profile
    AndroidCaptureBlock.SessionReplaced -> R.string.android_capture_block_session_replaced
}

private fun AndroidSpeechFailure.messageRes(): Int = when (this) {
    AndroidSpeechFailure.PermissionRequired -> R.string.android_capture_failed_permission
    AndroidSpeechFailure.RecognizerUnavailable -> R.string.android_capture_failed_recognizer
    AndroidSpeechFailure.NoSpeechHeard -> R.string.android_capture_failed_no_speech
    AndroidSpeechFailure.NetworkUnavailable -> R.string.android_capture_failed_network
    AndroidSpeechFailure.RecognizerBusy -> R.string.android_capture_failed_busy
    AndroidSpeechFailure.Unknown -> R.string.android_capture_failed_unknown
}

private fun AndroidTurnPhase.labelRes(): Int = when (this) {
    AndroidTurnPhase.Idle -> R.string.android_turn_phase_waiting
    AndroidTurnPhase.Listening -> R.string.android_turn_phase_listening
    AndroidTurnPhase.Transcribing -> R.string.android_turn_phase_transcribing
    AndroidTurnPhase.Thinking -> R.string.android_turn_phase_thinking
    AndroidTurnPhase.Buffering -> R.string.android_turn_phase_buffering
    AndroidTurnPhase.Speaking -> R.string.android_turn_phase_speaking
    AndroidTurnPhase.Complete -> R.string.android_turn_phase_complete
    AndroidTurnPhase.Unavailable -> R.string.android_turn_phase_unavailable
    AndroidTurnPhase.Disconnected -> R.string.android_turn_phase_disconnected
    AndroidTurnPhase.Interrupted -> R.string.android_turn_phase_interrupted
}
