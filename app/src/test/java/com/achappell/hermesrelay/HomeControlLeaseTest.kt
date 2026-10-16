package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HomeControlLeaseTest {
    private class Fixture : AutoCloseable {
        val timer = ManualAudioScheduler()
        val turn = AndroidTurnBinding("profile", "conversation", "connection", "turn")
        val expired = mutableListOf<Pair<AndroidTurnBinding, String>>()
        val lease = HomeControlLease(Any(), timer, timer.clock, HomeTurnDeadlines()) { binding, kind ->
            expired += binding to kind
        }

        fun assertExpired(kind: String, binding: AndroidTurnBinding = turn) {
            assertEquals(listOf(binding to kind), expired)
        }

        override fun close() {
            lease.cancel()
            timer.shutdownNow()
        }
    }

    @Test fun repeated_pcm_activity_keeps_one_alarm_until_it_rechecks_the_renewed_deadline() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val alarm = f.timer.awaitScheduled()
            f.timer.advanceMillis(44_000)
            repeat(1_000) { f.lease.activity(f.turn) }
            assertFalse("PCM renewal must not cancel and allocate an alarm per chunk", alarm.isCancelled)
            f.timer.advanceMillis(1_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(44_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun silent_keepalive_turn_expires_at_45_seconds_not_44_and_only_once() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val callback = f.timer.awaitScheduled()
            f.timer.advanceMillis(44_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlIdle")
            callback.runStale()
            f.timer.advanceMillis(1_800_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun running_beats_every_15_seconds_keep_five_minute_turn_alive_until_cancel() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            repeat(20) {
                f.timer.advanceMillis(15_000)
                assertTrue(f.expired.isEmpty())
                f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            }
            assertEquals(TimeUnit.SECONDS.toNanos(300), f.timer.clock.nanoTime())
            f.lease.cancel()
            f.timer.advanceMillis(1_800_000)
            assertTrue(f.expired.isEmpty())
        }
    }

    @Test fun matching_unphased_activity_renews_idle_deadline() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.timer.advanceMillis(44_000)
            f.lease.activity(f.turn)
            f.timer.advanceMillis(44_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun no_capability_expires_at_120_seconds_not_119_despite_activity() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = false)
            repeat(7) {
                f.timer.advanceMillis(15_000)
                f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
                assertTrue(f.expired.isEmpty())
            }
            f.timer.advanceMillis(14_000)
            f.lease.activity(f.turn, HomeTurnAlivePhase.AwaitingInput)
            f.lease.activity(f.turn)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlTerminalMissing")
        }
    }

    @Test fun no_capability_completion_cancel_at_63_seconds_prevents_terminal_deadline() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = false)
            val callback = f.timer.awaitScheduled()
            f.timer.advanceMillis(63_000)
            assertTrue(f.expired.isEmpty())
            f.lease.cancel()
            f.timer.advanceMillis(57_000)
            callback.runStale()
            assertTrue(f.expired.isEmpty())
        }
    }

    @Test fun awaiting_input_suspends_idle_at_300_seconds_but_expires_at_acceptance_backstop() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.timer.advanceMillis(30_000)
            // Pending input reaches this seam as AwaitingInput, not a separate prompt UI state.
            f.lease.activity(f.turn, HomeTurnAlivePhase.AwaitingInput)
            f.timer.advanceMillis(270_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_499_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlBackstop")
        }
    }

    @Test fun continuous_running_beats_cannot_renew_acceptance_backstop() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            repeat(119) {
                f.timer.advanceMillis(15_000)
                assertTrue(f.expired.isEmpty())
                f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            }
            f.timer.advanceMillis(14_000)
            f.lease.activity(f.turn)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlBackstop")
        }
    }

    @Test fun resumed_running_starts_fresh_idle_window_after_input_wait() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.lease.activity(f.turn, HomeTurnAlivePhase.AwaitingInput)
            f.timer.advanceMillis(300_000)
            assertTrue(f.expired.isEmpty())
            f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            f.timer.advanceMillis(44_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun unphased_activity_preserves_input_wait_and_original_backstop() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.lease.activity(f.turn, HomeTurnAlivePhase.AwaitingInput)
            f.timer.advanceMillis(300_000)
            f.lease.activity(f.turn)
            f.timer.advanceMillis(1_499_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlBackstop")
        }
    }

    @Test fun resuming_running_near_backstop_does_not_grant_another_full_idle_window() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.lease.activity(f.turn, HomeTurnAlivePhase.AwaitingInput)
            f.timer.advanceMillis(1_790_000)
            f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            f.timer.advanceMillis(9_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlBackstop")
        }
    }

    @Test fun terminal_or_close_cancel_fences_dequeued_callback_and_later_activity() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val callback = f.timer.awaitScheduled()
            // Both lifecycle callers use cancel; their actual wiring belongs to client tests.
            f.lease.cancel()
            f.lease.cancel()
            f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            f.timer.advanceMillis(45_000)
            callback.runStale()
            f.timer.advanceMillis(1_800_000)
            assertTrue(f.expired.isEmpty())
        }
    }

    @Test fun old_turn_callback_cannot_expire_or_cancel_new_turn() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val oldCallback = f.timer.awaitScheduled()
            f.timer.advanceMillis(30_000)
            val next = f.turn.copy(turnId = "next-turn")
            f.lease.start(next, usesKeepalive = true)
            f.timer.advanceMillis(15_000)
            oldCallback.runStale()
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(29_000)
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlIdle", next)
        }
    }

    @Test fun cancelled_callback_cannot_expire_restarted_identical_binding() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val oldCallback = f.timer.awaitScheduled()
            f.timer.advanceMillis(30_000)
            f.lease.cancel()
            f.lease.start(f.turn, usesKeepalive = true)
            f.timer.advanceMillis(15_000)
            oldCallback.runStale()
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(30_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun superseded_activity_callback_cannot_expire_renewed_lease() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            val oldCallback = f.timer.awaitScheduled()
            f.timer.advanceMillis(30_000)
            f.lease.activity(f.turn, HomeTurnAlivePhase.Running)
            f.timer.advanceMillis(15_000)
            oldCallback.runStale()
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(30_000)
            f.assertExpired("controlIdle")
        }
    }

    @Test fun different_binding_activity_neither_renews_nor_suspends_current_turn() {
        Fixture().use { f ->
            f.lease.start(f.turn, usesKeepalive = true)
            f.timer.advanceMillis(44_000)
            val otherBindings = listOf(
                f.turn.copy(profileId = "other-profile"),
                f.turn.copy(conversationHandle = "other-conversation"),
                f.turn.copy(connectionId = "other-connection"),
                f.turn.copy(turnId = "other-turn"),
            )
            otherBindings.forEach { other ->
                f.lease.activity(other, HomeTurnAlivePhase.Running)
                f.lease.activity(other, HomeTurnAlivePhase.AwaitingInput)
                f.lease.activity(other)
            }
            assertTrue(f.expired.isEmpty())
            f.timer.advanceMillis(1_000)
            f.assertExpired("controlIdle")
        }
    }
}
