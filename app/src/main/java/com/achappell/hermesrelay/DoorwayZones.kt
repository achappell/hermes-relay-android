package com.achappell.hermesrelay

import android.Manifest
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors
import java.text.DateFormat
import java.util.Date

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
@OptIn(ExperimentalMaterial3Api::class)
internal fun DoorwayHeaderZone(
    snapshot: AndroidClientSnapshot,
    canConfigure: Boolean = false,
    canShowHistory: Boolean = false,
    onConfigure: () -> Unit = {},
    onShowHistory: () -> Unit = {},
) {
    val stateColors = LocalHermesStateColors.current
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val showMenu = canConfigure || canShowHistory
    val menuDescription = stringResource(R.string.android_menu_content_description)

    TopAppBar(
        modifier = Modifier.testTag("android_doorway_header"),
        title = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier.a11yHeading(A11yOrder.HEADER),
                    text = stringResource(snapshot.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    modifier = Modifier.a11yOrder(A11yOrder.HEADER),
                    text = stringResource(snapshot.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            Row(
                modifier = Modifier.padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        modifier = Modifier.a11yHeading(A11yOrder.PROFILE),
                        text = stringResource(R.string.android_profile_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        modifier = Modifier.a11yOrder(A11yOrder.PROFILE),
                        text = snapshot.selectedProfile?.displayName
                            ?: stringResource(R.string.android_profile_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = stateColors.identity,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.a11yOrder(A11yOrder.PROFILE, LiveRegionMode.Polite),
                        text = stringResource(
                            R.string.android_authorization_label,
                            stringResource(snapshot.authorizationState.labelRes()),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                if (showMenu) {
                    Box {
                        IconButton(
                            modifier = Modifier
                                .testTag("android_more_menu")
                                .a11yOrder(A11yOrder.ACTION)
                                .semantics {
                                    contentDescription = menuDescription
                                },
                            onClick = { menuExpanded = true },
                        ) {
                            Text(
                                text = "⋮",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            modifier = Modifier.testTag("android_navigation_menu"),
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (canConfigure) {
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("android_menu_configure_relay"),
                                    text = {
                                        Text(stringResource(R.string.android_configure_relay))
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onConfigure()
                                    },
                                )
                            }
                            if (canShowHistory) {
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("android_menu_history"),
                                    text = {
                                        Text(stringResource(R.string.android_history_label))
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onShowHistory()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = stateColors.consoleSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
internal fun DoorwayBoundaryZone(
    snapshot: AndroidClientSnapshot,
) {
    val stateColors = LocalHermesStateColors.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .a11yOrder(A11yOrder.HEADER),
        colors = CardDefaults.cardColors(
            containerColor = stateColors.panel,
        ),
    ) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = stringResource(snapshot.boundaryRes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun DoorwayStateZone(
    state: AndroidDoorwayState,
    connectionState: AndroidConnectionState,
    canConfigure: Boolean,
    canEditRelay: Boolean,
    onConfigure: () -> Unit,
    onEditRelay: () -> Unit,
) {
    val stateColors = LocalHermesStateColors.current
    val stateColor: Color
    val label: String
    val description: String

    when (state) {
        AndroidDoorwayState.NoProfile -> {
            stateColor = stateColors.identity
            label = stringResource(R.string.android_state_no_profile)
            description = stringResource(R.string.android_state_no_profile_description)
        }

        is AndroidDoorwayState.Unavailable -> {
            stateColor = stateColors.unavailable
            label = stringResource(R.string.android_state_unavailable)
            description = when (state.reason) {
                AndroidDoorwayUnavailableReason.Authorization -> stringResource(
                    R.string.android_state_unavailable_authorization,
                )

                AndroidDoorwayUnavailableReason.Connection -> when (connectionState) {
                    is AndroidConnectionState.Failed -> stringResource(
                        R.string.android_state_unavailable_connection_failed,
                        connectionState.reason,
                    )

                    is AndroidConnectionState.Reconnecting -> stringResource(
                        R.string.android_state_unavailable_connection_reconnecting,
                    )

                    else -> stringResource(R.string.android_state_unavailable_connection)
                }

                AndroidDoorwayUnavailableReason.Microphone -> stringResource(
                    R.string.android_state_unavailable_microphone,
                )

                AndroidDoorwayUnavailableReason.UnconfirmedTurn -> stringResource(
                    R.string.android_state_unavailable_unconfirmed,
                )
            }
        }

        AndroidDoorwayState.Ready -> {
            stateColor = stateColors.live
            label = stringResource(R.string.android_state_ready)
            description = stringResource(R.string.android_state_ready_description)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_doorway_state_card"),
        colors = CardDefaults.cardColors(
            containerColor = stateColors.panel,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier
                    .testTag("android_doorway_state")
                    .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                text = label,
                color = stateColor,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state is AndroidDoorwayState.Unavailable &&
                connectionState != AndroidConnectionState.Connected
            ) {
                Text(
                    modifier = Modifier
                        .testTag("android_connection_state")
                        .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                    text = stringResource(
                        R.string.android_connection_label,
                        connectionState.label(),
                    ),
                    color = stateColors.unavailable,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                modifier = Modifier
                    .testTag("android_doorway_state_description")
                    .a11yOrder(A11yOrder.STATE),
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )

            when (state) {
                AndroidDoorwayState.NoProfile -> if (canConfigure) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("android_configure_relay")
                            .a11yOrder(A11yOrder.ACTION),
                        onClick = onConfigure,
                    ) {
                        Text(stringResource(R.string.android_configure_relay))
                    }
                }

                is AndroidDoorwayState.Unavailable,
                AndroidDoorwayState.Ready,
                -> if (canEditRelay) {
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("android_edit_relay")
                            .a11yOrder(A11yOrder.ACTION),
                        onClick = onEditRelay,
                    ) {
                        Text(stringResource(R.string.android_edit_relay))
                    }
                }
            }
        }
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
    canEditPrompt: Boolean,
    isConnected: Boolean,
    composerBlock: AndroidComposerBlock?,
    isInitiating: Boolean = false,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_typed_prompt")
            .focusRequester(promptFocus)
            .a11yOrder(A11yOrder.ACTION),
        value = prompt,
        onValueChange = onPromptChange,
        enabled = canEditPrompt,
        label = { Text(stringResource(R.string.android_prompt_label)) },
    )
    if (!isConnected && prompt.isNotBlank()) {
        Text(
            modifier = Modifier
                .testTag("android_cached_draft")
                .a11yOrder(A11yOrder.ACTION),
            text = stringResource(R.string.android_cached_draft),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!promptHistory.isEmpty) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                modifier = Modifier
                    .testTag("android_prompt_history_label")
                    .a11yHeading(A11yOrder.ACTION),
                text = stringResource(R.string.android_prompt_history_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    }

    composerBlock?.let { block ->
        Text(
            modifier = Modifier
                .testTag("android_send_blocked")
                .a11yOrder(A11yOrder.ACTION, LiveRegionMode.Polite),
            text = stringResource(block.messageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Button(
        onClick = onSend,
        modifier = Modifier
            .fillMaxWidth()
            .a11yOrder(A11yOrder.ACTION),
        enabled = composerBlock == null && !isInitiating,
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
    OutlinedButton(
        onClick = onTapToSpeak,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text(stringResource(R.string.android_tap_to_speak))
    }
}

/**
 * A small activity signal, never the source of truth. The readable phase label
 * beside it remains present when system animation is reduced or disabled.
 */
@Composable
internal fun VoiceActivityIndicator(
    motionMode: AndroidMotionMode,
) {
    val stateColors = LocalHermesStateColors.current
    val fractions = if (motionMode == AndroidMotionMode.Static) {
        listOf(0.45f, 0.7f, 0.45f)
    } else {
        val transition = rememberInfiniteTransition(label = "voice activity")
        val outer by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "outer voice activity",
        )
        val centre by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "centre voice activity",
        )
        listOf(outer, centre, outer)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_voice_activity"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fractions.forEach { fraction ->
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height((8f + (24f * fraction)).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(stateColors.live),
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.ActiveCaptureZone(
    captureState: AndroidCaptureState,
    handsFree: Boolean,
    motionMode: AndroidMotionMode,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    if (handsFree) {
        Text(
            modifier = Modifier
                .testTag("android_hands_free_active")
                .a11yOrder(A11yOrder.STATE),
            text = stringResource(R.string.android_hands_free_active),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        modifier = Modifier
            .testTag("android_capture_state")
            .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
        text = stringResource(captureState.labelRes()),
        style = MaterialTheme.typography.headlineSmall,
    )
    if (captureState != AndroidCaptureState.Starting) {
        VoiceActivityIndicator(motionMode)
    }

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
                    .a11yOrder(A11yOrder.RESPONSE),
                text = partial,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("android_capture_stop")
            .a11yOrder(A11yOrder.ACTION),
        onClick = onStop,
    ) {
        Text(stringResource(R.string.android_capture_stop))
    }
    TextButton(
        modifier = Modifier
            .fillMaxWidth()
            .a11yOrder(A11yOrder.ACTION),
        onClick = onCancel,
    ) {
        Text(stringResource(R.string.android_capture_cancel))
    }
}

@Composable
internal fun ColumnScope.IdleCaptureZone(
    captureController: AndroidCaptureController,
    captureState: AndroidCaptureState,
    handsFree: Boolean,
    hasAcceptedTurn: Boolean,
    hasUnconfirmedTurn: Boolean = false,
    isAuthorized: Boolean,
    isConnected: Boolean,
    permissionRevision: Int,
    microphonePermission: ManagedActivityResultLauncher<String, Boolean>,
    showBlockMessage: Boolean = true,
) {
    val block = remember(
        isAuthorized,
        isConnected,
        permissionRevision,
        captureState,
    ) { captureController.blockingReason() }

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

    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
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
            !hasUnconfirmedTurn &&
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

    if (block == null) {
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
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

    block?.takeIf { showBlockMessage }?.let { reason ->
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
    canAttemptConnection: Boolean = isAuthorized,
    isConnected: Boolean,
    canEditRelay: Boolean,
    onRecover: () -> Unit,
    onEditRelay: () -> Unit,
    onResend: () -> Unit,
    onDiscard: () -> Unit,
) {
    val stateColors = LocalHermesStateColors.current

    if (!isConnected && canAttemptConnection) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_connect")
                .a11yOrder(A11yOrder.ACTION),
            onClick = onRecover,
            enabled = !recoveryState.isRecovering,
        ) {
            Text(stringResource(R.string.android_recover))
        }
    }

    if ((!isConnected || recoveryState.hasUnconfirmedTurn) && canEditRelay) {
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_edit_relay")
                .a11yOrder(A11yOrder.ACTION),
            onClick = onEditRelay,
        ) {
            Text(stringResource(R.string.android_edit_relay))
        }
    }

    if (recoveryState.hasUnconfirmedTurn) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = stateColors.panel,
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
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .a11yOrder(A11yOrder.ACTION),
            onClick = onResend,
        ) {
            Text(stringResource(R.string.android_resend_unconfirmed_turn))
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .a11yOrder(A11yOrder.ACTION),
            onClick = onDiscard,
        ) {
            Text(stringResource(R.string.android_discard_unconfirmed_turn))
        }
    }

    if (recoveryState.unresolvedHomeTurn && !recoveryState.hasUnconfirmedTurn) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = stateColors.panel,
            ),
        ) {
            Text(
                modifier = Modifier
                    .padding(20.dp)
                    .testTag("android_home_unresolved_turn"),
                text = stringResource(R.string.android_home_unresolved_turn),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    when (resendResult) {
        null, is AndroidResendResult.Sent, AndroidResendResult.NothingToResend -> Unit
        is AndroidResendResult.Uncertain -> Text(
            text = stringResource(R.string.android_resend_uncertain),
            color = stateColors.unavailable,
            style = MaterialTheme.typography.bodyMedium,
        )
        AndroidResendResult.NotConnected -> Text(
            text = stringResource(R.string.android_resend_not_connected),
            color = stateColors.unavailable,
            style = MaterialTheme.typography.bodyMedium,
        )

        is AndroidResendResult.Rejected -> Text(
            text = stringResource(resendResult.reason.messageRes()),
            color = stateColors.unavailable,
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
    motionMode: AndroidMotionMode,
    onInterrupt: (AndroidTurnBinding) -> Unit,
) {
    when (initiationState) {
        AndroidInitiationState.Idle -> Unit
        is AndroidInitiationState.Accepted -> {
            // "Waiting for Home events" is only true until this turn ends.
            val turnEnded = turnState.binding == initiationState.binding && turnState.isTerminal
            if (!turnEnded) {
                Text(
                    modifier = Modifier.testTag("android_turn_status"),
                    text = stringResource(
                        R.string.android_initiation_accepted,
                        snapshot.selectedProfile?.displayName
                            ?: initiationState.binding.profileId,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (hasAcceptedTurn && supportsInterrupt) {
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            if (turnState.phase == AndroidTurnPhase.Buffering ||
                turnState.phase == AndroidTurnPhase.Speaking
            ) {
                VoiceActivityIndicator(motionMode)
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
                        .a11yOrder(A11yOrder.RESPONSE),
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

            if (turnState.structuredPrompt != null) {
                Text(
                    modifier = Modifier
                        .testTag("android_structured_prompt_unavailable")
                        .a11yOrder(A11yOrder.STATE, LiveRegionMode.Assertive),
                    text = stringResource(R.string.android_structured_prompt_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (turnState.availableCommands.isNotEmpty()) {
                Text(
                    modifier = Modifier
                        .testTag("android_command_unavailable")
                        .a11yOrder(A11yOrder.STATE, LiveRegionMode.Polite),
                    text = stringResource(R.string.android_command_unavailable),
                    style = MaterialTheme.typography.bodySmall,
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

        is AndroidInitiationState.Uncertain -> {
            Text(
                text = stringResource(R.string.android_failure_delivery_uncertain),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Zone 3b — Local History
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalHistoryZone(
    recorder: AndroidHistoryRecorder,
    exporter: TranscriptExporter,
    profileDisplayName: String?,
    onClear: () -> Unit,
    sheetVisibility: Boolean? = null,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val stateColors = LocalHermesStateColors.current
    var localSheetVisible by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.android_history_share_title)
    val sheetVisible = sheetVisibility ?: localSheetVisible

    fun closeSheet() {
        menuExpanded = false
        localSheetVisible = false
        onDismiss()
    }

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

    if (sheetVisibility == null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("android_history_trigger"),
            colors = CardDefaults.cardColors(containerColor = stateColors.panel),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    modifier = Modifier
                        .testTag("android_history_label")
                        .a11yHeading(A11yOrder.RESPONSE),
                    text = stringResource(R.string.android_history_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    modifier = Modifier.a11yOrder(A11yOrder.RESPONSE),
                    text = stringResource(R.string.android_history_boundary),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    modifier = Modifier
                        .testTag("android_history_open")
                        .a11yOrder(A11yOrder.ACTION),
                    onClick = { localSheetVisible = true },
                ) {
                    Text(stringResource(R.string.android_history_open))
                }
            }
        }
    }

    if (sheetVisible) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        )
        ModalBottomSheet(
            modifier = Modifier.testTag("android_history_sheet"),
            onDismissRequest = ::closeSheet,
            sheetState = sheetState,
            containerColor = stateColors.panel,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("android_history_sheet_title")
                            .a11yHeading(A11yOrder.RESPONSE),
                        text = stringResource(R.string.android_history_label),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Box {
                        TextButton(
                            modifier = Modifier
                                .testTag("android_history_more")
                                .a11yOrder(A11yOrder.ACTION),
                            onClick = { menuExpanded = true },
                        ) {
                            Text(stringResource(R.string.android_history_more))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("android_history_share_text"),
                                text = {
                                    Text(stringResource(R.string.android_history_share_text))
                                },
                                onClick = {
                                    menuExpanded = false
                                    share(AndroidExportFormat.PlainText)
                                },
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag("android_history_share_markdown"),
                                text = {
                                    Text(stringResource(R.string.android_history_share_markdown))
                                },
                                onClick = {
                                    menuExpanded = false
                                    share(AndroidExportFormat.Markdown)
                                },
                            )
                        }
                    }
                    TextButton(
                        modifier = Modifier
                            .testTag("android_history_close")
                            .a11yOrder(A11yOrder.ACTION),
                        onClick = {
                            closeSheet()
                        },
                    ) {
                        Text(stringResource(R.string.android_history_close))
                    }
                }
                Text(
                    modifier = Modifier
                        .testTag("android_history_boundary")
                        .a11yOrder(A11yOrder.RESPONSE),
                    text = stringResource(R.string.android_history_boundary),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()

                val entries = recorder.history.entries
                if (entries.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .testTag("android_history_empty")
                            .a11yOrder(A11yOrder.RESPONSE),
                        text = stringResource(R.string.android_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                    entries.takeLast(20).forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                modifier = Modifier
                                    .testTag("android_history_entry_meta")
                                    .a11yOrder(A11yOrder.RESPONSE),
                                text = stringResource(
                                    if (entry.role == AndroidTranscriptRole.User) {
                                        R.string.android_history_you
                                    } else {
                                        R.string.android_history_hermes
                                    },
                                ) + " · " + timeFormat.format(Date(entry.createdAtMillis)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                modifier = Modifier
                                    .testTag("android_history_entry")
                                    .a11yOrder(A11yOrder.RESPONSE),
                                text = entry.text,
                                style = if (entry.role == AndroidTranscriptRole.Assistant) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                            )
                        }
                    }
                    TextButton(
                        modifier = Modifier
                            .testTag("android_history_clear")
                            .a11yOrder(A11yOrder.ACTION),
                        onClick = onClear,
                    ) {
                        Text(stringResource(R.string.android_history_clear))
                    }
                }
            }
        }
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
    AndroidInitiationFailure.HomeBindingUnavailable -> R.string.android_failure_home_binding_unavailable
    AndroidInitiationFailure.RequestRejected -> R.string.android_failure_request_rejected
    AndroidInitiationFailure.DeliveryUncertain -> R.string.android_failure_delivery_uncertain
}

internal fun AndroidComposerBlock.messageRes(): Int = when (this) {
    AndroidComposerBlock.NoProfile -> R.string.android_composer_block_no_profile
    AndroidComposerBlock.Authorization -> R.string.android_composer_block_authorization
    AndroidComposerBlock.Disconnected -> R.string.android_composer_block_disconnected
    AndroidComposerBlock.ActiveTurn -> R.string.android_composer_block_active_turn
    AndroidComposerBlock.UnconfirmedTurn -> R.string.android_composer_block_unconfirmed_turn
    AndroidComposerBlock.EmptyPrompt -> R.string.android_composer_block_empty_prompt
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
