package com.achappell.hermesrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ExecutorService

/**
 * The one Home runtime of a process (`ANDROID-HOME-07`).
 *
 * It owns everything that must outlive an Activity: the Home client and its
 * audio sink, the recovery and capture controllers, the worker executor, and
 * the state the user sees mid-conversation (turn, recovery, capture, the
 * unconfirmed prompt and hands-free). The Activity and Compose layer observe
 * it and send intents; they never construct or close it, so rotation, a font
 * scale or theme change, or a window resize recreate the screen without
 * closing the socket, stopping audio or discarding the unconfirmed turn.
 *
 * Nothing here may hold an `Activity` or an Activity `Context`: the runtime
 * lives as long as the process.
 *
 * Threading: state is written on the main thread through [postToMain]; blocking
 * work runs on [workExecutor].
 */
internal class HomeRuntime(
    val clientPort: AndroidClientPort,
    speechInput: AndroidSpeechInput?,
    historyStore: AndroidHistoryStore?,
    private val postToMain: (Runnable) -> Unit,
    private val workExecutor: ExecutorService,
    private val onTornDown: () -> Unit = {},
    /** Content-free connection journal (`ANDROID-DIAG-01`); never receives prompts or replies. */
    val journal: DiagnosticsJournal = DiagnosticsJournal.None,
) {
    val homeConversations: AndroidHomeConversations? = clientPort as? AndroidHomeConversations
    val initiationController = AndroidInitiationController(clientPort)
    val recorder: AndroidHistoryRecorder? = historyStore?.let { AndroidHistoryRecorder(it) }

    var recoveryState by mutableStateOf(AndroidRecoveryState())
        private set
    var initiationState by mutableStateOf<AndroidInitiationState>(AndroidInitiationState.Idle)
        private set
    var turnState by mutableStateOf(AndroidTurnState())
        private set
    var lastRequest by mutableStateOf<AndroidTurnRequest?>(null)
        private set
    var resendResult by mutableStateOf<AndroidResendResult?>(null)
        private set
    var captureState by mutableStateOf<AndroidCaptureState>(AndroidCaptureState.Idle)
        private set
    var handsFree by mutableStateOf(false)
        private set
    var initiationInFlight by mutableStateOf(false)
        private set
    var resendInFlight by mutableStateOf(false)
        private set
    var promptHistory by mutableStateOf(AndroidPromptHistory())

    /** Bumped when Local History changes outside Compose; the sheet re-reads on change. */
    var historyRevision by mutableIntStateOf(0)

    /** Bumped each time a turn settles, so the screen can restore composer focus once. */
    var settleRevision by mutableIntStateOf(0)
        private set

    /** Bumped when a typed prompt is accepted, so the screen can empty the composer once. */
    var composerClearRevision by mutableIntStateOf(0)
        private set

    /**
     * True from a deliberate Disconnect until the user chooses Connect
     * (`ANDROID-HOME-12`). Every automatic path (foreground reconnect, resume,
     * Profile selection) is a no-op meanwhile.
     */
    var userDisconnected by mutableStateOf(false)
        private set

    /**
     * Set by the visible screen: reconnects a paired Profile whose socket
     * Android cut, only while the screen is resumed. No screen, no automatic
     * reconnect (`ANDROID-HOME-04/06` own the background rules).
     */
    @Volatile
    var reconnectIfForeground: () -> Unit = {}

    val recoveryController = AndroidRecoveryController(clientPort) { changed ->
        postToMain {
            recoveryState = changed
            changed.resumedTurnBinding?.let { binding ->
                updateInitiation(AndroidInitiationState.Accepted(binding))
                updateTurn(AndroidTurnState.awaitingEvents(binding))
            }
        }
    }

    val captureController: AndroidCaptureController? = speechInput?.let { input ->
        AndroidCaptureController(
            speech = input,
            initiation = initiationController,
            isConnected = { recoveryState.connection == AndroidConnectionState.Connected },
            isAuthorized = {
                val current = clientPort.snapshot()
                current.selectedProfile != null &&
                    current.authorizationState == AndroidAuthorizationState.Verified
            },
            currentSessionId = { recoveryController.state.connectionId },
            onStateChange = { changed ->
                postToMain {
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
                    if (handsFree && capturing && turnState.isTerminal) {
                        updateTurn(AndroidTurnState(phase = AndroidTurnPhase.Listening))
                    }
                }
            },
            onHandsFreeChange = { armed -> postToMain { handsFree = armed } },
            onInitiation = { result ->
                postToMain {
                    applyInitiationState(state = result, recordAcceptedInput = true)
                }
            },
        )
    }

    private val acceptedBinding: AndroidTurnBinding?
        get() = (initiationState as? AndroidInitiationState.Accepted)?.binding

    /** True while Home still owns an accepted turn the user is waiting on. */
    val hasAcceptedTurn: Boolean
        get() = acceptedBinding != null && (!turnState.isTerminal || clientPort.hasActiveTurn())

    private var observedBinding: AndroidTurnBinding? = null
    private var turnObservation: AndroidTurnObservation? = null
    private var settledHandled = false
    private var attachedActivities = 0
    private var teardownWhenSettled = false
    private var tornDown = false

    private val connectionObservation: AndroidTurnObservation =
        clientPort.observeConnection { event ->
            postToMain {
                recoveryController.transportLost(
                    reason = event.reason,
                    inFlightTurn = lastRequest?.let { request ->
                        AndroidUnconfirmedTurn(acceptedBinding, request)
                    },
                )
                reconnectIfForeground()
            }
        }

    /** Runs blocking work off the main thread. */
    fun runOnWork(block: () -> Unit) {
        workExecutor.execute(block)
    }

    /** Runs [block] on the main thread. */
    fun runOnMain(block: () -> Unit) {
        postToMain(Runnable(block))
    }

    private fun updateInitiation(state: AndroidInitiationState) {
        initiationState = state
        val binding = acceptedBinding
        if (binding == observedBinding) return
        turnObservation?.cancel()
        observedBinding = binding
        turnObservation = binding?.let {
            clientPort.observeTurn(it) { event -> postToMain { onTurnEvent(event) } }
        }
    }

    private fun updateTurn(state: AndroidTurnState) {
        turnState = state
        if (!state.isTerminal) settledHandled = false
    }

    private fun onTurnEvent(event: AndroidNormalizedEvent) {
        updateTurn(AndroidTurnStateReducer.reduce(turnState, event))
        if (
            event is AndroidNormalizedEvent.TurnCompleted ||
            event is AndroidNormalizedEvent.TurnFailed ||
            event is AndroidNormalizedEvent.TurnInterrupted
        ) {
            recoveryState = recoveryController.resolveHomeTurn()
        }
        // A new conversation only has a Home reference once a turn was
        // accepted; learn it so the next launch can continue it.
        if (event is AndroidNormalizedEvent.TurnCompleted) {
            homeConversations?.let { conversations ->
                runOnWork { conversations.learnCurrentConversation() }
            }
        }
        settleIfDue()
        tearDownIfDue()
    }

    /** Runs once per settled turn, however many screens come and go meanwhile. */
    private fun settleIfDue() {
        if (!turnState.isTerminal || initiationState !is AndroidInitiationState.Accepted) return
        if (settledHandled) return
        settledHandled = true
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
        settleRevision += 1
    }

    fun applyInitiationState(
        state: AndroidInitiationState,
        recordAcceptedInput: Boolean,
        clearTypedPrompt: Boolean = false,
    ) {
        updateInitiation(state)
        when (state) {
            is AndroidInitiationState.Accepted -> {
                resendResult = null
                updateTurn(AndroidTurnState.awaitingEvents(state.binding))
                if (recordAcceptedInput) {
                    (lastRequest?.input as? AndroidTurnInput.Typed)?.let { typed ->
                        recorder?.recordDraft("")
                        recorder?.recordUserTurn(typed.text)
                        promptHistory = promptHistory.record(typed.text)
                        // The composer empties once the turn is accepted; a sent
                        // prompt lingering in the box reads as unsent.
                        if (clearTypedPrompt) composerClearRevision += 1
                        historyRevision += 1
                    }
                }
            }

            is AndroidInitiationState.Uncertain -> {
                lastRequest = state.request
                updateTurn(AndroidTurnState())
                recoveryController.transportLost(
                    reason = state.reason.name,
                    inFlightTurn = AndroidUnconfirmedTurn(binding = null, request = state.request),
                )
            }

            is AndroidInitiationState.Rejected -> lastRequest = null

            else -> Unit
        }
    }

    fun initiate(input: AndroidTurnInput) {
        if (initiationInFlight) return
        initiationInFlight = true
        clientPort.snapshot().selectedProfile?.let { lastRequest = AndroidTurnRequest(it, input) }
        runOnWork {
            val state = initiationController.initiate(input)
            runOnMain {
                initiationInFlight = false
                applyInitiationState(
                    state = state,
                    recordAcceptedInput = true,
                    clearTypedPrompt = input is AndroidTurnInput.Typed,
                )
            }
        }
    }

    /** Starts the bounded reconnect ladder off the main thread. */
    fun recover() {
        if (userDisconnected || recoveryState.isRecovering) return
        runOnWork { recoveryController.recover() }
    }

    /** The user's explicit Connect: the only way out of a deliberate Disconnect. */
    fun connect() {
        userDisconnected = false
        recover()
    }

    /** Disconnect is offered only while connected, and not while a prompt is being sent. */
    val canDisconnect: Boolean
        get() = recoveryState.connection == AndroidConnectionState.Connected && !userDisconnected

    /** Disabled while a prompt submission (or a resend) is in flight. */
    val disconnectEnabled: Boolean
        get() = canDisconnect && !initiationInFlight && !resendInFlight

    /** A reply the user is waiting on needs a confirmation before it is cut off. */
    val disconnectNeedsConfirmation: Boolean
        get() = hasAcceptedTurn

    /**
     * Ends the session deliberately: interrupts a reply in flight, sends
     * `conversation.close`, closes the socket, stops audio and capture and
     * clears hands-free. Never reconnects until [connect]. An unconfirmed turn
     * stays offered; nothing is replayed.
     */
    fun disconnect() {
        if (!disconnectEnabled) return
        val interrupting = if (hasAcceptedTurn) acceptedBinding else null
        // Set first and on the main thread so no automatic path can race the close.
        userDisconnected = true
        captureController?.let { capture ->
            capture.disarmHandsFree()
            capture.cancelCapture()
        }
        runOnWork {
            interrupting?.let { clientPort.interruptTurn(it) }
            clientPort.endSession()
            runOnMain {
                updateInitiation(AndroidInitiationState.Idle)
                updateTurn(AndroidTurnState())
                recoveryState = recoveryController.disconnectDeliberately()
                // The unconfirmed turn (if any) lives in the recovery state.
                lastRequest = null
            }
        }
    }

    /** Abandons the current turn display and reconnects into a requested conversation. */
    fun switchConversation(intent: HomeConversationIntent) {
        val conversations = homeConversations ?: return
        if (userDisconnected) return
        updateInitiation(AndroidInitiationState.Idle)
        updateTurn(AndroidTurnState())
        runOnWork {
            conversations.requestConversation(intent)
            recoveryController.recover()
        }
    }

    fun resendUnconfirmedTurn() {
        if (resendInFlight) return
        resendInFlight = true
        runOnWork {
            val result = recoveryController.resendUnconfirmedTurn()
            runOnMain {
                resendInFlight = false
                resendResult = result
                if (result is AndroidResendResult.Sent) {
                    lastRequest = recoveryController.state.unconfirmedTurn?.request ?: lastRequest
                    updateInitiation(AndroidInitiationState.Accepted(result.binding))
                    updateTurn(AndroidTurnState.awaitingEvents(result.binding))
                }
            }
        }
    }

    fun discardUnconfirmedTurn() {
        recoveryController.discardUnconfirmedTurn()
        lastRequest = null
        updateInitiation(AndroidInitiationState.Idle)
        updateTurn(AndroidTurnState())
        resendResult = null
    }

    /** A screen attached. A pending teardown is cancelled: someone is looking again. */
    fun activityCreated() {
        attachedActivities += 1
        teardownWhenSettled = false
        journal.record("app activity created attached=$attachedActivities")
    }

    /**
     * A screen went away.
     *
     * Only a finishing Activity that is not being recreated can end the
     * runtime. A recreation (rotation, font scale, theme, window resize) and a
     * system destroy of a backgrounded Activity both keep it, so the socket,
     * audio and the unconfirmed turn survive. A finishing Activity with a reply
     * in flight leaves the runtime running; it tears down when that reply
     * settles.
     */
    fun activityDestroyed(isFinishing: Boolean, isChangingConfigurations: Boolean) {
        attachedActivities = (attachedActivities - 1).coerceAtLeast(0)
        journal.record(
            "app activity destroyed finishing=$isFinishing config_change=$isChangingConfigurations " +
                "remaining=$attachedActivities",
        )
        // Another Activity instance is still showing this runtime.
        if (attachedActivities > 0) return
        if (isChangingConfigurations || !isFinishing) return
        if (hasAcceptedTurn) {
            teardownWhenSettled = true
            journal.record("runtime teardown deferred reply=inFlight")
        } else {
            tearDown("activityFinished")
        }
    }

    private fun tearDownIfDue() {
        if (teardownWhenSettled && attachedActivities == 0 && turnState.isTerminal) {
            tearDown("replySettled")
        }
    }

    private fun tearDown(reason: String) {
        if (tornDown) return
        tornDown = true
        journal.record("runtime teardown reason=$reason")
        connectionObservation.cancel()
        turnObservation?.cancel()
        turnObservation = null
        captureController?.let { capture ->
            capture.disarmHandsFree()
            capture.cancelCapture()
        }
        workExecutor.shutdownNow()
        clientPort.close()
        onTornDown()
    }
}

/**
 * Resolves the process's single [HomeRuntime] (the Android analogue of iOS
 * `ContentViewRuntimeBox`).
 *
 * Every Activity calls [resolve]; the factory runs once until the runtime
 * tears itself down, so a second Activity instance can never build a second
 * client, sink or lifecycle owner. [createdCount] is the content-free number
 * the `runtime created` journal line of `ANDROID-DIAG-01` will report: more
 * than one per process, without an intervening teardown, is a regression.
 */
internal class HomeRuntimeBox(
    private val journal: DiagnosticsJournal = DiagnosticsJournal.None,
    private val create: (onTornDown: () -> Unit) -> HomeRuntime,
) {
    private var runtime: HomeRuntime? = null

    var createdCount: Int = 0
        private set

    @Synchronized
    fun resolve(): HomeRuntime = runtime ?: create(::release).also {
        runtime = it
        createdCount += 1
        // Exactly one per process, across rotations. A second line without a
        // `runtime teardown` between is a regression.
        journal.record("runtime created")
    }

    @Synchronized
    private fun release() {
        runtime = null
    }
}
