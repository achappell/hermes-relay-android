package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * `ANDROID-HOME-07`: one Home runtime per process, bound to what the user sees.
 *
 * Everything runs inline on the test thread (no real thread, no sleep), so the
 * outcome does not depend on scheduling.
 */
class HomeRuntimeTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val binding = AndroidTurnBinding(profile.id, "conversation-1", "turn-1")

    private class Fixture(initiation: AndroidInitiationResult) {
        val port = FakeHomePort(initiation)
        val store = InMemoryAndroidHistoryStore()
        val journal = RecordingJournal()
        val box = HomeRuntimeBox(journal) { onTornDown ->
            HomeRuntime(
                clientPort = port,
                speechInput = null,
                historyStore = store,
                postToMain = { it.run() },
                workExecutor = InlineExecutorService(),
                onTornDown = onTornDown,
                journal = journal,
            )
        }
    }

    private fun accepted() = AndroidInitiationResult.Accepted(binding)

    @Test
    fun consecutive_activity_resolves_return_the_same_runtime() {
        val fixture = Fixture(accepted())

        val first = fixture.box.resolve()
        val second = fixture.box.resolve()

        assertSame(first, second)
        assertEquals("one runtime per process", 1, fixture.box.createdCount)
    }

    @Test
    fun the_screen_and_the_teardown_rules_read_the_same_turn_state() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))

        // The state the screen binds to is the state `hasAcceptedTurn` (and so
        // the destroy rule) is computed from: one field, not a copy.
        assertTrue(runtime.initiationState is AndroidInitiationState.Accepted)
        assertTrue(runtime.hasAcceptedTurn)
        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done"))
        assertTrue(runtime.turnState.isTerminal)
        assertFalse(runtime.hasAcceptedTurn)
    }

    @Test
    fun recreation_mid_reply_keeps_the_socket_the_audio_and_the_turn() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Partial answer"))
        val before = runtime.turnState

        // Rotation: the old Activity is destroyed for recreation, a new one resolves.
        runtime.activityDestroyed(isFinishing = false, isChangingConfigurations = true)
        val recreated = fixture.box.resolve()
        recreated.activityCreated()

        assertSame(runtime, recreated)
        assertEquals(0, fixture.port.closeCount)
        assertEquals(1, fixture.box.createdCount)
        assertEquals(before, recreated.turnState)
        assertEquals("Partial answer", recreated.turnState.responseText)
        assertTrue(recreated.initiationState is AndroidInitiationState.Accepted)
        assertEquals(1, fixture.port.turnObservations)
    }

    @Test
    fun recreation_keeps_the_unconfirmed_turn() {
        val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("hello"))
        val fixture = Fixture(
            AndroidInitiationResult.Uncertain(request, AndroidHomeUnavailableReason.TransportTimeout),
        )
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        val retained = runtime.recoveryController.state.unconfirmedTurn

        runtime.activityDestroyed(isFinishing = false, isChangingConfigurations = true)
        val recreated = fixture.box.resolve()

        assertEquals(AndroidUnconfirmedTurn(null, request), retained)
        assertEquals(retained, recreated.recoveryController.state.unconfirmedTurn)
        assertEquals(0, fixture.port.closeCount)
    }

    @Test
    fun a_system_destroy_of_a_backgrounded_activity_keeps_the_runtime() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()

        runtime.activityDestroyed(isFinishing = false, isChangingConfigurations = false)

        assertEquals(0, fixture.port.closeCount)
        assertSame(runtime, fixture.box.resolve())
    }

    @Test
    fun destroy_with_a_reply_in_flight_does_not_close_the_client() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.emit(AndroidNormalizedEvent.ResponseTextDelta(binding, "Streaming"))

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertEquals(0, fixture.port.closeCount)
        assertSame(runtime, fixture.box.resolve())
    }

    @Test
    fun a_reply_that_ends_after_the_activity_is_gone_tears_the_runtime_down_once() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done"))

        assertEquals(1, fixture.port.closeCount)
        assertEquals(1, fixture.port.connectionObservationsCancelled)
        // The next screen gets a fresh runtime, not the closed one.
        val next = fixture.box.resolve()
        assertNotSame(runtime, next)
        assertEquals(2, fixture.box.createdCount)
    }

    @Test
    fun a_screen_that_returns_before_the_reply_ends_cancels_the_pending_teardown() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
        fixture.box.resolve().activityCreated()

        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done"))

        assertEquals(0, fixture.port.closeCount)
        assertSame(runtime, fixture.box.resolve())
    }

    @Test
    fun destroy_with_nothing_in_flight_tears_down_exactly_once() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertEquals(1, fixture.port.closeCount)
        assertEquals(1, fixture.port.connectionObservationsCancelled)
    }

    @Test
    fun a_settled_turn_is_recorded_once_however_many_screens_come_and_go() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.recorder!!.open(profile.id)
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "The answer"))
        val settled = runtime.settleRevision

        runtime.activityDestroyed(isFinishing = false, isChangingConfigurations = true)
        fixture.box.resolve().activityCreated()

        assertEquals(1, settled)
        assertEquals(settled, runtime.settleRevision)
        val assistantEntries = fixture.store.load(profile.id).entries
            .count { it.role == AndroidTranscriptRole.Assistant }
        assertEquals(1, assistantEntries)
    }

    @Test
    fun a_lost_connection_retains_the_in_flight_turn_and_reconnects_only_while_a_screen_is_attached() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        var foregroundReconnects = 0
        runtime.reconnectIfForeground = { foregroundReconnects += 1 }
        runtime.initiate(AndroidTurnInput.Typed("hello"))

        fixture.port.loseConnection("Transport closed.")

        assertEquals(1, foregroundReconnects)
        val retained = runtime.recoveryState.unconfirmedTurn
        assertEquals(binding, retained?.binding)
        assertEquals(AndroidTurnInput.Typed("hello"), retained?.request?.input)

        // The screen went away (its effect cleared the callback): the loss is
        // still recorded, but nothing reconnects on the runtime's own initiative.
        runtime.reconnectIfForeground = {}
        fixture.port.loseConnection("Transport closed again.")

        assertEquals(1, foregroundReconnects)
        assertEquals(retained, runtime.recoveryState.unconfirmedTurn)
    }

    @Test
    fun runtime_created_is_journaled_once_across_rotations() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()

        repeat(2) {
            runtime.activityDestroyed(isFinishing = false, isChangingConfigurations = true)
            fixture.box.resolve().activityCreated()
        }

        assertEquals(1, fixture.journal.count("runtime created"))
        assertEquals(0, fixture.journal.count("runtime teardown"))
        assertEquals(3, fixture.journal.count("app activity created"))
        assertEquals(2, fixture.journal.count("app activity destroyed finishing=false config_change=true"))
    }

    @Test
    fun teardown_names_its_initiator_in_the_journal() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
        fixture.port.emit(AndroidNormalizedEvent.TurnCompleted(binding, "Done"))

        val lines = fixture.journal.lines
        assertTrue(lines.contains("runtime teardown deferred reply=inFlight"))
        assertTrue(lines.contains("runtime teardown reason=replySettled"))
        assertEquals(
            "a teardown line is written exactly once",
            1,
            lines.count { it.startsWith("runtime teardown reason=") },
        )
        // Journal lines are fixed names and flags: no prompt, reply or handle.
        val text = lines.joinToString("\n")
        listOf("hello", "Done", binding.conversationHandle, binding.turnId).forEach {
            assertFalse("leaked $it", text.contains(it))
        }
    }

    /** `ANDROID-HOME-05`: a reply waiting for late audio is still in flight. */
    @Test
    fun a_reply_waiting_for_late_audio_defers_teardown_until_the_audio_outcome() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        runtime.initiate(AndroidTurnInput.Typed("hello"))
        fixture.port.emit(AndroidNormalizedEvent.TextCompleted(binding, "Answer"))

        assertEquals(AndroidTurnPhase.Buffering, runtime.turnState.phase)
        assertTrue(runtime.hasAcceptedTurn)
        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)
        assertEquals("waiting for audio is still in flight", 0, fixture.port.closeCount)

        fixture.port.emit(AndroidNormalizedEvent.AudioFailed(binding, "transport_timeout"))

        assertEquals(1, fixture.port.closeCount)
        assertTrue(fixture.journal.lines.contains("runtime teardown reason=replySettled"))
    }

    @Test
    fun an_overlapping_second_activity_keeps_the_runtime_when_the_first_finishes() {
        val fixture = Fixture(accepted())
        val runtime = fixture.box.resolve()
        runtime.activityCreated()
        fixture.box.resolve().activityCreated()

        runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false)

        assertEquals(0, fixture.port.closeCount)
        assertSame(runtime, fixture.box.resolve())
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

    private class FakeHomePort(private val initiation: AndroidInitiationResult) : AndroidClientPort {
        var closeCount = 0
        var turnObservations = 0
        var connectionObservationsCancelled = 0
        private var turnListener: ((AndroidNormalizedEvent) -> Unit)? = null
        private var connectionListener: ((AndroidNormalizedEvent.Disconnected) -> Unit)? = null

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = AndroidProfile("amanda", "Amanda"),
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest) = initiation

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation {
            turnObservations += 1
            turnListener = onEvent
            return AndroidTurnObservation { turnListener = null }
        }

        override fun observeConnection(
            onEvent: (AndroidNormalizedEvent.Disconnected) -> Unit,
        ): AndroidTurnObservation {
            connectionListener = onEvent
            return AndroidTurnObservation {
                connectionListener = null
                connectionObservationsCancelled += 1
            }
        }

        override fun hasActiveTurn(): Boolean = false

        override fun close() {
            closeCount += 1
        }

        fun emit(event: AndroidNormalizedEvent) {
            checkNotNull(turnListener) { "no turn is being observed" }(event)
        }

        fun loseConnection(reason: String) {
            checkNotNull(connectionListener) { "connection is not observed" }(
                AndroidNormalizedEvent.Disconnected(connectionId = "connection-1", reason = reason),
            )
        }
    }
}
