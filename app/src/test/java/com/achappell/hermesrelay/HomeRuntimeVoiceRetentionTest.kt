package com.achappell.hermesrelay

import org.junit.Assert.*
import org.junit.Test

/** HOME-08b: real runtime/controller decisions, virtual monotonic time, no wall-clock waits. */
class HomeRuntimeVoiceRetentionTest {
    private val timers = ManualVoiceTimers()
    private val work = QueuedExecutorService()
    private val journal = RecordingJournal()
    private val speech = Speech()
    private val service = Service()
    private val home = Home()
    private val focus = Focus()
    private val mediaWrites = MediaSessionWrites()
    private val runtime = HomeRuntime(
        clientPort = home, speechInput = speech, historyStore = null,
        postToMain = { it.run() }, workExecutor = work, journal = journal,
        clock = timers, voiceTimers = timers, retentionTimers = timers,
        foregroundService = service, audioFocus = focus,
        mediaSessionWrites = mediaWrites,
    )
    private val capture get() = runtime.captureController!!

    private class Speech : AndroidSpeechInput {
        override var networkRecognitionAllowed = false
        val listeners = mutableListOf<(AndroidSpeechEvent) -> Unit>()
        var cancels = 0
        override fun authorization() = AndroidSpeechAuthorization.Granted
        override fun start(onEvent: (AndroidSpeechEvent) -> Unit) { listeners += onEvent }
        override fun stop() = Unit
        override fun cancel() { cancels++ }
        fun emit(event: AndroidSpeechEvent) { listeners.last()(event) }
    }

    private class Service : ForegroundServiceController {
        val states = mutableListOf<VoiceServiceState>()
        var ready = true
        var pending: ((Boolean) -> Unit)? = null
        var onSync: (VoiceServiceState) -> Unit = {}
        override fun sync(state: VoiceServiceState) { states += state; onSync(state) }
        override fun awaitMicrophone(onReady: (Boolean) -> Unit) {
            if (ready) onReady(true) else pending = onReady
        }
        val last get() = states.last()
    }

    private class Focus : AudioFocusController {
        var listener: ((FocusChange) -> Unit)? = null
        val requests = mutableListOf<Boolean>()
        override fun request(allowDuck: Boolean, onChange: (FocusChange) -> Unit): Boolean {
            requests += allowDuck
            listener = onChange
            return true
        }
        override fun abandon() = Unit
    }

