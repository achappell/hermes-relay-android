package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * `ANDROID-STD-01` slice 1: how the one runtime treats a Standard Profile.
 *
 * Everything runs inline on the test thread against a fake Standard port, so
 * no network or scheduling is involved. The fake records every call so a test
 * can prove what was *not* done (a replay, a Home fallback, a cleared turn).
 */
class StandardRuntimeTest {
    private val profile = AndroidProfile("std-1", "Standard · hermes.example")
    private val binding = AndroidTurnBinding(profile.id, "standard-1", "conn-1", "turn-1")

    private class Fixture(
        initiation: AndroidInitiationResult,
        var mode: RelayProfileMode? = RelayProfileMode.Standard,
        newConversation: AndroidNewConversationResult = AndroidNewConversationResult.Created,
    ) {
        val port = FakeStandardPort(initiation, newConversation)
        val store = InMemoryAndroidHistoryStore()
        val journal = RecordingJournal()
        val runtime = HomeRuntime(
            clientPort = port,
            speechInput = null,
            historyStore = store,
            postToMain = { it.run() },
            workExecutor = InlineExecutorService(),
            journal = journal,
            selectedMode = { mode },
            newConversationLabel = "New conversation",
        ).also {
            it.activityCreated()
            it.recorder?.open("std-key")
        }
    }

    private fun accepted() = AndroidInitiationResult.Accepted(binding)

    private fun uncertain() = AndroidInitiationResult.Uncertain(
        AndroidTurnRequest(profile, AndroidTurnInput.Typed("hello")),
        AndroidHomeUnavailableReason.TransportTimeout,
    )

    @Test
    fun resend_and_discard_are_ignored_in_standard_mode_and_never_replay() {
        val fixture = Fixture(uncertain())
        val runtime = fixture.runtime
        runtime.recover()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        val retained = runtime.recoveryController.state.unconfirmedTurn
        assertTrue("the uncertain turn is retained", retained != null)

        runtime.resendUnconfirmedTurn()
        runtime.discardUnconfirmedTurn()

        assertEquals("a Standard turn is never replayed", 1, fixture.port.beginTurns)
        assertEquals(retained, runtime.recoveryController.state.unconfirmedTurn)
        assertTrue(runtime.isConversationBusy)
        assertEquals(1, fixture.journal.count("standard resend ignored"))
        assertEquals(1, fixture.journal.count("standard discard ignored"))
    }

    @Test
    fun resend_is_not_blocked_in_home_mode() {
        val fixture = Fixture(uncertain(), mode = RelayProfileMode.HomeBridge)
        val runtime = fixture.runtime
        runtime.recover()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        // A lost delivery marks the connection down; reconnect before the explicit resend.
        runtime.recover()

        runtime.resendUnconfirmedTurn()

        assertEquals("Home keeps its explicit resend", 2, fixture.port.beginTurns)
    }

    @Test
    fun reconnect_does_not_clear_an_uncertain_standard_turn() {
        val fixture = Fixture(uncertain())
        val runtime = fixture.runtime
        runtime.recover()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.uncertain = true

        runtime.recover()

        assertTrue(runtime.recoveryState.hasUnconfirmedTurn)
        assertTrue(runtime.isConversationBusy)
    }

    @Test
    fun new_conversation_success_clears_uncertainty_and_records_a_divider() {
        val fixture = Fixture(uncertain())
        val runtime = fixture.runtime
        runtime.recover()
        runtime.recorder!!.recordUserTurn("earlier question")
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.uncertain = true
        fixture.port.emitFinishing(true)

        runtime.startNewConversation()

        assertEquals(1, fixture.port.newConversations)
        assertNull(runtime.recoveryController.state.unconfirmedTurn)
        assertFalse(runtime.recoveryState.hasUnconfirmedTurn)
        assertFalse(runtime.standardFinishing)
        assertFalse(runtime.isConversationBusy)
        assertEquals(AndroidInitiationState.Idle, runtime.initiationState)
        assertEquals(StandardNewConversationState.Idle, runtime.newConversationState)
        val entries = runtime.recorder!!.history.entries
        assertEquals("earlier history stays visible", "earlier question", entries.first().text)
        assertEquals(AndroidTranscriptRole.Divider, entries.last().role)
        assertEquals("New conversation", entries.last().text)
        assertEquals(1, fixture.journal.count("standard new_conversation applied"))
    }

    @Test
    fun a_failed_new_conversation_leaves_the_turn_uncertain_and_sending_blocked() {
        val fixture = Fixture(
            uncertain(),
            newConversation = AndroidNewConversationResult.Failed(
                AndroidHomeUnavailableReason.TransportUnavailable,
            ),
        )
        val runtime = fixture.runtime
        runtime.recover()
        runtime.recorder!!.recordUserTurn("earlier question")
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.uncertain = true
        val retained = runtime.recoveryController.state.unconfirmedTurn

        runtime.startNewConversation()

        assertEquals(
            StandardNewConversationState.Failed(AndroidHomeUnavailableReason.TransportUnavailable),
            runtime.newConversationState,
        )
        assertEquals(retained, runtime.recoveryController.state.unconfirmedTurn)
        assertTrue(runtime.isConversationBusy)
        assertEquals(
            "no divider on failure",
            listOf(AndroidTranscriptRole.User),
            runtime.recorder!!.history.entries.map { it.role },
        )
        assertEquals(1, fixture.journal.count("standard new_conversation kept_state"))

        runtime.dismissNewConversationFailure()
        assertEquals(StandardNewConversationState.Idle, runtime.newConversationState)
    }

