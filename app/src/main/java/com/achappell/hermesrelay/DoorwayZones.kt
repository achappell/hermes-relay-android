package com.achappell.hermesrelay

import android.Manifest
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

/**
 * The conversation doorway, decomposed into the zones the Android visual design
 * pass names: identity, composer, voice controls, connection and recovery, the
 * live turn, and Local History.
 *
 * This split is structural only. `AndroidClientScreen` still owns every piece of
 * state and every decision about it; each zone here renders what it is handed
 * and reports interactions back. Emission order, test tags, and the `UX-DR21`
 * traversal bands are unchanged from the single-composable version, because the
 * instrumentation suite asserts on all three and `5-A-2` is a settled
 * requirement rather than something this decomposition may renegotiate.
 */

// ---------------------------------------------------------------------------
// Zone 1 — Profile and connection identity
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.DoorwayHeaderZone(
    snapshot: AndroidClientSnapshot,
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
}

@Composable
internal fun UnauthorizedNotice() {
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

// ---------------------------------------------------------------------------
// Zone 4a — Typed composer
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.TypedComposerZone(
    prompt: String,
    onPromptChange: (String) -> Unit,
    promptFocus: FocusRequester,
    promptHistory: AndroidPromptHistory,
    onPromptHistoryChange: (AndroidPromptHistory) -> Unit,
    isAuthorized: Boolean,
    isConnected: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier
            .testTag("android_typed_prompt")
            .focusRequester(promptFocus)
            .a11yOrder(A11yOrder.ACTION),
        value = prompt,
        onValueChange = onPromptChange,
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
    if (!promptHistory.isEmpty) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                modifier = Modifier
                    .testTag("android_prompt_previous")
                    .a11yOrder(A11yOrder.ACTION),
                onClick = {
                    val (next, recalled) = promptHistory.previous(prompt)
                    onPromptHistoryChange(next)
                    recalled?.let(onPromptChange)
                },
            ) {
                Text(stringResource(R.string.android_prompt_previous))
            }
            TextButton(
                modifier = Modifier
                    .testTag("android_prompt_next")
                    .a11yOrder(A11yOrder.ACTION),
                enabled = promptHistory.isNavigating,
                onClick = {
                    val (next, recalled) = promptHistory.next()
                    onPromptHistoryChange(next)
                    recalled?.let(onPromptChange)
                },
            ) {
                Text(stringResource(R.string.android_prompt_next))
            }
        }
    }

    Button(
        onClick = onSend,
        modifier = Modifier.a11yOrder(A11yOrder.ACTION),
        enabled = canSend,
    ) {
        Text(stringResource(R.string.android_start_typed_turn))
    }
}

// ---------------------------------------------------------------------------
// Zone 4b — Voice controls
// ---------------------------------------------------------------------------

/**
 * The no-recognizer fallback. Without an `AndroidSpeechInput` the doorway can
 * still open a tap-to-speak turn; it simply cannot transcribe locally.
 */
@Composable
internal fun TapToSpeakFallback(
    enabled: Boolean,
    onTapToSpeak: () -> Unit,
) {
    Button(
        onClick = onTapToSpeak,
        enabled = enabled,
    ) {
        Text(stringResource(R.string.android_tap_to_speak))
    }
}

@Composable
internal fun ColumnScope.ActiveCaptureZone(
    captureState: AndroidCaptureState,
    handsFree: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    if (handsFree) {
        Text(
            modifier = Modifier
                .testTag("android_hands_free_active")
                .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
            text = stringResource(R.string.android_hands_free_active),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        modifier = Modifier
            .testTag("android_capture_state")
            .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
        text = stringResource(captureState.labelRes()),
        style = MaterialTheme.typography.titleMedium,
    )

    // The participant's own words, live, before any turn exists. Provisional
    // until the recognizer finalizes them.
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
        onClick = onStop,
    ) {
        Text(stringResource(R.string.android_capture_stop))
    }
    Button(onClick = onCancel) {
        Text(stringResource(R.string.android_capture_cancel))
    }
}

