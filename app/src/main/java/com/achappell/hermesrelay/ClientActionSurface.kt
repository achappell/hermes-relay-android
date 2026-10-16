package com.achappell.hermesrelay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.HermesSpacing
import com.achappell.hermesrelay.ui.theme.LocalHermesStateColors

@Composable
internal fun ClientActionSurface(
    runtime: HomeRuntime,
    prompt: String,
    updatePrompt: (String) -> Unit,
    promptFocus: FocusRequester,
    promptHistory: AndroidPromptHistory,
    canEditPrompt: Boolean,
    isConnected: Boolean,
    composerBlock: AndroidComposerBlock?,
    initiationInFlight: Boolean,
    captureController: AndroidCaptureController?,
    isAuthorized: Boolean,
    hasAcceptedTurn: Boolean,
    hasUnresolvedTurn: Boolean,
    captureState: AndroidCaptureState,
    handsFree: Boolean,
    motionMode: AndroidMotionMode,
    availabilityBlock: AndroidAvailabilityBlock?,
    permissionRevision: Int,
    microphone: RuntimePermissionController,
    doorwayState: AndroidDoorwayState?,
) {
    val stateColors = LocalHermesStateColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("android_action_surface"),
        color = stateColors.consoleSurface,
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    .widthIn(max = 720.dp).heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = HermesSpacing.lg, vertical = HermesSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HermesSpacing.sm),
            ) {
                TypedComposerZone(
                    prompt = prompt,
                    onPromptChange = updatePrompt,
                    promptFocus = promptFocus,
                    promptHistory = promptHistory,
                    onPromptHistoryChange = { runtime.promptHistory = it },
                    canEditPrompt = canEditPrompt,
                    isConnected = isConnected,
                    composerBlock = composerBlock,
                    isInitiating = initiationInFlight,
                    onSend = { runtime.initiate(AndroidTurnInput.Typed(prompt)) },
                )
                if (captureController == null) {
                    TapToSpeakFallback(
                        enabled = isAuthorized && isConnected && !hasAcceptedTurn &&
                            !hasUnresolvedTurn && !initiationInFlight,
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
                        availabilityBlock = availabilityBlock,
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
}