    @Test
    fun new_conversation_is_not_available_in_home_mode_or_during_a_live_turn() {
        val home = Fixture(accepted(), mode = RelayProfileMode.HomeBridge)
        home.runtime.recover()
        home.runtime.startNewConversation()
        assertEquals(0, home.port.newConversations)

        val live = Fixture(accepted())
        live.runtime.recover()
        live.runtime.initiate(AndroidTurnInput.Typed("hello"))
        assertTrue(live.runtime.hasAcceptedTurn)
        live.runtime.startNewConversation()
        assertEquals("a live turn must be stopped first", 0, live.port.newConversations)
    }

    @Test
    fun new_conversation_adopts_a_socket_it_had_to_open() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)

        runtime.startNewConversation()

        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(1, fixture.port.newConversations)
    }

    @Test
    fun finishing_blocks_the_conversation_until_hermes_ends_the_turn() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        runtime.recover()

        fixture.port.emitFinishing(true)
        assertTrue(runtime.standardFinishing)
        assertTrue(runtime.isConversationBusy)

        fixture.port.emitFinishing(false)
        assertFalse(runtime.standardFinishing)
        assertFalse(runtime.isConversationBusy)
    }

    @Test
    fun a_fresh_standard_session_after_connect_records_one_divider_when_history_exists() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        runtime.recorder!!.recordUserTurn("earlier question")
        fixture.port.reconnectOutcome = {
            AndroidReconnectOutcome.Connected(connectionId = "c1", sessionStartedFresh = true)
        }

        runtime.recover()

        val entries = runtime.recorder!!.history.entries
        assertEquals(AndroidTranscriptRole.Divider, entries.last().role)
        assertEquals(1, fixture.journal.count("standard fresh session divider=true"))

        // A resumed connection is the same Hermes context: no divider.
        fixture.port.reconnectOutcome = {
            AndroidReconnectOutcome.Connected(connectionId = "c2", sessionStartedFresh = false)
        }
        runtime.recover()
        assertEquals(1, runtime.recorder!!.history.entries.count { it.role == AndroidTranscriptRole.Divider })
    }

    @Test
    fun a_fresh_session_with_no_history_records_no_leading_divider() {
        val fixture = Fixture(accepted())
        fixture.port.reconnectOutcome = {
            AndroidReconnectOutcome.Connected(connectionId = "c1", sessionStartedFresh = true)
        }

        fixture.runtime.recover()

        assertTrue(fixture.runtime.recorder!!.history.entries.isEmpty())
    }

    @Test
    fun a_home_connect_never_records_a_standard_divider() {
        val fixture = Fixture(accepted(), mode = RelayProfileMode.HomeBridge)
        fixture.runtime.recorder!!.recordUserTurn("earlier question")
        fixture.port.reconnectOutcome = {
            AndroidReconnectOutcome.Connected(connectionId = "c1", sessionStartedFresh = true)
        }

        fixture.runtime.recover()

        assertEquals(
            listOf(AndroidTranscriptRole.User),
            fixture.runtime.recorder!!.history.entries.map { it.role },
        )
    }

    @Test
    fun a_selection_change_into_standard_ends_the_previous_transport_and_resets_the_turn() {
        val fixture = Fixture(accepted(), mode = RelayProfileMode.HomeBridge)
        val runtime = fixture.runtime
        runtime.profileSelectionChanged("home-1", RelayProfileMode.HomeBridge)
        runtime.recover()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done"))

        fixture.mode = RelayProfileMode.Standard
        runtime.profileSelectionChanged("std-1", RelayProfileMode.Standard)

        assertEquals(listOf("endSession"), fixture.port.calls)
        assertEquals(AndroidConnectionState.Disconnected, runtime.recoveryState.connection)
        assertEquals(AndroidInitiationState.Idle, runtime.initiationState)
        assertEquals(1, fixture.journal.count("standard selection changed reset=true"))
    }

    @Test
    fun a_home_to_home_selection_change_keeps_its_existing_behavior() {
        val fixture = Fixture(accepted(), mode = RelayProfileMode.HomeBridge)
        val runtime = fixture.runtime
        runtime.profileSelectionChanged("home-1", RelayProfileMode.HomeBridge)
        runtime.recover()

        runtime.profileSelectionChanged("home-2", RelayProfileMode.HomeBridge)

        assertTrue(fixture.port.calls.isEmpty())
        assertEquals(AndroidConnectionState.Connected, runtime.recoveryState.connection)
        assertEquals(0, fixture.journal.count("standard selection changed"))
    }

    @Test
    fun the_first_observed_selection_never_resets_anything() {
        val fixture = Fixture(accepted())
        fixture.runtime.recover()

        fixture.runtime.profileSelectionChanged("std-1", RelayProfileMode.Standard)

        assertTrue(fixture.port.calls.isEmpty())
        assertEquals(AndroidConnectionState.Connected, fixture.runtime.recoveryState.connection)
    }

    @Test
    fun an_active_turn_makes_the_conversation_busy() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        runtime.recover()
        assertFalse(runtime.isConversationBusy)

        runtime.initiate(AndroidTurnInput.Typed("hello"))
        assertTrue(runtime.isConversationBusy)

        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done", textOnly = true))
        assertFalse(runtime.isConversationBusy)
    }

    @Test
    fun a_standard_text_turn_completes_without_audio() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        runtime.recover()
        runtime.initiate(AndroidTurnInput.Typed("hello"))

        fixture.port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Hi"))
        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Hi there", textOnly = true))

        assertEquals(AndroidTurnPhase.Complete, runtime.turnState.phase)
        assertEquals("Hi there", runtime.turnState.responseText)
        assertEquals(
            listOf(AndroidTranscriptRole.User, AndroidTranscriptRole.Assistant),
            runtime.recorder!!.history.entries.map { it.role },
        )
    }

    @Test
    fun prompt_recall_belongs_to_one_history_identity() {
        val fixture = Fixture(accepted())
        val runtime = fixture.runtime
        runtime.openPromptHistory("std-a")
        runtime.promptHistory = runtime.promptHistory.record("first identity prompt")

        runtime.openPromptHistory("std-b")
        assertTrue("another identity's prompts leaked", runtime.promptHistory.isEmpty)
        runtime.promptHistory = runtime.promptHistory.record("second identity prompt")

        runtime.openPromptHistory("std-a")
        assertEquals(1, runtime.promptHistory.size)
        assertEquals("first identity prompt", runtime.promptHistory.previous("").second)

        runtime.openPromptHistory(null)
        assertTrue(runtime.promptHistory.isEmpty)
    }

    @Test
    fun voice_capture_is_unauthorized_for_a_standard_profile() {
        val speech = FakeSpeechInput()
        val port = FakeStandardPort(accepted(), AndroidNewConversationResult.Created)
        var mode: RelayProfileMode? = RelayProfileMode.Standard
        val runtime = HomeRuntime(
            clientPort = port,
            speechInput = speech,
            historyStore = InMemoryAndroidHistoryStore(),
            postToMain = { it.run() },
            workExecutor = InlineExecutorService(),
            selectedMode = { mode },
        )
        runtime.activityCreated()
        runtime.recover()
        val capture = checkNotNull(runtime.captureController)

        assertEquals(AndroidCaptureBlock.ProfileUnavailable, capture.blockingReason())

        mode = RelayProfileMode.HomeBridge
        assertNull("Home voice is unchanged", capture.blockingReason())
    }

    private class FakeStandardPort(
        private val initiation: AndroidInitiationResult,
        private val newConversation: AndroidNewConversationResult,
    ) : AndroidClientPort, AndroidStandardSession {
        val calls = mutableListOf<String>()
        var beginTurns = 0
        var newConversations = 0
        var uncertain = false
        var finishing = false
        var reconnectOutcome: () -> AndroidReconnectOutcome = {
            AndroidReconnectOutcome.Connected(connectionId = "conn-1")
        }
        private var turnListener: ((AndroidNormalizedEvent) -> Unit)? = null
        private var finishingListener: ((Boolean) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("std-1", "Standard"),
            authorizationState = AndroidAuthorizationState.Verified,
            mode = RelayProfileMode.Standard,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            beginTurns += 1
            return initiation
        }

        override fun reconnect(): AndroidReconnectOutcome = reconnectOutcome()

        override fun endSession() {
            calls += "endSession"
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            turnListener = onEvent
            return AndroidTurnObservation { turnListener = null }
        }

        override fun hasActiveTurn(): Boolean = false

        override fun newConversation(): AndroidNewConversationResult {
            newConversations += 1
            if (newConversation is AndroidNewConversationResult.Created) {
                uncertain = false
                finishing = false
            }
            return newConversation
        }

        override fun isFinishingPreviousResponse(): Boolean = finishing

        override fun hasUncertainTurn(): Boolean = uncertain

        override fun observeFinishing(onChange: (Boolean) -> Unit): AndroidTurnObservation {
            finishingListener = onChange
            return AndroidTurnObservation { finishingListener = null }
        }

        fun emit(event: AndroidNormalizedEvent) {
            checkNotNull(turnListener) { "no turn is being observed" }(event)
        }

        fun emitFinishing(value: Boolean) {
            finishing = value
            checkNotNull(finishingListener)(value)
        }
    }

    private class InlineExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown() = shutdown

        override fun isTerminated() = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = shutdown
    }
}
