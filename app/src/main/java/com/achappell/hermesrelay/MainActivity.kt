package com.achappell.hermesrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.testTag
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
        )

        setContent {
            HermesRelayTheme {
                AndroidClientScreen(clientPort, configuration)
            }
        }
    }
}

@Composable
internal fun AndroidClientScreen(
    clientPort: AndroidClientPort,
    configuration: RelayConfigurationController? = null,
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
    val isAuthorized = snapshot.authorizationState == AndroidAuthorizationState.Verified &&
        snapshot.selectedProfile != null
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

    fun resendUnconfirmedTurn() {
        val result = recoveryController.resendUnconfirmedTurn()
        resendResult = result
        if (result is AndroidResendResult.Sent) {
            initiationState = AndroidInitiationState.Accepted(result.binding)
            turnState = AndroidTurnState.awaitingEvents(result.binding)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(snapshot.titleRes),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
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
                text = stringResource(R.string.android_profile_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = snapshot.selectedProfile?.displayName
                    ?: stringResource(R.string.android_profile_none),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
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
                modifier = Modifier.testTag("android_typed_prompt"),
                value = prompt,
                onValueChange = { prompt = it },
                enabled = isAuthorized,
                label = { Text(stringResource(R.string.android_prompt_label)) },
            )
            Button(
                onClick = {
                    initiate(AndroidTurnInput.Typed(prompt))
                },
                enabled = isAuthorized && !hasAcceptedTurn && prompt.isNotBlank(),
            ) {
                Text(stringResource(R.string.android_start_typed_turn))
            }
            Button(
                onClick = {
                    initiate(AndroidTurnInput.TapToSpeak)
                },
                enabled = isAuthorized && !hasAcceptedTurn,
            ) {
                Text(stringResource(R.string.android_tap_to_speak))
            }

            if (recoveryState.connection != AndroidConnectionState.Connected ||
                recoveryState.hasUnconfirmedTurn
            ) {
                Text(
                    modifier = Modifier.testTag("android_connection_state"),
                    text = stringResource(
                        R.string.android_connection_label,
                        recoveryState.connection.label(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (recoveryState.connection != AndroidConnectionState.Connected) {
                Button(
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

                    if (turnState.binding == state.binding && turnState.phase != AndroidTurnPhase.Idle) {
                        Text(
                            text = stringResource(
                                R.string.android_turn_phase_label,
                                stringResource(turnState.phase.labelRes()),
                            ),
                            modifier = Modifier.testTag("android_turn_phase"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (turnState.responseText.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.android_response_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            modifier = Modifier.testTag("android_response_text"),
                            text = turnState.responseText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    if (turnState.audio == AndroidAudioDelivery.Unavailable) {
                        Text(
                            modifier = Modifier.testTag("android_audio_unavailable"),
                            text = stringResource(R.string.android_audio_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (turnState.phase == AndroidTurnPhase.Disconnected) {
                        Text(
                            modifier = Modifier.testTag("android_disconnected"),
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
}
