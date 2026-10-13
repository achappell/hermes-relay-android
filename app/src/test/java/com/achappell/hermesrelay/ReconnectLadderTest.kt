package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ANDROID-HOME-04`: the paced reconnect ladder. A manual clock and pacer make
 * each attempt's time exact; nothing here waits in real time.
 */
class ReconnectLadderTest {
    private val clock = ManualClock()
    private val pacer = ManualPacer(clock)
    private val journal = RecordingJournal()

    /** Replays [outcomes] (the last repeats) and records the clock at every attempt. */
    private class ScriptedPort(
        private val clock: ManualClock,
        private val outcomes: List<AndroidReconnectOutcome>,
    ) : AndroidClientPort {
        val attemptTimesMillis = mutableListOf<Long>()
        val attemptBudgetsMillis = mutableListOf<Long>()
        var onReconnect: () -> Unit = {}

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
            AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)

        override fun reconnect(): AndroidReconnectOutcome = reconnect(Long.MAX_VALUE)

        override fun reconnect(attemptTimeoutMillis: Long): AndroidReconnectOutcome {
            attemptTimesMillis += clock.nanoTime() / 1_000_000
            attemptBudgetsMillis += attemptTimeoutMillis
            onReconnect()
            return outcomes[(attemptTimesMillis.size - 1).coerceAtMost(outcomes.lastIndex)]
        }
    }

    private val unavailable = AndroidReconnectOutcome.Retryable(
        "Home is unreachable.",
        AndroidHomeUnavailableReason.TransportUnavailable,
    )

    private fun controller(
        port: ScriptedPort,
        policy: ReconnectPolicy = ReconnectPolicy.default,
    ) = AndroidRecoveryController(port, policy, clock, pacer, journal)

    @Test
    fun attempts_follow_the_backoff_schedule_instead_of_burning_through_in_milliseconds() {
        val port = ScriptedPort(
            clock,
            listOf(unavailable, unavailable, AndroidReconnectOutcome.Connected("bridge-2")),
        )

        val state = controller(port).recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        // The first attempt runs at once; the iOS schedule then delays each retry: 0.5 s, 1 s.
        assertEquals(listOf(0L, 500L, 1_500L), port.attemptTimesMillis)
        assertEquals(listOf(10_000L, 10_000L, 10_000L), port.attemptBudgetsMillis)
        assertEquals(listOf(500L, 1_000L), pacer.pauses)
    }

    @Test
    fun the_ladder_ends_failed_with_the_last_real_reason_after_the_whole_schedule() {
        val port = ScriptedPort(clock, listOf(unavailable))

        val state = controller(port).recover()

        assertEquals(AndroidConnectionState.Failed("Home is unreachable.", unavailable.reasonCode), state.connection)
        assertEquals(listOf(0L, 500L, 1_500L, 3_500L, 7_500L, 15_500L), port.attemptTimesMillis)
        assertFalse(state.isRecovering)
    }

    @Test
    fun the_overall_deadline_ends_the_ladder_before_a_late_attempt() {
        val policy = ReconnectPolicy(
            delaysMillis = listOf(30_000L, 30_000L, 30_000L),
            overallMillis = 60_000L,
            attemptMillis = 10_000L,
        )
        val port = ScriptedPort(clock, listOf(unavailable))

        val state = controller(port, policy).recover()

        assertEquals(AndroidConnectionState.Failed("Home is unreachable.", unavailable.reasonCode), state.connection)
        assertEquals("the attempt due at the 60 s mark never runs", listOf(0L, 30_000L), port.attemptTimesMillis)
    }

    @Test
    fun a_transport_failure_reported_as_unrecoverable_is_still_retried() {
        val port = ScriptedPort(
            clock,
            listOf(
                AndroidReconnectOutcome.Unrecoverable(
                    "The approved Home route could not be found.",
                    AndroidHomeUnavailableReason.TransportUnavailable,
                ),
                AndroidReconnectOutcome.Connected("bridge-2"),
            ),
        )

        val state = controller(port).recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        assertEquals(listOf(0L, 500L), port.attemptTimesMillis)
    }

    @Test
    fun a_non_transport_refusal_ends_the_ladder_at_once() {
        val port = ScriptedPort(
            clock,
            listOf(
                AndroidReconnectOutcome.Unrecoverable(
                    "The Home bridge rejected this Device credential.",
                    AndroidHomeUnavailableReason.Unauthorized,
                ),
            ),
        )

        val state = controller(port).recover()

        assertEquals(
            AndroidConnectionState.Failed(
                "The Home bridge rejected this Device credential.",
                AndroidHomeUnavailableReason.Unauthorized,
            ),
            state.connection,
        )
        assertEquals(listOf(0L), port.attemptTimesMillis)
        assertEquals(emptyList<Long>(), pacer.pauses)
    }

    @Test
    fun a_transport_failure_reported_as_unrecoverable_keeps_its_code_at_the_deadline() {
        val port = ScriptedPort(
            clock,
            listOf(AndroidReconnectOutcome.Unrecoverable("Timed out.", AndroidHomeUnavailableReason.TransportTimeout)),
        )
        port.onReconnect = { clock.advanceMillis(60_000L) }

        val state = controller(port).recover()

        assertEquals(
            AndroidConnectionState.Failed("Timed out.", AndroidHomeUnavailableReason.TransportTimeout),
            state.connection,
        )
        assertEquals(listOf(0L), port.attemptTimesMillis)
    }

    @Test
    fun a_returning_network_skips_the_rest_of_the_wait_and_attempts_at_once() {
        val port = ScriptedPort(clock, listOf(unavailable, AndroidReconnectOutcome.Connected("bridge-2")))
        val controller = controller(port)
        var pauses = 0
        // Let attempt 1 fail at once, then return the network during the
        // wait before attempt 2. The second attempt must share its timestamp.
        pacer.onPause = {
            pauses += 1
            if (pauses == 1) controller.retryNow()
        }

        val state = controller.recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
        assertEquals(listOf(0L, 0L), port.attemptTimesMillis)
    }

    @Test
    fun cancelling_during_a_wait_stops_the_ladder_and_leaves_disconnected() {
        val port = ScriptedPort(clock, listOf(unavailable))
        val shown = mutableListOf<AndroidConnectionState>()
        val controller = AndroidRecoveryController(port, ReconnectPolicy.default, clock, pacer, journal) {
            shown += it.connection
        }
        pacer.onPause = { controller.cancelRecovery() }

        val state = controller.recover()

        assertEquals(AndroidConnectionState.Disconnected, state.connection)
        assertFalse(state.isRecovering)
        assertEquals("no attempt after the cancel", listOf(0L), port.attemptTimesMillis)
        assertEquals(1, journal.count("home connect retry cancelled"))
        assertFalse("Reconnecting is never left showing", shown.last() is AndroidConnectionState.Reconnecting)
        assertFalse(controller.isLadderLive)
    }

    @Test
    fun a_cancel_during_an_attempt_that_then_fails_leaves_disconnected() {
        val port = ScriptedPort(clock, listOf(unavailable))
        val controller = controller(port)
        port.onReconnect = { controller.cancelRecovery() }

        val state = controller.recover()

        assertEquals(AndroidConnectionState.Disconnected, state.connection)
        assertEquals(listOf(0L), port.attemptTimesMillis)
    }

    @Test
    fun an_attempt_that_connects_after_the_cancel_is_reported_as_it_is() {
        val port = ScriptedPort(clock, listOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val controller = controller(port)
        port.onReconnect = { controller.cancelRecovery() }

        val state = controller.recover()

        assertEquals("the socket is live, so the state says so", AndroidConnectionState.Connected, state.connection)
    }

    @Test
    fun a_ladder_started_after_a_cancel_runs_normally() {
        val port = ScriptedPort(clock, listOf(AndroidReconnectOutcome.Connected("bridge-2")))
        val controller = controller(port)

        controller.cancelRecovery()
        val state = controller.recover()

        assertEquals(AndroidConnectionState.Connected, state.connection)
    }

    @Test
    fun each_wait_is_journaled_without_content() {
        val port = ScriptedPort(clock, listOf(unavailable, unavailable, AndroidReconnectOutcome.Connected("bridge-2")))

        controller(port).recover()

        assertEquals(
            listOf(
                "home connect retry scheduled attempt=2 delay_ms=500",
                "home connect retry scheduled attempt=3 delay_ms=1000",
            ),
            journal.lines,
        )
    }

    @Test
    fun each_attempt_is_bounded_by_what_is_left_of_the_overall_deadline() {
        val policy = ReconnectPolicy(
            delaysMillis = listOf(500L, 8_000L),
            overallMillis = 10_000L,
            attemptMillis = 10_000L,
        )
        val port = ScriptedPort(clock, listOf(unavailable))

        controller(port, policy).recover()

        assertEquals(listOf(0L, 500L, 8_500L), port.attemptTimesMillis)
        assertEquals(listOf(10_000L, 9_500L, 1_500L), port.attemptBudgetsMillis)
    }

    @Test
    fun an_ambiguous_claim_failure_is_never_retried_with_a_second_claim() {
        val port = ScriptedPort(
            clock,
            listOf(
                AndroidReconnectOutcome.Unrecoverable(
                    "Home could not be reached to start a conversation.",
                    AndroidHomeUnavailableReason.TransportUnavailable,
                    ambiguousClaim = true,
                ),
                AndroidReconnectOutcome.Connected("bridge-2"),
            ),
        )

        val state = controller(port).recover()

        assertEquals(listOf(0L), port.attemptTimesMillis)
        assertTrue(state.connection is AndroidConnectionState.Failed)
    }
}
