package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-HOME-08` at the runtime seam: a reply in flight keeps the transport,
 * audio and a foreground service alive after the app stops, and one end path
 * parks the claim and releases everything when the work is over.
 */
class HomeRuntimeRetentionTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val binding = AndroidTurnBinding(profile.id, "bridge-1", "turn-1")
    private val clock = ManualClock()
    private val pacer = ManualPacer(clock)
    private val journal = RecordingJournal()
    private val work = QueuedExecutorService()
    private val timers = ManualVoiceTimers()
    private val service = RecordingService()
    private val focus = FakeFocus()

    private class RecordingService : ForegroundServiceController {
        val states = mutableListOf<VoiceServiceState>()

        override fun sync(state: VoiceServiceState) {
            states += state
        }

        val last: VoiceServiceState? get() = states.lastOrNull()
    }

    private class FakeFocus : AudioFocusController {
        val requests = mutableListOf<Boolean>()
        var abandons = 0
        var granted = true
        private var listener: ((FocusChange) -> Unit)? = null

        override fun request(allowDuck: Boolean, onChange: (FocusChange) -> Unit): Boolean {
            requests += allowDuck
            listener = onChange
            return granted
        }

        override fun abandon() {
            abandons += 1
        }

        fun emit(change: FocusChange) {
            listener?.invoke(change)
        }
    }

    private class Home(private val binding: AndroidTurnBinding) : AndroidClientPort {
        var reconnects = 0
        var lifecycleCloses = 0
        var gracefulCloses = 0
        var interrupts = 0
        var pauses = 0
        var resumes = 0
        var live = true
        var activeTurn = false
        private var turnListener: ((AndroidNormalizedEvent) -> Unit)? = null
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("amanda", "Amanda"),
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            activeTurn = true
            return AndroidInitiationResult.Accepted(binding)
        }

        override fun reconnect(): AndroidReconnectOutcome {
            reconnects += 1
            live = true
            return AndroidReconnectOutcome.Connected("bridge-1")
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            turnListener = onEvent
            return AndroidTurnObservation { turnListener = null }
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation { connectionListener = null }
        }

        override fun hasActiveTurn() = activeTurn

        override fun hasLiveTransport() = live

        override fun closeForLifecycle() {
            lifecycleCloses += 1
            live = false
        }

        override fun closeAfterInterrupt() {
            gracefulCloses += 1
            closeForLifecycle()
        }

        override fun interruptTurn(binding: AndroidTurnBinding): Boolean {
            interrupts += 1
            return true
        }

        override fun pauseOutput() {
            pauses += 1
        }

        override fun resumeOutput() {
            resumes += 1
        }

        fun emit(event: AndroidNormalizedEvent) {
            turnListener?.invoke(event)
        }

        fun dropTransport() {
            live = false
            activeTurn = false
            connectionListener?.invoke(AndroidNormalizedEvent.Disconnected("bridge-1", "lost"))
        }
    }

    private fun runtime(home: Home): HomeRuntime = HomeRuntime(
        clientPort = home,
        speechInput = null,
        historyStore = InMemoryAndroidHistoryStore(),
        postToMain = { it.run() },
        workExecutor = work,
        journal = journal,
        recoveryPacer = pacer,
        clock = clock,
        foregroundService = service,
        audioFocus = focus,
        retentionTimers = timers,
    )

    /** A connected app with a reply submitted and accepted, in front. */
    private fun replying(home: Home = Home(binding)): Pair<Home, HomeRuntime> {
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.appForegrounded()
        runtime.recover()
        work.drain()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        work.drain()
        assertEquals(BackgroundRetention.Reply, runtime.retention)
        return home to runtime
    }

    private fun finishReply(home: Home) {
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        home.emit(AndroidNormalizedEvent.AudioEnded(binding))
        home.activeTurn = false
        home.emit(AndroidNormalizedEvent.TurnCompleted(binding, "done"))
    }

    @Test
    fun a_submitted_reply_starts_the_service_while_the_app_is_visible() {
        replying()

        assertEquals(VoiceServiceState(active = true, paused = false, appStopped = false), service.last)
        assertEquals(1, service.states.size)
    }

    @Test
    fun a_stop_mid_reply_keeps_the_socket_and_tells_the_service_the_app_stopped() {
        val (home, runtime) = replying()

        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(VoiceServiceState(active = true, paused = false, appStopped = true), service.last)
        assertEquals(1, journal.count("voice background enter retention=reply"))
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun a_configuration_change_neither_stops_nor_retains_anything_new() {
        val (home, runtime) = replying()
        val before = service.states.size

        runtime.appBackgrounded(isChangingConfigurations = true)
        work.drain()

        assertEquals(before, service.states.size)
        assertEquals(0, home.lifecycleCloses)
    }

    @Test
    fun a_finished_reply_ends_the_service_and_parks_the_claim_once() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        finishReply(home)
        work.drain()

        assertTrue(runtime.turnState.isTerminal)
        assertFalse(service.last!!.active)
        assertEquals(1, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
        assertEquals(1, journal.count("voice background retention ended reason=workFinished"))
        assertEquals(1, journal.count("lifecycle deactivate trigger=backgroundWorkEnded reply=none"))
    }

    @Test
    fun a_text_terminal_before_the_audio_ends_keeps_the_reply_retained() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        home.emit(AndroidNormalizedEvent.TextCompleted(binding, "text only so far"))
        work.drain()

        assertEquals(BackgroundRetention.Reply, runtime.retention)
        assertEquals(0, home.lifecycleCloses)
        assertTrue(service.last!!.active)

        finishReply(home)
        work.drain()

        assertEquals(1, home.lifecycleCloses)
        assertFalse(service.last!!.active)
    }

    @Test
    fun a_stop_with_nothing_retained_is_exactly_the_idle_close() {
        val home = Home(binding)
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.recover()
        work.drain()

        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(1, home.lifecycleCloses)
        assertTrue(service.states.isEmpty())
        assertEquals(1, journal.count("lifecycle deactivate trigger=stopWithoutRetention reply=none"))
    }

    @Test
    fun returning_to_the_foreground_keeps_the_live_reply_and_reconnects_nothing() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.appStarted()
        runtime.appForegrounded()
        work.drain()

        assertEquals(1, home.reconnects)
        assertEquals(0, home.lifecycleCloses)
        assertEquals(1, journal.count("voice background exit"))
        assertEquals(VoiceServiceState(active = true, paused = false, appStopped = false), service.last)
    }

    @Test
    fun a_reply_submitted_while_stopped_never_asks_for_a_foreground_service() {
        val home = Home(binding)
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.recover()
        work.drain()
        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()
        // Reconnected by some other path while still stopped.
        runtime.recover()
        work.drain()

        runtime.initiate(AndroidTurnInput.Typed("hello"))
        work.drain()

        assertTrue(service.states.isEmpty())
        assertEquals(1, journal.count("fgs start skipped reason=background"))
    }

    @Test
    fun notification_pause_and_play_hold_the_output_only() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.pauseFromControl()
        work.drain()

        assertTrue(runtime.outputPaused)
        assertEquals(1, home.pauses)
        assertEquals(0, home.interrupts)
        assertEquals(VoiceServiceState(active = true, paused = true, appStopped = true), service.last)

        runtime.playFromControl()
        work.drain()

        assertFalse(runtime.outputPaused)
        assertEquals(1, home.resumes)
        assertEquals(VoiceServiceState(active = true, paused = false, appStopped = true), service.last)
    }

    @Test
    fun a_focus_gain_never_resumes_a_reply_the_user_paused() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        runtime.pauseFromControl()
        focus.emit(FocusChange.LossTransient)
        work.drain()
        assertEquals(1, home.pauses)

        focus.emit(FocusChange.Gain)
        work.drain()

        assertTrue("still held by the user", runtime.outputPaused)
        assertEquals(0, home.resumes)

        runtime.playFromControl()
        work.drain()

        assertFalse(runtime.outputPaused)
        assertEquals(1, home.resumes)
    }

    @Test
    fun a_transient_focus_loss_pauses_and_the_gain_resumes() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))

        focus.emit(FocusChange.LossTransient)
        work.drain()
        assertTrue(runtime.outputPaused)

        focus.emit(FocusChange.Gain)
        work.drain()

        assertFalse(runtime.outputPaused)
        assertEquals(1, home.resumes)
    }

    @Test
    fun a_permanent_focus_loss_stops_the_reply_and_parks_it_when_stopped() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        runtime.appBackgrounded(isChangingConfigurations = false)

        focus.emit(FocusChange.LossPermanent)
        work.drain()

        assertEquals(1, home.interrupts)
        assertEquals(1, home.lifecycleCloses)
        assertFalse(service.last!!.active)
        assertEquals(1, journal.count("voice background retention ended reason=interruption"))
    }

    @Test
    fun the_focus_gain_type_is_chosen_once_at_the_first_audio() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        runtime.appBackgrounded(isChangingConfigurations = false)

        assertEquals("one request, made in the foreground, may duck", listOf(true), focus.requests)
    }

    @Test
    fun audio_that_starts_while_stopped_asks_for_a_transient_gain() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        home.emit(AndroidNormalizedEvent.AudioStarted(binding))

        assertEquals(listOf(false), focus.requests)
    }

    @Test
    fun focus_is_abandoned_when_the_reply_ends() {
        val (home, _) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))

        finishReply(home)

        assertEquals(1, focus.abandons)
    }

    @Test
    fun a_stream_that_starts_after_a_pause_is_held_again() {
        val (home, runtime) = replying()
        runtime.pauseFromControl()
        work.drain()
        assertEquals(1, home.pauses)

        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        work.drain()

        assertEquals("the new stream started unpaused", 2, home.pauses)
    }

    @Test
    fun a_reply_held_paused_while_stopped_ends_after_sixty_seconds() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.pauseFromControl()
        work.drain()

        timers.advanceBy(59_000)
        work.drain()
        assertEquals(0, home.lifecycleCloses)

        timers.advanceBy(2_000)
        work.drain()

        assertEquals(1, home.interrupts)
        assertEquals(1, home.lifecycleCloses)
        assertFalse(service.last!!.active)
        assertEquals(1, journal.count("voice background retention ended reason=idleTimeout"))
    }

    @Test
    fun a_pause_in_the_foreground_is_never_timed_out() {
        val (home, runtime) = replying()
        runtime.pauseFromControl()
        work.drain()

        timers.advanceBy(120_000)
        work.drain()

        assertEquals(0, home.interrupts)
        assertEquals(0, home.lifecycleCloses)
        assertTrue(runtime.outputPaused)
    }

    @Test
    fun returning_to_the_foreground_resumes_a_lock_screen_pause() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.pauseFromControl()
        work.drain()

        runtime.appStarted()
        work.drain()

        assertFalse(runtime.outputPaused)
        assertEquals(1, home.resumes)
        assertEquals(0, home.lifecycleCloses)
    }

    @Test
    fun notification_stop_interrupts_then_parks_and_clears_the_card_once() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.stopFromControl()
        runtime.stopFromControl()
        work.drain()

        assertEquals(1, home.interrupts)
        assertEquals(1, home.lifecycleCloses)
        assertFalse(service.last!!.active)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
        assertEquals(AndroidInitiationState.Idle, runtime.initiationState)
        assertEquals(1, journal.count("lifecycle deactivate trigger=notificationStop reply=none"))
    }

    @Test
    fun notification_stop_in_the_foreground_only_interrupts() {
        val (home, runtime) = replying()

        runtime.stopFromControl()
        work.drain()

        assertEquals(1, home.interrupts)
        assertEquals(0, home.lifecycleCloses)
        assertFalse(service.last!!.active)
    }

    @Test
    fun task_removal_ends_the_retained_reply_once() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.taskRemoved()
        runtime.taskRemoved()
        work.drain()

        assertEquals(1, home.interrupts)
        assertEquals(1, home.lifecycleCloses)
        assertEquals(1, journal.count("lifecycle deactivate trigger=taskRemoved reply=none"))
    }

    @Test
    fun a_transport_loss_ends_the_retention_and_offers_the_prompt_without_resending() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        home.dropTransport()
        work.drain()

        assertEquals(BackgroundRetention.None, runtime.retention)
        assertFalse(service.last!!.active)
        assertEquals(1, home.lifecycleCloses)
        assertEquals("no background reconnect", 1, home.reconnects)
        assertNotNull(runtime.recoveryState.unconfirmedTurn)
    }

    @Test
    fun a_deliberate_disconnect_releases_the_service() {
        val (_, runtime) = replying()

        runtime.disconnect()
        work.drain()

        assertFalse(service.last!!.active)
        assertEquals(BackgroundRetention.None, runtime.retention)
    }

    @Test
    fun a_finished_reply_the_client_has_not_cleared_yet_is_ended_by_the_recheck() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)
        // The terminal event arrives before the client clears its turn.
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        home.emit(AndroidNormalizedEvent.AudioEnded(binding))
        home.emit(AndroidNormalizedEvent.TurnCompleted(binding, "done"))
        work.drain()
        assertEquals(BackgroundRetention.Reply, runtime.retention)
        assertEquals(0, home.lifecycleCloses)

        home.activeTurn = false
        timers.advanceBy(1_000)
        work.drain()

        assertEquals(1, home.lifecycleCloses)
        assertFalse(service.last!!.active)
    }

    @Test
    fun tearing_down_releases_the_service_and_focus() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        runtime.activityCreated()

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
        finishReply(home)
        work.drain()

        assertFalse(service.last!!.active)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun a_stopped_reply_does_not_restart_the_service_when_late_events_arrive() {
        val (home, runtime) = replying()

        runtime.stopFromControl()
        val statesAfterStop = service.states.size
        home.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "still streaming"))
        work.drain()

        assertEquals(BackgroundRetention.None, runtime.retention)
        assertEquals("no restart after Stop", statesAfterStop, service.states.size)
        assertFalse(service.last!!.active)
    }

    @Test
    fun a_stopped_reply_parks_the_claim_once_even_if_events_follow() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.stopFromControl()
        home.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "still streaming"))
        work.drain()
        home.dropTransport()
        work.drain()

        assertEquals(1, journal.count("voice background retention ended"))
        assertEquals(1, home.lifecycleCloses)
    }

    @Test
    fun a_stop_flushes_the_interrupt_before_the_socket_goes_but_a_natural_end_does_not_need_to() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.stopFromControl()
        work.drain()

        assertEquals(1, home.gracefulCloses)

        val (finished, other) = replying()
        other.appBackgrounded(isChangingConfigurations = false)
        finishReply(finished)
        work.drain()

        assertEquals(0, finished.gracefulCloses)
        assertEquals(1, finished.lifecycleCloses)
    }

    @Test
    fun the_prompt_of_a_stopped_reply_is_not_offered_as_unconfirmed_later() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        runtime.stopFromControl()
        work.drain()
        home.dropTransport()
        work.drain()

        assertEquals(null, runtime.recoveryState.unconfirmedTurn)
        assertEquals(null, runtime.lastRequest)
    }

    @Test
    fun a_stop_while_the_submit_is_still_in_flight_keeps_the_reply_retained() {
        val home = Home(binding)
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.appForegrounded()
        runtime.recover()
        work.drain()

        runtime.initiate(AndroidTurnInput.Typed("hello"))
        runtime.appBackgrounded(isChangingConfigurations = false)
        work.drain()

        assertEquals(
            listOf(
                VoiceServiceState(active = true, paused = false, appStopped = false),
                VoiceServiceState(active = true, paused = false, appStopped = true),
            ),
            service.states,
        )
        assertEquals(0, journal.count("fgs start skipped"))
        assertEquals(0, home.lifecycleCloses)
    }

    @Test
    fun a_reply_that_ends_just_as_the_app_returns_never_closes_the_foreground_socket() {
        val (home, runtime) = replying()
        runtime.appBackgrounded(isChangingConfigurations = false)

        finishReply(home)
        runtime.appStarted()
        work.drain()

        assertEquals(0, home.lifecycleCloses)
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
    }

    @Test
    fun tearing_down_while_the_reply_is_still_retained_releases_the_service() {
        val (home, runtime) = replying()
        home.emit(AndroidNormalizedEvent.AudioStarted(binding))
        runtime.activityCreated()

        runtime.appBackgrounded(isChangingConfigurations = false)
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        // A reply in flight defers the teardown; end it from the notification.
        assertEquals(1, journal.count("runtime teardown deferred"))
        runtime.stopFromControl()
        work.drain()

        assertFalse(service.last!!.active)
        assertEquals(1, focus.abandons)
        assertEquals("the deferred teardown ran once the reply was stopped", 1, journal.count("runtime teardown reason="))
    }

    @Test
    fun a_runtime_torn_down_with_the_submit_in_flight_releases_the_service() {
        val home = Home(binding)
        val runtime = runtime(home)
        runtime.appStarted()
        runtime.recover()
        work.drain()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        assertTrue(service.last!!.active)
        runtime.activityCreated()

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertFalse(service.last!!.active)
    }
}
