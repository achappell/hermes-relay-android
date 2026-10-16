package com.achappell.hermesrelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

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
    private val voiceTimers: VoiceTimers = MainLooperVoiceTimers,
    reconnectPolicy: ReconnectPolicy = ReconnectPolicy.default,
    recoveryPacer: RecoveryPacer = LockRecoveryPacer(),
    private val clock: MonotonicClock = MonotonicClock.Real,
    /** Default-network events: a returning network skips the rest of a backoff wait. */
    networkEvents: NetworkEvents = NetworkEvents.None,
    /** Keeps a reply alive after the app stops (`ANDROID-HOME-08`). */
    private val foregroundService: ForegroundServiceController = ForegroundServiceController.None,
    private val audioFocus: AudioFocusController = AudioFocusController.None,
    private val audioRoutes: AudioRouteEvents = AudioRouteEvents.None,
    private val retentionTimers: VoiceTimers = NoVoiceTimers,
    private val retentionTimings: RetentionTimings = RetentionTimings.Default,
    leadScheduler: java.util.concurrent.ScheduledExecutorService? = null,
    private val mediaSessionWrites: MediaSessionWrites = MediaSessionWrites(),
) {
    private val leadSampler = AudioLeadSampler(
        clientPort::playbackLead, journal, clock, leadScheduler, clientPort::playbackLeadStream,
    )

    val homeConversations: AndroidHomeConversations?
        get() = clientPort.homeConversations()
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
    var interruptStatus by mutableStateOf(InterruptStatus.None)
        private set
    var initiationInFlight by mutableStateOf(false)
        private set
    var resendInFlight by mutableStateOf(false)
        private set
    var promptHistory by mutableStateOf(AndroidPromptHistory())
    private var historyIdentity: String? = null

    fun openHistory(identity: String?) {
        if (historyIdentity == identity) return
        historyIdentity = identity
        recorder?.open(identity)
        promptHistory = AndroidPromptHistory()
    }

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

    /** Orders foreground and background inputs; only the newest one wins (`ANDROID-HOME-04`). */
    val lifecycle = HomeLifecycleCoordinator()

    /** What the last [syncRetention] decided; the foreground service follows it. */
    private var appliedRetention = BackgroundRetention.None
    private val pauseReasons = mutableSetOf<PauseReason>()
    private var pausedHold: VoiceTimer? = null
    private var pausedDeadline: Long? = null
    private var pausedGeneration = 0L
    private var retentionWatchdog: VoiceTimer? = null
    private var lastServiceState: VoiceServiceState? = null
    private var focusAsked = false
    private var focusGeneration = 0L
    private var routeGeneration = 0L
    private var observingRoutes = false
    private var selectedRoutes = emptyList<OutputRoute>()
    private var routeAwaitingDeparture = emptyList<OutputRoute>()
    private var awaitingRouteDeparture = false
    private var outputDucked = false
    private var inBackgroundRetention = false
    private var voiceIdle: VoiceTimer? = null
    private var voiceIdleDeadline: Long? = null
    private var idleGeneration = 0L
    private var tailTimer: VoiceTimer? = null
    private var tailDeadline: Long? = null
    private var outputWasActive = false
    private var syncingRetention = false
    private var endingVoice = false

    private val wakeSuppressed: Boolean
        get() = initiationInFlight || resendInFlight || hasAcceptedTurn ||
            tailDeadline?.let { clock.nanoTime() < it } == true

    /** The turn a stop was applied to; it never re-enters retention, whatever events follow. */
    private var stoppedBinding: AndroidTurnBinding? = null

    /** True while the user or a focus loss holds the reply's output. */
    var outputPaused by mutableStateOf(false)
        private set

    val recoveryController = AndroidRecoveryController(
        clientPort = clientPort,
        policy = reconnectPolicy,
        clock = clock,
        pacer = recoveryPacer,
        journal = journal,
    ) { changed ->
        postToMain {
            recoveryState = changed
            changed.resumedTurnBinding?.let { binding ->
                updateInitiation(AndroidInitiationState.Accepted(binding))
                updateTurn(AndroidTurnState.awaitingEvents(binding))
            }
            syncRetention()
        }
    }

    val captureController: AndroidCaptureController? = speechInput?.let { input ->
        AndroidCaptureController(
            speech = input,
            timers = voiceTimers,
            initiation = initiationController,
            isForeground = { lifecycle.isForeground && !tornDown && !endingVoice },
            wakeSuppressed = { wakeSuppressed },
            prepareMicrophone = { ready ->
                syncRetention()
                foregroundService.awaitMicrophone { allowed ->
                    postToMain { ready(allowed && lastServiceState?.microphone == true) }
                }
            },
            onVoiceActivity = { postToMain { refreshVoiceIdle() } },
            isConnected = {
                recoveryState.connection == AndroidConnectionState.Connected &&
                    (clientPort.connectionMode() != RelayConnectionMode.Standard ||
                        (!clientPort.hasUncertainTurn() &&
                            !recoveryState.hasUnconfirmedTurn &&
                            !recoveryState.unresolvedHomeTurn &&
                            !clientPort.hasActiveTurn()))
            },
            isAuthorized = {
                val current = clientPort.snapshot()
                current.selectedProfile != null &&
                    current.authorizationState == AndroidAuthorizationState.Verified
            },
            currentSessionId = { recoveryController.state.connectionId },
            onStateChange = { changed ->
                postToMain {
                    captureState = changed
                    if (changed == AndroidCaptureState.Starting) {
                        journal.record("voice capture started")
                    }
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
                        updateInitiation(AndroidInitiationState.Idle)
                        updateTurn(AndroidTurnState(phase = AndroidTurnPhase.Listening))
                    }
                    syncRetention()
                }
            },
            onHandsFreeChange = { armed ->
                postToMain {
                    handsFree = armed
                    journal.record("voice hands_free armed=$armed")
                    syncRetention()
                }
            },
            onInitiation = { result ->
                postToMain {
                    applyInitiationState(state = result, recordAcceptedInput = true)
                }
            },
        )
    }

    private val interruptCoordinator = TurnInterruptCoordinator(
        timers = voiceTimers,
        send = { binding -> clientPort.interruptTurn(binding) },
        beginCapture = { captureController?.beginCapture() },
        onStatusChange = { interruptStatus = it },
    )

    /**
     * Interrupts and listens when hands-free is off. With hands-free armed,
     * the capture controller retains its existing post-turn policy.
     */
    fun interruptAndListen(binding: AndroidTurnBinding) {
        interruptCoordinator.interrupt(binding, thenListen = !handsFree)
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

    private val networkObservation: AndroidTurnObservation =
        networkEvents.observe(
            object : NetworkListener {
                override fun onAvailable() {
                    postToMain { networkAvailable() }
                }
            },
        )

    /**
     * The default network (re)appeared. A ladder that is waiting out a backoff
     * step attempts at once; otherwise a disconnected, foreground Profile
     * reconnects. Both are idempotent, so the replay Android delivers at
     * registration and repeated flaps start no second recovery.
     */
    private fun networkAvailable() {
        if (tornDown || userDisconnected) return
        if (recoveryController.isLadderLive) {
            recoveryController.retryNow()
        } else if (recoveryState.connection != AndroidConnectionState.Connected) {
            reconnectIfForeground()
        }
    }

    /**
     * The app is in the foreground (Activity resumed). Any reconnect it queues
     * is dropped if a newer input arrives before it runs.
     */
    fun appStarted() {
        lifecycle.enterStarted()
        foregroundEntered()
    }

    fun appForegrounded() {
        lifecycle.enterForeground()
        foregroundEntered()
    }

    /**
     * The app stopped. A recreation (rotation, font scale) is not a background
     * and changes nothing. Otherwise a running reconnect ladder is cancelled
     * and returns the state to Disconnected, and reconnects queued by an older
     * input are dropped.
     */
    fun appBackgrounded(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        if (lifecycle.isForeground) mediaSessionWrites.backgroundEntered()
        val stopped = lifecycle.enterBackground()
        recoveryController.cancelRecovery()
        // A reply in flight stays alive under the foreground service until its
        // audio has drained (`ANDROID-HOME-08`).
        if (appliedRetention != BackgroundRetention.None) {
            inBackgroundRetention = true
            val kind = if (appliedRetention == BackgroundRetention.Reply) "reply" else "voiceSession"
            journal.record("voice background enter retention=$kind")
            if (appliedRetention == BackgroundRetention.Reply) {
                leadSampler.backgroundEntered(clientPort.playbackLeadStream())
            }
            pushServiceState()
            refreshPausedHold()
            return
        }
        // Otherwise a submit or capture in flight keeps the socket, and an idle
        // app has its transport closed without ending the claim, so Home parks
        // it for the next start.
        val idle = !userDisconnected && !hasAcceptedTurn && !initiationInFlight && !resendInFlight &&
            captureController?.isCapturing != true
        if (!idle) return
        runOnWork {
            // A newer start superseded this stop: its socket is not ours to close.
            if (!lifecycle.isCurrent(stopped)) return@runOnWork
            journal.record("lifecycle deactivate trigger=stopWithoutRetention reply=none")
            clientPort.closeForLifecycle()
            // On the worker, before any queued reconnect looks at the state.
            recoveryController.disconnectForLifecycle()
        }
    }

    private fun foregroundEntered() {
        mediaSessionWrites.foregroundEntered()
        leadSampler.stop()
        if (inBackgroundRetention) {
            inBackgroundRetention = false
            journal.record("voice background exit")
        }
        // Returning resumes a reply the lock screen paused; a focus hold stays.
        releaseOutput(PauseReason.User)
        pushServiceState()
        refreshPausedHold()
    }

    /**
     * What a stop has to keep alive right now. A reply is retained from the
     * submit until its audio has drained or the turn ended; a lost transport,
     * a user Disconnect or a settled turn end it.
     */
    val retention: BackgroundRetention
        get() = when {
            tornDown || userDisconnected -> BackgroundRetention.None
            recoveryState.connection != AndroidConnectionState.Connected -> BackgroundRetention.None
            initiationInFlight || resendInFlight -> BackgroundRetention.Reply
            hasAcceptedTurn && acceptedBinding != stoppedBinding -> BackgroundRetention.Reply
            !endingVoice && (captureController?.isHandsFree == true ||
                captureController?.isCapturing == true) -> BackgroundRetention.VoiceSession
            else -> BackgroundRetention.None
        }

    /** Applies a change of [retention]; safe to call after any input that can change it. */
    private fun syncRetention() {
        if (tornDown || syncingRetention || endingVoice) return
        syncingRetention = true
        try {
            syncWakeSuppression()
            val now = retention
            val changed = now != appliedRetention
            appliedRetention = now
            syncRoutes(now == BackgroundRetention.Reply)
            if (now != BackgroundRetention.Reply) leadSampler.stop()
            if (now != BackgroundRetention.None) {
                if (retentionWatchdog == null) scheduleRetentionWatchdog()
                pushServiceState()
                if (now == BackgroundRetention.VoiceSession) syncFocus()
            } else if (changed) {
                captureController?.disarmHandsFree(AndroidHandsFreeExit.SessionEnded)
                captureController?.cancelCapture()
                leaveRetention(RetentionEnd.WorkFinished, interrupting = null)
            }
            refreshVoiceIdle()
        } finally {
            syncingRetention = false
        }
    }

    /** The terminal event includes the sink drain; all wake paths share the same tail. */
    private fun syncWakeSuppression() {
        val active = initiationInFlight || resendInFlight || hasAcceptedTurn
        if (active) {
            tailTimer?.cancel()
            tailTimer = null
            tailDeadline = null
            outputWasActive = true
            captureController?.suppressWake()
        } else if (outputWasActive) {
            outputWasActive = false
            tailDeadline = clock.nanoTime() + retentionTimings.outputTailMillis * 1_000_000
            scheduleTail()
        }
    }

    private fun scheduleTail() {
        val deadline = tailDeadline ?: return
        val remaining = deadline - clock.nanoTime()
        if (remaining <= 0) {
            tailDeadline = null
            tailTimer = null
            captureController?.continueHandsFree()
            return
        }
        tailTimer = retentionTimers.after((remaining + 999_999) / 1_000_000) {
            if (tailDeadline == deadline) scheduleTail()
        }
    }

    /** Listening for a wake is idle; an actual speech activity/partial is not. */
    private val voiceIdleWanted: Boolean
        get() = !endingVoice && captureController?.isHandsFree == true &&
            retention == BackgroundRetention.VoiceSession &&
            captureController.hasVoiceActivity.not() && !clientPort.hasActiveTurn()

    private fun refreshVoiceIdle() {
        if (!voiceIdleWanted) {
            voiceIdle?.cancel()
            voiceIdle = null
            voiceIdleDeadline = null
            idleGeneration++
        } else if (voiceIdleDeadline == null) {
            voiceIdleDeadline = clock.nanoTime() + retentionTimings.voiceIdleMillis * 1_000_000
            scheduleVoiceIdle(++idleGeneration)
        }
    }

    private fun scheduleVoiceIdle(generation: Long) {
        // Speech updates the controller before its queued runtime notification.
        // Recheck the source of truth, not just the timer's generation.
        if (!voiceIdleWanted) {
            refreshVoiceIdle()
            return
        }
        val deadline = voiceIdleDeadline ?: return
        val remaining = deadline - clock.nanoTime()
        if (remaining <= 0) {
            journal.record("voice idle expired")
            stopReply(RetentionEnd.IdleTimeout)
            return
        }
        voiceIdle = retentionTimers.after((remaining + 999_999) / 1_000_000) {
            if (generation == idleGeneration && voiceIdleDeadline == deadline) scheduleVoiceIdle(generation)
        }
    }

    /**
     * Retained work is over. Releases what it held; with the app stopped it
     * also runs the `ANDROID-HOME-06` close, so the claim is parked once.
     */
    private fun leaveRetention(reason: RetentionEnd, interrupting: AndroidTurnBinding?) {
        leadSampler.stop()
        releaseRetentionResources()
        if (lifecycle.isForeground) {
            interrupting?.let { binding -> runOnWork { clientPort.interruptTurn(binding) } }
            return
        }
        val stopped = lifecycle.generation
        inBackgroundRetention = false
        journal.record("voice background retention ended reason=${reason.journal}")
        runOnWork {
            interrupting?.let { clientPort.interruptTurn(it) }
            // A newer start superseded this stop: its socket is not ours to close.
            if (!lifecycle.isCurrent(stopped)) return@runOnWork
            journal.record("lifecycle deactivate trigger=${reason.trigger} reply=none")
            if (interrupting != null) clientPort.closeAfterInterrupt() else clientPort.closeForLifecycle()
            recoveryController.disconnectForLifecycle()
            if (interrupting != null) {
                runOnMain {
                    updateInitiation(AndroidInitiationState.Idle)
                    updateTurn(AndroidTurnState())
                    // The stopped prompt must not be offered as an unconfirmed turn later.
                    lastRequest = null
                    tearDownIfDue()
                }
            }
        }
    }

    private fun releaseRetentionResources() {
        retentionWatchdog?.cancel()
        retentionWatchdog = null
        pausedHold?.cancel()
        pausedHold = null
        pausedDeadline = null
        pausedGeneration++
        voiceIdle?.cancel()
        voiceIdle = null
        voiceIdleDeadline = null
        idleGeneration++
        pauseReasons.clear()
        syncRoutes(false)
        outputPaused = false
        abandonFocus()
        pushServiceState()
    }

    /**
     * Ends a retained reply at once (notification Stop, a permanent focus loss,
     * a paused hold that ran out, task removal): Home is told to stop, and with
     * the app stopped the transport is parked. Idempotent.
     */
    private fun stopReply(reason: RetentionEnd) {
        if (appliedRetention == BackgroundRetention.None) return
        endingVoice = true
        captureController?.disarmHandsFree()
        captureController?.cancelCapture()
        val interrupting = if (hasAcceptedTurn) acceptedBinding else null
        appliedRetention = BackgroundRetention.None
        stoppedBinding = acceptedBinding
        interruptCoordinator.reset()
        leaveRetention(reason, interrupting)
        endingVoice = false
    }

    /** The notification's Pause. */
    fun pauseFromControl() = holdOutput(PauseReason.User)

    /** The notification's Play. */
    fun playFromControl() {
        releaseOutput(PauseReason.User, PauseReason.Route)
    }

    /** The notification's Stop. */
    fun stopFromControl() = stopReply(RetentionEnd.NotificationStop)

    /** The user swiped the app away: nothing stays running, and nothing is replayed. */
    fun taskRemoved() {
        stopReply(RetentionEnd.TaskRemoved)
        tearDownIfDue()
    }

    private fun holdOutput(reason: PauseReason) {
        if (appliedRetention != BackgroundRetention.Reply) return
        if (!pauseReasons.add(reason)) return
        journal.record("voice output paused reason=${reason.name.lowercase()}")
        if (pauseReasons.size == 1) {
            outputPaused = true
            clientPort.pauseOutput()
            pushServiceState()
        }
        refreshPausedHold()
    }

    private fun releaseOutput(reason: PauseReason, additional: PauseReason? = null) {
        if (pausedDeadline?.let { clock.nanoTime() >= it } == true) {
            stopReply(RetentionEnd.IdleTimeout)
            return
        }
        if (appliedRetention != BackgroundRetention.Reply || turnState.isTerminal ||
            acceptedBinding == stoppedBinding || !clientPort.hasLiveTransport()
        ) return
        val removed = pauseReasons.remove(reason)
        val additionallyRemoved = additional?.let { pauseReasons.remove(it) } ?: false
        if (!removed && !additionallyRemoved) return
        if (pauseReasons.isNotEmpty()) return
        outputPaused = false
        refreshPausedHold()
        journal.record("voice output resumed reason=${reason.name.lowercase()}")
        clientPort.resumeOutput()
        pushServiceState()
    }

    private fun syncRoutes(wanted: Boolean) {
        if (wanted == observingRoutes) return
        observingRoutes = wanted
        val generation = ++routeGeneration
        if (!wanted) {
            audioRoutes.stop()
            selectedRoutes = emptyList()
            routeAwaitingDeparture = emptyList()
            awaitingRouteDeparture = false
            return
        }
        selectedRoutes = audioRoutes.selectedRoutes()
        audioRoutes.observe { change ->
            postToMain {
                if (generation != routeGeneration || !observingRoutes) return@postToMain
                onRouteChange(change)
            }
        }
    }

    private fun onRouteChange(change: AudioRouteChange) {
        val current = change.selected
        val lost = selectedRoutes.any { old ->
            old.personal && (old.id in change.removedIds || current.none { it.id == old.id })
        }
        if (change.event == AudioRouteEvent.BecomingNoisy && PauseReason.Route !in pauseReasons) {
            awaitingRouteDeparture = true
            routeAwaitingDeparture = (if (current.any { it.personal }) current else selectedRoutes).filter { it.personal }
        }
        if (awaitingRouteDeparture) {
            // The first personal sample after unknown noisy is a candidate for
            // the departing route, not evidence that a different output returned.
            if (routeAwaitingDeparture.isEmpty() && current.any { it.personal }) {
                routeAwaitingDeparture = current.filter { it.personal }
            }
            val removed = routeAwaitingDeparture.any { it.id in change.removedIds }
            val replaced = current.isNotEmpty() && routeAwaitingDeparture.isNotEmpty() &&
                current.none { route -> routeAwaitingDeparture.any { it.id == route.id } }
            val publicOutput = current.isNotEmpty() && current.none { it.personal }
            if (removed || replaced || publicOutput) awaitingRouteDeparture = false
        }
        selectedRoutes = current
        journal.record("audio route event reason=${change.event.name.lowercase()}")
        if (change.event == AudioRouteEvent.BecomingNoisy || lost ||
            (change.event == AudioRouteEvent.DeviceRemoved && current.isEmpty())
        ) holdOutput(PauseReason.Route)
        // A replacement can be verified in this very event. Other causes remain held.
        if (change.event != AudioRouteEvent.BecomingNoisy && !awaitingRouteDeparture &&
            current.isNotEmpty() && current.all { it.personal }
        ) releaseOutput(PauseReason.Route)
    }

    /** A held reply expires on monotonic time; stale timer callbacks cannot end a later hold. */
    private fun refreshPausedHold() {
        val wanted = pauseReasons.isNotEmpty() && appliedRetention == BackgroundRetention.Reply
        if (wanted && pausedDeadline == null) {
            pausedDeadline = clock.nanoTime() + retentionTimings.pausedHoldMillis * 1_000_000
            schedulePausedHold(++pausedGeneration)
        } else if (!wanted) {
            pausedHold?.cancel()
            pausedHold = null
            pausedDeadline = null
            pausedGeneration++
        }
    }

    private fun schedulePausedHold(generation: Long) {
        val deadline = pausedDeadline ?: return
        val remaining = deadline - clock.nanoTime()
        if (remaining <= 0) {
            stopReply(RetentionEnd.IdleTimeout)
            return
        }
        pausedHold = retentionTimers.after((remaining + 999_999) / 1_000_000) {
            if (generation == pausedGeneration && pausedDeadline == deadline) schedulePausedHold(generation)
        }
    }

    private fun scheduleRetentionWatchdog() {
        retentionWatchdog?.cancel()
        retentionWatchdog = retentionTimers.after(retentionTimings.recheckMillis) {
            retentionWatchdog = null
            syncRetention()
            if (observingRoutes && PauseReason.Route in pauseReasons) {
                onRouteChange(AudioRouteChange(AudioRouteEvent.SelectionChanged, audioRoutes.selectedRoutes()))
            }
            if (appliedRetention != BackgroundRetention.None) scheduleRetentionWatchdog()
        }
    }

    /**
     * Tells the foreground service what to show, only when that changes. The
     * service is started while the app is visible; a start from the background
     * is not allowed on current Android, so it is skipped and journaled.
     */
    private fun pushServiceState() {
        val state = VoiceServiceState(
            active = appliedRetention != BackgroundRetention.None,
            paused = outputPaused,
            appStopped = !lifecycle.isForeground,
            microphone = appliedRetention != BackgroundRetention.None &&
                (captureController?.isHandsFree == true || captureController?.isCapturing == true),
            listening = appliedRetention == BackgroundRetention.VoiceSession,
        )
        val last = lastServiceState
        if (state == last) return
        val running = last?.active == true
        if (!state.active && !running) {
            lastServiceState = state
            return
        }
        if (state.active && !running && !lifecycle.isForeground) {
            journal.record("fgs start skipped reason=background")
            return
        }
        lastServiceState = state
        foregroundService.sync(state)
    }

    /** One request for the active voice session; never reconfigure focus while output is live. */
    private fun syncFocus() {
        val voice = captureController?.isHandsFree == true || captureController?.isCapturing == true
        if (turnState.isTerminal && !voice) {
            abandonFocus()
        } else if (
            (voice || turnState.audio == AndroidAudioDelivery.Buffering ||
                turnState.audio == AndroidAudioDelivery.Speaking) &&
            !focusAsked &&
            appliedRetention != BackgroundRetention.None
        ) {
            focusAsked = true
            val generation = ++focusGeneration
            val granted = audioFocus.request(allowDuck = lifecycle.isForeground) { change ->
                postToMain {
                    if (focusAsked && generation == focusGeneration) onFocusChange(change)
                }
            }
            journal.record("audio focus request result=${if (granted) "granted" else "denied"}")
        }
    }

    private fun abandonFocus() {
        if (!focusAsked) return
        focusAsked = false
        focusGeneration++
        outputDucked = false
        audioFocus.abandon()
    }

    private fun onFocusChange(change: FocusChange) {
        journal.record("audio focus event change=${change.name}")
        if (change == FocusChange.LossTransient || change == FocusChange.LossPermanent) {
            captureController?.disarmHandsFree()
            captureController?.cancelCapture()
        }
        when (change) {
            FocusChange.LossTransient -> holdOutput(PauseReason.Focus)
            FocusChange.LossPermanent -> stopReply(RetentionEnd.FocusLost)
            FocusChange.Gain -> {
                outputDucked = false
                clientPort.setOutputDucked(false)
                releaseOutput(PauseReason.Focus)
            }
            FocusChange.Duck -> {
                outputDucked = true
                clientPort.setOutputDucked(true)
            }
        }
    }

    /** A Connected state is trusted only while a ready socket exists (`ANDROID-HOME-06`). */
    val needsReconnect: Boolean
        get() = recoveryState.connection != AndroidConnectionState.Connected || !clientPort.hasLiveTransport()
    private var lastSelectedProfileId: String? = null

    /**
     * The selected Profile changed (or was deleted): a ladder retrying the
     * previous one stops. The first report, and a repeat after the Activity is
     * recreated, change nothing.
     */
    fun profileSelected(profileId: String?) {
        val previous = lastSelectedProfileId
        lastSelectedProfileId = profileId
        if (previous != null && previous != profileId) {
            recoveryController.cancelRecovery()
            captureController?.disarmHandsFree(AndroidHandsFreeExit.SessionEnded)
            captureController?.cancelCapture()
            interruptCoordinator.reset()
        }
    }

    /** Settings may never move pending input or an unresolved turn to another identity. */
    val canReconfigure: Boolean
        get() = !hasAcceptedTurn && !clientPort.hasActiveTurn() &&
            !clientPort.hasUncertainTurn() && !recoveryState.hasUnconfirmedTurn &&
            !recoveryState.unresolvedHomeTurn && !recoveryState.isRecovering &&
            !initiationInFlight && !resendInFlight && !handsFree &&
            captureController?.isCapturing != true

    fun configurationChanged() {
        if (!canReconfigure) return
        recoveryController.cancelRecovery()
        if (clientPort is ModeAwareClientPort) clientPort.configurationChanged() else clientPort.endSession()
        recoveryState = recoveryController.disconnectDeliberately()
        updateInitiation(AndroidInitiationState.Idle)
        updateTurn(AndroidTurnState())
        lastRequest = null
    }

    /** Only a successful Standard session.create resolves an uncertain Standard turn. */
    fun newStandardConversation(onResult: (Boolean) -> Unit) {
        if (clientPort.connectionMode() != RelayConnectionMode.Standard || initiationInFlight || resendInFlight) return
        initiationInFlight = true
        recoveryController.cancelRecovery()
        captureController?.disarmHandsFree()
        captureController?.cancelCapture()
        interruptCoordinator.reset()
        runOnWork {
            val outcome = clientPort.newConversation()
            runOnMain {
                journal.record("standard new_conversation result=${if (outcome is AndroidReconnectOutcome.Connected) "connected" else "failed"}")
                initiationInFlight = false
                if (outcome is AndroidReconnectOutcome.Connected) {
                    recoveryState = recoveryController.acceptNewConversation(outcome)
                    userDisconnected = false
                    lastRequest = null
                    resendResult = null
                    updateInitiation(AndroidInitiationState.Idle)
                    updateTurn(AndroidTurnState())
                    recorder?.recordDivider("New conversation")
                    historyRevision += 1
                }
                if (outcome !is AndroidReconnectOutcome.Connected && !clientPort.hasLiveTransport()) {
                    recoveryState = recoveryController.acceptNewConversation(outcome)
                }
                onResult(outcome is AndroidReconnectOutcome.Connected)
            }
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
        if (binding != observedBinding) {
            turnObservation?.cancel()
            observedBinding = binding
            turnObservation = binding?.let {
                clientPort.observeTurn(it) { event -> postToMain { onTurnEvent(event) } }
            }
        }
        syncRetention()
    }

    private fun updateTurn(state: AndroidTurnState) {
        turnState = state
        if (!state.isTerminal) settledHandled = false
        syncRetention()
    }

    private fun onTurnEvent(event: AndroidNormalizedEvent) {
        val audioBefore = turnState.audio
        updateTurn(AndroidTurnStateReducer.reduce(turnState, event))
        // Apply session controls at audio admission, before the startup cushion is released.
        if (turnState.audio != audioBefore &&
            (turnState.audio == AndroidAudioDelivery.Buffering || turnState.audio == AndroidAudioDelivery.Speaking)
        ) {
            if (pauseReasons.isNotEmpty()) clientPort.pauseOutput()
            if (outputDucked) clientPort.setOutputDucked(true)
        }
        syncFocus()
        // Text-complete (audio still pending) resolves the Home turn as well: Home's
        // control turn is over even though the reply is not yet terminal for the user.
        if (
            event is AndroidNormalizedEvent.TurnCompleted ||
            event is AndroidNormalizedEvent.TextCompleted ||
            event is AndroidNormalizedEvent.TurnFailed ||
            event is AndroidNormalizedEvent.TurnInterrupted
        ) {
            recoveryState = recoveryController.resolveHomeTurn()
        }
        // A new conversation only has a Home reference once a turn was
        // accepted; learn it so the next launch can continue it.
        if (event is AndroidNormalizedEvent.TurnCompleted || event is AndroidNormalizedEvent.TextCompleted) {
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
        interruptCoordinator.onTurnSettled()
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
                interruptCoordinator.reset()
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
                // A submit that failed at the transport reconnects; it never resends.
                if (state.reason == AndroidHomeUnavailableReason.TransportUnavailable) {
                    recover()
                }
            }

            is AndroidInitiationState.Rejected -> lastRequest = null

            else -> Unit
        }
    }

    fun initiate(input: AndroidTurnInput) {
        if (initiationInFlight) return
        if (input is AndroidTurnInput.Typed) {
            // Explicit Send owns the next turn before any submission work starts.
            interruptCoordinator.reset()
            captureController?.let { capture ->
                if (capture.isCapturing) {
                    journal.record("voice capture cancelled reason=typed_prompt")
                }
                capture.cancelCapture()
            }
        }
        initiationInFlight = true
        syncRetention()
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
                syncRetention()
            }
        }
    }

    /** The lifecycle generation of the reconnect already waiting for the worker; -1 when none. */
    private val queuedRecovery = AtomicLong(-1L)

    /** Starts the bounded reconnect ladder off the main thread. */
    fun recover() {
        if (userDisconnected || recoveryController.isLadderLive) return
        // A cached Connected without a live transport is not trusted.
        if (recoveryState.connection == AndroidConnectionState.Connected && !clientPort.hasLiveTransport()) {
            recoveryController.transportLost("Hermes Session transport is not live.")
        }
        val queuedUnder = lifecycle.generation
        // One waiting request per input is enough: a second one would run
        // another full ladder after the first ended Failed.
        if (queuedRecovery.getAndSet(queuedUnder) == queuedUnder) return
        runOnWork {
            queuedRecovery.compareAndSet(queuedUnder, -1L)
            // A newer foreground or background input superseded this request
            // while it waited for the worker, or an earlier request already
            // reconnected: never reconnect a client that is Connected.
            if (
                lifecycle.isCurrent(queuedUnder) &&
                recoveryController.state.connection != AndroidConnectionState.Connected
            ) {
                recoveryController.recover()
            }
        }
    }

    /** The user's explicit Connect: the only way out of a deliberate Disconnect. */
    fun connect() {
        userDisconnected = false
        recover()
    }

    /** Disconnect is offered while connected or recovering, and not during a submit. */
    val canDisconnect: Boolean
        get() = (
            recoveryState.connection == AndroidConnectionState.Connected ||
                recoveryState.isRecovering
            ) && !userDisconnected

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
        syncRetention()
        recoveryController.cancelRecovery()
        journal.record("home disconnect initiator=user")
        captureController?.let { capture ->
            capture.disarmHandsFree()
            capture.cancelCapture()
        }
        // A pending interrupt-and-listen must not open the microphone afterwards.
        interruptCoordinator.reset()
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
        captureController?.disarmHandsFree()
        captureController?.cancelCapture()
        interruptCoordinator.reset()
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
        syncRetention()
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
                syncRetention()
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
        // Settled: the turn ended, or a stop cleared it.
        if (teardownWhenSettled && attachedActivities == 0 && (turnState.isTerminal || acceptedBinding == null)) {
            tearDown("replySettled")
        }
    }

    private fun tearDown(reason: String) {
        if (tornDown) return
        tornDown = true
        leadSampler.close()
        journal.record("runtime teardown reason=$reason")
        connectionObservation.cancel()
        networkObservation.cancel()
        recoveryController.cancelRecovery()
        appliedRetention = BackgroundRetention.None
        releaseRetentionResources()
        tailTimer?.cancel()
        tailTimer = null
        tailDeadline = null
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

    /** The runtime if one exists; never creates one. */
    @Synchronized
    fun current(): HomeRuntime? = runtime

    @Synchronized
    private fun release() {
        runtime = null
    }
}