    private class Home : AndroidClientPort {
        val binding = AndroidTurnBinding("voice", "connection", "turn")
        var active = false
        var live = true
        var closes = 0
        var submits = 0
        var reconnects = 0
        var interrupts = 0
        var listener: ((AndroidNormalizedEvent) -> Unit)? = null
        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes, descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes, selectedProfile = AndroidProfile("voice", "Voice"),
            authorizationState = AndroidAuthorizationState.Verified,
        )
        override fun reconnect(): AndroidReconnectOutcome {
            reconnects++
            live = true
            return AndroidReconnectOutcome.Connected("connection")
        }
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            submits++
            active = true
            return AndroidInitiationResult.Accepted(binding)
        }
        override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
            listener = onEvent
            return AndroidTurnObservation { listener = null }
        }
        override fun hasActiveTurn() = active
        override fun hasLiveTransport() = live
        override fun closeForLifecycle() { closes++; live = false }
        override fun closeAfterInterrupt() = closeForLifecycle()
        override fun interruptTurn(binding: AndroidTurnBinding): Boolean { interrupts++; return true }
        fun emit(event: AndroidNormalizedEvent) { listener?.invoke(event) }
        fun complete() {
            active = false
            emit(AndroidNormalizedEvent.TurnCompleted(binding, "fixture response"))
        }
    }

    private fun connect() {
        runtime.appStarted()
        runtime.recover()
        work.drain()
    }
    private fun arm() { connect(); capture.armHandsFree() }
    private fun reply() {
        runtime.initiate(AndroidTurnInput.Typed("fixture request"))
        work.drain()
        home.emit(AndroidNormalizedEvent.AudioStarted(home.binding))
    }
    private fun drainReply() {
        home.emit(AndroidNormalizedEvent.AudioEnded(home.binding))
        home.complete()
        timers.advanceBy(1_000) // terminal callback and client active flag may be ordered either way
    }

    @Test fun background_baseline_precedes_entry_work_and_ignores_duplicate_stop_and_rotation() {
        connect()
        reply()
        val response = mediaWrites.begin()
        mediaWrites.written(false)
        service.onSync = { if (it.appStopped) mediaWrites.written(false) }
        runtime.appBackgrounded(isChangingConfigurations = true)
        assertNull(response.baseline)
        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.appBackgrounded(isChangingConfigurations = false)
        mediaWrites.written(false)
        assertEquals(2L, mediaWrites.end(response))
    }

    @Test fun audio_admission_acquires_focus_while_buffering_not_while_waiting_for_synthesis() {
        connect()
        runtime.initiate(AndroidTurnInput.Typed("fixture request"))
        work.drain()
        home.emit(AndroidNormalizedEvent.TextCompleted(home.binding, "text ready"))
        assertEquals(AndroidTurnPhase.Buffering, runtime.turnState.phase)
        assertEquals(AndroidAudioDelivery.NotStarted, runtime.turnState.audio)
        assertTrue(focus.requests.isEmpty())
        home.emit(AndroidNormalizedEvent.AudioBuffering(home.binding))
        assertEquals(AndroidAudioDelivery.Buffering, runtime.turnState.audio)
        assertEquals(listOf(true), focus.requests)
        runtime.appBackgrounded(false)
        work.drain()
        assertTrue(service.last.active)
        assertEquals(0, home.closes)
        home.emit(AndroidNormalizedEvent.AudioStarted(home.binding))
        assertEquals(AndroidAudioDelivery.Speaking, runtime.turnState.audio)
        assertEquals("playback never reconfigures the admitted focus", listOf(true), focus.requests)
        assertFalse(capture.isCapturing)
    }

    @Test fun foreground_microphone_service_is_requested_and_acknowledged_before_capture() {
        connect()
        service.ready = false
        capture.armHandsFree()
        assertTrue(service.last.active)
        assertTrue(service.last.microphone)
        assertFalse(service.last.appStopped)
        assertEquals(0, speech.listeners.size)
        service.pending!!(true)
        assertEquals(1, speech.listeners.size)
        runtime.appBackgrounded(false)
        work.drain()
        assertEquals(0, home.closes)
        assertTrue(service.last.listening)
        assertTrue(service.last.microphone)
        assertEquals(1, journal.count("voice background enter retention=voiceSession"))
    }

    @Test fun failed_microphone_service_never_opens_capture_and_releases_retention() {
        connect()
        service.ready = false
        capture.armHandsFree()
        service.pending!!(false)
        assertFalse(runtime.handsFree)
        assertFalse(service.last.active)
        assertEquals(0, speech.listeners.size)
    }

    @Test fun background_only_arm_tap_permission_retry_and_controls_never_start_microphone() {
        connect()
        runtime.appBackgrounded(false)
        work.drain()
        capture.armHandsFree()
        capture.beginCapture()
        capture.allowNetworkRecognitionAndRetry()
        runtime.playFromControl()
        assertFalse(capture.isHandsFree)
        assertEquals(0, speech.listeners.size)
        assertFalse(service.states.any { it.microphone })
    }

    @Test fun silence_reopens_without_resetting_the_sixty_second_idle_deadline() {
        arm()
        runtime.appBackgrounded(false)
        timers.advanceBy(30_000)
        speech.emit(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard))
        timers.advanceBy(250)
        assertEquals(2, speech.listeners.size)
        timers.advanceBy(29_749)
        assertTrue(runtime.handsFree)
        timers.advanceBy(1)
        work.drain()
        assertFalse(runtime.handsFree)
        assertFalse(capture.isCapturing)
        assertFalse(service.last.active)
        assertEquals(1, home.closes)
        assertEquals(1, journal.count("voice idle expired"))
        timers.advanceBy(120_000)
        runtime.stopFromControl()
        work.drain()
        assertEquals(1, home.closes)
    }

    @Test fun genuine_speech_and_one_reply_restart_idle_after_completion_not_after_recognizer_ready() {
        arm()
        runtime.appBackgrounded(false)
        timers.advanceBy(59_000)
        speech.emit(AndroidSpeechEvent.SpeechActivity)
        timers.advanceBy(2_000)
        assertTrue(runtime.handsFree)
        speech.emit(AndroidSpeechEvent.Final("follow up"))
        assertEquals(1, home.submits)
        home.emit(AndroidNormalizedEvent.AudioStarted(home.binding))
        timers.advanceBy(70_000)
        assertTrue(runtime.handsFree)
        home.emit(AndroidNormalizedEvent.AudioEnded(home.binding))
        home.complete()
        timers.advanceBy(59_999)
        assertTrue(runtime.handsFree)
        timers.advanceBy(1)
        work.drain()
        assertFalse(runtime.handsFree)
        assertEquals(1, home.submits)
        assertEquals(1, home.closes)
    }

    @Test fun arming_during_output_blocks_recognizer_and_activity_until_drain_plus_tail() {
        connect()
        reply()
        capture.armHandsFree()
        assertEquals(0, speech.listeners.size)
        runtime.appBackgrounded(false)
        home.emit(AndroidNormalizedEvent.TextCompleted(home.binding, "fixture response"))
        timers.advanceBy(10_000)
        assertEquals(0, speech.listeners.size)
        home.emit(AndroidNormalizedEvent.AudioEnded(home.binding))
        home.complete()
        timers.advanceBy(1_499)
        assertEquals(0, speech.listeners.size)
        timers.advanceBy(1)
        assertEquals(1, speech.listeners.size)
        speech.emit(AndroidSpeechEvent.SpeechActivity)
        speech.emit(AndroidSpeechEvent.Final("genuine follow up"))
        assertEquals(2, home.submits)
    }

    @Test fun retired_result_and_activity_callbacks_cannot_echo_or_extend_idle() {
        arm()
        val retired = speech.listeners.single()
        reply()
        retired(AndroidSpeechEvent.SpeechActivity)
        retired(AndroidSpeechEvent.Partial("fixture response"))
        retired(AndroidSpeechEvent.Final("fixture response"))
        assertEquals(1, home.submits)
        drainReply()
        timers.advanceBy(500)
        assertEquals(2, speech.listeners.size)
        retired(AndroidSpeechEvent.SpeechActivity)
        retired(AndroidSpeechEvent.Final("fixture response"))
        assertFalse(capture.hasVoiceActivity)
        assertEquals(1, home.submits)
    }

    @Test fun focus_loss_disarms_and_downgrades_but_rearming_during_output_is_still_suppressed() {
        arm()
        reply()
        focus.listener!!(FocusChange.LossTransient)
        assertFalse(runtime.handsFree)
        assertTrue(runtime.outputPaused)
        assertFalse(service.last.microphone)
        assertTrue(service.last.active)
        capture.armHandsFree()
        assertTrue(runtime.handsFree)
        assertEquals(1, speech.listeners.size)
        runtime.appBackgrounded(false)
        focus.listener!!(FocusChange.LossTransient)
        focus.listener!!(FocusChange.Gain)
        assertFalse(runtime.handsFree)
        assertEquals(1, speech.listeners.size)
    }

    @Test fun paused_reply_timeout_disarms_microphone_and_closes_once() {
        arm()
        reply()
        runtime.appBackgrounded(false)
        runtime.pauseFromControl()
        timers.advanceBy(59_999)
        assertTrue(runtime.handsFree)
        timers.advanceBy(1)
        work.drain()
        runtime.playFromControl()
        assertFalse(runtime.handsFree)
        assertFalse(service.last.active)
        assertEquals(1, home.closes)
        assertEquals(1, home.interrupts)
    }

    @Test fun a_late_foreground_service_ack_cannot_revive_a_stopped_session() {
        connect()
        service.ready = false
        capture.armHandsFree()
        runtime.appBackgrounded(false)
        runtime.stopFromControl()
        service.pending!!(true)
        work.drain()
        assertEquals(0, speech.listeners.size)
        assertFalse(runtime.handsFree)
        assertEquals(1, home.closes)
    }

    @Test fun foreground_return_supersedes_queued_idle_close_without_reconnecting_or_rearming() {
        arm()
        runtime.appBackgrounded(false)
        timers.advanceBy(60_000)
        runtime.appStarted()
        work.drain()
        assertEquals(0, home.closes)
        assertEquals(1, home.reconnects)
        assertFalse(runtime.handsFree)
        assertFalse(service.last.active)
    }

    @Test fun returning_while_listening_keeps_the_same_capture_and_transport() {
        arm()
        runtime.appBackgrounded(false)
        runtime.appStarted()
        work.drain()
        assertEquals(1, speech.listeners.size)
        assertEquals(1, home.reconnects)
        assertEquals(0, home.closes)
        assertTrue(service.last.microphone)
    }

    @Test fun profile_change_retires_the_recognizer_before_late_results_can_cross_identity() {
        arm()
        runtime.profileSelected("voice")
        val retired = speech.listeners.single()
        runtime.profileSelected("other")
        retired(AndroidSpeechEvent.Final("old identity"))
        assertFalse(runtime.handsFree)
        assertFalse(capture.isCapturing)
        assertFalse(service.last.active)
        assertEquals(0, home.submits)
    }

    @Test fun task_removal_and_focus_loss_end_idle_listening_without_ever_rearming() {
        arm()
        runtime.appBackgrounded(false)
        focus.listener!!(FocusChange.LossTransient)
        focus.listener!!(FocusChange.Gain)
        runtime.taskRemoved()
        work.drain()
        assertFalse(runtime.handsFree)
        assertFalse(service.last.active)
        assertEquals(1, home.closes)
        assertEquals(1, speech.listeners.size)
    }

    @Test fun early_and_cancelled_timer_callbacks_cannot_shorten_or_revive_a_new_idle_window() {
        var now = 0L
        val callbacks = mutableListOf<() -> Unit>()
        val unreliableTimers = VoiceTimers { _, action ->
            callbacks += action
            VoiceTimer { /* Deliberately delivers cancelled callbacks to model a looper race. */ }
        }
        val local = HomeRuntime(
            clientPort = home, speechInput = speech, historyStore = null,
            postToMain = { it.run() }, workExecutor = work,
            clock = MonotonicClock { now }, voiceTimers = unreliableTimers,
            retentionTimers = unreliableTimers, foregroundService = service,
        )
        local.appStarted()
        local.recover()
        work.drain()
        local.captureController!!.armHandsFree()
        val oldIdle = callbacks.last()
        oldIdle() // Timer wakes early; elapsed monotonic time is still zero.
        assertTrue(local.handsFree)
        local.captureController.disarmHandsFree()
        now = 1_000_000_000
        local.captureController.armHandsFree()
        val newIdle = callbacks.last()
        now = 60_000_000_000
        oldIdle()
        assertTrue(local.handsFree)
        now = 61_000_000_000
        newIdle()
        assertFalse(local.handsFree)
        assertFalse(service.last.active)
    }

    @Test fun speech_activity_wins_when_the_idle_callback_precedes_its_queued_runtime_refresh() {
        val mainQueue = ArrayDeque<Runnable>()
        fun flushMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run() }
        val local = HomeRuntime(
            clientPort = home, speechInput = speech, historyStore = null,
            postToMain = { mainQueue.addLast(it) }, workExecutor = work,
            clock = timers, voiceTimers = timers, retentionTimers = timers,
            retentionTimings = RetentionTimings(recheckMillis = 120_000),
            foregroundService = service,
        )
        local.appStarted()
        local.recover()
        work.drain()
        flushMain()
        local.captureController!!.armHandsFree()
        flushMain()
        local.appBackgrounded(false)
        speech.emit(AndroidSpeechEvent.SpeechActivity)
        assertTrue(local.captureController.hasVoiceActivity)
        assertTrue(mainQueue.isNotEmpty())
        // The looper already has an idle timer due ahead of onVoiceActivity's refresh.
        timers.advanceBy(60_000)
        work.drain()
        assertTrue(local.handsFree)
        assertTrue(local.captureController.isCapturing)
        assertTrue(service.last.active)
        assertEquals(0, home.closes)
        flushMain()
    }

    @Test fun exact_stop_retires_speech_activity_so_a_rearmed_session_still_expires() {
        assertRearmedIdleExpiresAfter(AndroidSpeechEvent.Final("stop"))
        assertEquals(0, home.submits)
    }

    @Test fun recognizer_failure_retires_speech_activity_so_a_rearmed_session_still_expires() {
        assertRearmedIdleExpiresAfter(AndroidSpeechEvent.Failed(AndroidSpeechFailure.AudioUnavailable))
        assertEquals(0, home.submits)
    }

    private fun assertRearmedIdleExpiresAfter(terminal: AndroidSpeechEvent) {
        arm()
        speech.emit(AndroidSpeechEvent.SpeechActivity)
        assertTrue(capture.hasVoiceActivity)
        speech.emit(terminal)
        assertFalse(runtime.handsFree)
        assertFalse(capture.hasVoiceActivity)
        capture.armHandsFree()
        assertEquals(2, speech.listeners.size)
        runtime.appBackgrounded(false)
        timers.advanceBy(59_999)
        assertTrue(runtime.handsFree)
        assertEquals(0, home.closes)
        timers.advanceBy(1)
        work.drain()
        assertFalse(runtime.handsFree)
        assertFalse(service.last.active)
        assertEquals(1, home.closes)
    }

    @Test fun queued_capture_callbacks_cannot_resurrect_retention_after_idle_teardown() {
        val mainQueue = ArrayDeque<Runnable>()
        fun flushMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run() }
        val local = HomeRuntime(
            clientPort = home, speechInput = speech, historyStore = null,
            postToMain = { mainQueue.addLast(it) }, workExecutor = work,
            journal = journal, clock = timers, voiceTimers = timers,
            retentionTimers = timers, foregroundService = service,
        )
        local.appStarted()
        local.recover()
        work.drain()
        flushMain()
        local.captureController!!.armHandsFree()
        flushMain()
        local.appBackgrounded(false)
        timers.advanceBy(60_000)
        work.drain()
        // Idle/capture and armed=false UI notifications are still queued after teardown.
        assertFalse(local.captureController.isHandsFree)
        flushMain()
        work.drain()
        flushMain()
        assertEquals(BackgroundRetention.None, local.retention)
        assertFalse(local.handsFree)
        assertFalse(service.last.active)
        assertEquals("one park despite delayed capture-state and armed-state notifications", 1, home.closes)
        assertEquals(1, journal.count("lifecycle deactivate trigger=backgroundWorkEnded reply=none"))
        assertEquals(0, journal.count("fgs start skipped reason=background"))
    }
}
