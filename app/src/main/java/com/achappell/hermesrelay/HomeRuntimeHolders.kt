package com.achappell.hermesrelay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TurnHolderState(
    val initiationState: AndroidInitiationState = AndroidInitiationState.Idle,
    val turnState: AndroidTurnState = AndroidTurnState(),
    val lastRequest: AndroidTurnRequest? = null,
    val initiationInFlight: Boolean = false,
    val promptHistory: AndroidPromptHistory = AndroidPromptHistory(),
    val historyRevision: Int = 0,
    val settleRevision: Int = 0,
    val composerClearRevision: Int = 0,
    val outputPaused: Boolean = false,
) {
    /** Shared runtime/UI projection; active transport work remains authoritative after terminal text. */
    fun hasAcceptedTurn(clientPort: AndroidClientPort): Boolean =
        initiationState is AndroidInitiationState.Accepted && (!turnState.isTerminal || clientPort.hasActiveTurn())
}

/** Main-serialized presentation state; the runtime retains transport and timer ownership. */
internal class TurnHolder {
    private val mutableState = MutableStateFlow(TurnHolderState())
    val state: StateFlow<TurnHolderState> = mutableState.asStateFlow()
    private var settledHandled = false

    internal fun update(change: (TurnHolderState) -> TurnHolderState) {
        mutableState.value = change(mutableState.value)
    }

    fun present(turn: AndroidTurnState) {
        update { it.copy(turnState = turn) }
        if (!turn.isTerminal) settledHandled = false
    }

    fun onEvent(event: AndroidNormalizedEvent) {
        present(AndroidTurnStateReducer.reduce(state.value.turnState, event))
    }

    /** Claims settlement before external effects, preventing duplicate history and capture work. */
    fun claimSettlement(): Boolean {
        val current = state.value
        if (!current.turnState.isTerminal || current.initiationState !is AndroidInitiationState.Accepted || settledHandled) return false
        settledHandled = true
        return true
    }

    fun recordAcceptedPrompt(text: String, clearTypedPrompt: Boolean) {
        update {
            it.copy(
                promptHistory = it.promptHistory.record(text),
                composerClearRevision = it.composerClearRevision + if (clearTypedPrompt) 1 else 0,
                historyRevision = it.historyRevision + 1,
            )
        }
    }
}

internal data class CaptureHolderState(
    val captureState: AndroidCaptureState = AndroidCaptureState.Idle,
    val handsFree: Boolean = false,
    val interruptStatus: InterruptStatus = InterruptStatus.None,
)

internal class CaptureHolder {
    private val mutableState = MutableStateFlow(CaptureHolderState())
    val state: StateFlow<CaptureHolderState> = mutableState.asStateFlow()

    internal fun update(change: (CaptureHolderState) -> CaptureHolderState) {
        mutableState.value = change(mutableState.value)
    }

    /** Returns whether a reopened hands-free window replaces a settled turn's display. */
    fun onCaptureChanged(capture: AndroidCaptureState, turn: AndroidTurnState): Boolean {
        update { it.copy(captureState = capture) }
        val capturing = capture == AndroidCaptureState.Starting ||
            capture == AndroidCaptureState.Listening || capture is AndroidCaptureState.Transcribing
        return state.value.handsFree && capturing && turn.isTerminal
    }
}

internal data class RecoveryHolderState(
    val recoveryState: AndroidRecoveryState = AndroidRecoveryState(),
    val resendResult: AndroidResendResult? = null,
    val resendInFlight: Boolean = false,
    val userDisconnected: Boolean = false,
) {
    val canDisconnect: Boolean
        get() = (recoveryState.connection == AndroidConnectionState.Connected || recoveryState.isRecovering) && !userDisconnected
}

internal class RecoveryHolder {
    private val mutableState = MutableStateFlow(RecoveryHolderState())
    val state: StateFlow<RecoveryHolderState> = mutableState.asStateFlow()

    internal fun update(change: (RecoveryHolderState) -> RecoveryHolderState) {
        mutableState.value = change(mutableState.value)
    }

    /** A second explicit resend cannot enqueue another send while the first is pending. */
    fun beginResend(): Boolean {
        if (state.value.resendInFlight) return false
        update { it.copy(resendInFlight = true) }
        return true
    }

    fun completeResend(result: AndroidResendResult) {
        update { it.copy(resendInFlight = false, resendResult = result) }
    }
}