@Composable
internal fun ColumnScope.IdleCaptureZone(
    captureController: AndroidCaptureController,
    captureState: AndroidCaptureState,
    handsFree: Boolean,
    hasAcceptedTurn: Boolean,
    isAuthorized: Boolean,
    isConnected: Boolean,
    permissionRevision: Int,
    microphonePermission: ManagedActivityResultLauncher<String, Boolean>,
) {
    val block = remember(
        isAuthorized,
        isConnected,
        permissionRevision,
        captureState,
    ) { captureController.blockingReason() }

    if (block == null) {
        Button(
            modifier = Modifier
                .testTag("android_hands_free")
                .a11yOrder(A11yOrder.ACTION),
            onClick = {
                if (captureController.isHandsFree) {
                    captureController.disarmHandsFree()
                } else {
                    captureController.armHandsFree()
                }
            },
        ) {
            Text(
                stringResource(
                    if (handsFree) {
                        R.string.android_hands_free_stop
                    } else {
                        R.string.android_hands_free_start
                    },
                ),
            )
        }
    }

    captureController.lastHandsFreeExit
        // A deliberate disarm needs no explanation; the user did it.
        ?.takeIf { !handsFree && it != AndroidHandsFreeExit.Disarmed }
        ?.let { exit ->
            Text(
                modifier = Modifier
                    .testTag("android_hands_free_exit")
                    .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                text = stringResource(exit.messageRes()),
                style = MaterialTheme.typography.bodySmall,
            )
        }

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

// ---------------------------------------------------------------------------
// Zone 1b — Connection state and recovery
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.ConnectionRecoveryZone(
    recoveryState: AndroidRecoveryState,
    resendResult: AndroidResendResult?,
    isAuthorized: Boolean,
    isConnected: Boolean,
    onRecover: () -> Unit,
    onResend: () -> Unit,
    onDiscard: () -> Unit,
) {
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
            onClick = onRecover,
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
        Button(onClick = onResend) {
            Text(stringResource(R.string.android_resend_unconfirmed_turn))
        }
        Button(onClick = onDiscard) {
            Text(stringResource(R.string.android_discard_unconfirmed_turn))
        }
    }

    when (resendResult) {
        null, is AndroidResendResult.Sent, AndroidResendResult.NothingToResend -> Unit
        AndroidResendResult.NotConnected -> Text(
            text = stringResource(R.string.android_resend_not_connected),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )

        is AndroidResendResult.Rejected -> Text(
            text = stringResource(resendResult.reason.messageRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------------------------------------------------------------------------
// Zones 2 and 3 — Live turn phase and the response rail
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.TurnZone(
    initiationState: AndroidInitiationState,
    turnState: AndroidTurnState,
    snapshot: AndroidClientSnapshot,
    hasAcceptedTurn: Boolean,
    isConnected: Boolean,
    supportsInterrupt: Boolean,
    onInterrupt: (AndroidTurnBinding) -> Unit,
) {
    when (initiationState) {
        AndroidInitiationState.Idle -> Unit
        is AndroidInitiationState.Accepted -> {
            Text(
                text = stringResource(
                    R.string.android_initiation_accepted,
                    snapshot.selectedProfile?.displayName
                        ?: initiationState.binding.profileId,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (hasAcceptedTurn && supportsInterrupt) {
                Button(
                    modifier = Modifier
                        .testTag("android_interrupt")
                        .a11yOrder(A11yOrder.ACTION),
                    onClick = { onInterrupt(initiationState.binding) },
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

            if (turnState.binding == initiationState.binding &&
                turnState.phase != AndroidTurnPhase.Idle
            ) {
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
                // Retained text stays visible during an outage, but it must not
                // read as a live conversation.
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
                text = stringResource(initiationState.reason.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Zone 3b — Local History
// ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.LocalHistoryZone(
    recorder: AndroidHistoryRecorder,
    exporter: TranscriptExporter,
    profileDisplayName: String?,
    onClear: () -> Unit,
) {
    val context = LocalContext.current

    Text(
        modifier = Modifier.a11yHeading(A11yOrder.RESPONSE),
        text = stringResource(R.string.android_history_label),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        modifier = Modifier.a11yOrder(A11yOrder.RESPONSE),
        text = stringResource(R.string.android_history_boundary),
        style = MaterialTheme.typography.bodySmall,
    )

    val entries = recorder.history.entries
    if (entries.isEmpty()) {
        Text(
            modifier = Modifier
                .testTag("android_history_empty")
                .a11yOrder(A11yOrder.RESPONSE),
            text = stringResource(R.string.android_history_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    entries.takeLast(20).forEach { entry ->
        Text(
            modifier = Modifier.a11yOrder(A11yOrder.RESPONSE),
            text = stringResource(
                if (entry.role == AndroidTranscriptRole.User) {
                    R.string.android_history_you
                } else {
                    R.string.android_history_hermes
                },
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            modifier = Modifier
                .testTag("android_history_entry")
                .a11yOrder(A11yOrder.RESPONSE),
            text = entry.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    // Resolved in composable scope so a configuration change cannot leave the
    // share sheet holding a stale string.
    val shareTitle = stringResource(R.string.android_history_share_title)

    fun share(format: AndroidExportFormat) {
        val body = exporter.export(recorder.history, format, profileDisplayName)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            modifier = Modifier
                .testTag("android_history_share_text")
                .a11yOrder(A11yOrder.ACTION),
            onClick = { share(AndroidExportFormat.PlainText) },
        ) {
            Text(stringResource(R.string.android_history_share_text))
        }
        TextButton(
            modifier = Modifier
                .testTag("android_history_share_markdown")
                .a11yOrder(A11yOrder.ACTION),
            onClick = { share(AndroidExportFormat.Markdown) },
        ) {
            Text(stringResource(R.string.android_history_share_markdown))
        }
    }

    Button(
        modifier = Modifier
            .testTag("android_history_clear")
            .a11yOrder(A11yOrder.ACTION),
        onClick = onClear,
    ) {
        Text(stringResource(R.string.android_history_clear))
    }
}

// ---------------------------------------------------------------------------
// Accessibility banding and label resolution
// ---------------------------------------------------------------------------

/**
 * `UX-DR21` fixes the accessibility reading order as
 * Profile -> state -> response/Transcription -> action, which is not the same
 * as the visual order. Every element carries its band explicitly so assistive
 * technology traverses the doorway in the order the requirement names.
 */
internal object A11yOrder {
    const val HEADER = -1f
    const val PROFILE = 0f
    const val STATE = 1f
    const val RESPONSE = 2f
    const val ACTION = 3f
}

internal fun Modifier.a11yOrder(index: Float): Modifier =
    semantics { traversalIndex = index }

internal fun Modifier.a11yOrder(index: Float, announce: LiveRegionMode): Modifier =
    semantics {
        traversalIndex = index
        liveRegion = announce
    }

internal fun Modifier.a11yHeading(index: Float): Modifier =
    semantics {
        traversalIndex = index
        heading()
    }

internal fun AndroidAuthorizationState.labelRes(): Int = when (this) {
    AndroidAuthorizationState.NotConfigured -> R.string.android_authorization_not_configured
    AndroidAuthorizationState.Verifying -> R.string.android_authorization_verifying
    AndroidAuthorizationState.Verified -> R.string.android_authorization_verified
    AndroidAuthorizationState.Unavailable -> R.string.android_authorization_unavailable
}

internal fun AndroidInitiationFailure.messageRes(): Int = when (this) {
    AndroidInitiationFailure.ProfileUnavailable -> R.string.android_failure_profile_unavailable
    AndroidInitiationFailure.AuthorizationRequired -> R.string.android_failure_authorization_required
    AndroidInitiationFailure.EmptyTypedPrompt -> R.string.android_failure_empty_prompt
    AndroidInitiationFailure.SessionUnavailable -> R.string.android_failure_session_unavailable
}

@Composable
internal fun AndroidConnectionState.label(): String = when (this) {
    AndroidConnectionState.Connected -> stringResource(R.string.android_connection_connected)
    AndroidConnectionState.Disconnected -> stringResource(R.string.android_connection_disconnected)
    is AndroidConnectionState.Reconnecting -> stringResource(
        R.string.android_connection_reconnecting,
        attempt,
        of,
    )

    is AndroidConnectionState.Failed -> stringResource(R.string.android_connection_failed, reason)
}

internal fun AndroidCaptureState.labelRes(): Int = when (this) {
    AndroidCaptureState.Starting -> R.string.android_capture_starting
    AndroidCaptureState.Listening -> R.string.android_capture_listening
    is AndroidCaptureState.Transcribing -> R.string.android_capture_transcribing
    else -> R.string.android_capture_listening
}

internal fun AndroidHandsFreeExit.messageRes(): Int = when (this) {
    AndroidHandsFreeExit.ExactStop -> R.string.android_hands_free_exit_stop
    AndroidHandsFreeExit.Silence -> R.string.android_hands_free_exit_silence
    AndroidHandsFreeExit.Failure -> R.string.android_hands_free_exit_failure
    AndroidHandsFreeExit.SessionEnded -> R.string.android_hands_free_exit_session
    // Never shown: a deliberate disarm is not narrated back to the user.
    AndroidHandsFreeExit.Disarmed -> R.string.android_hands_free_exit_stop
}

internal fun AndroidCaptureBlock.messageRes(): Int = when (this) {
    AndroidCaptureBlock.PermissionRequired -> R.string.android_capture_block_permission
    AndroidCaptureBlock.RecognizerUnavailable -> R.string.android_capture_block_recognizer
    AndroidCaptureBlock.NotConnected -> R.string.android_capture_block_not_connected
    AndroidCaptureBlock.ProfileUnavailable -> R.string.android_capture_block_profile
    AndroidCaptureBlock.SessionReplaced -> R.string.android_capture_block_session_replaced
}

internal fun AndroidSpeechFailure.messageRes(): Int = when (this) {
    AndroidSpeechFailure.PermissionRequired -> R.string.android_capture_failed_permission
    AndroidSpeechFailure.RecognizerUnavailable -> R.string.android_capture_failed_recognizer
    AndroidSpeechFailure.NoSpeechHeard -> R.string.android_capture_failed_no_speech
    AndroidSpeechFailure.NetworkUnavailable -> R.string.android_capture_failed_network
    AndroidSpeechFailure.RecognizerBusy -> R.string.android_capture_failed_busy
    AndroidSpeechFailure.Unknown -> R.string.android_capture_failed_unknown
}

internal fun AndroidTurnPhase.labelRes(): Int = when (this) {
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
