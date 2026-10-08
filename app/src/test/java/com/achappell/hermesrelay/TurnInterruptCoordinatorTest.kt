package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ANDROID-VOICE-04`: one interrupt per turn, a 2 s wait for the terminal, then a visible unconfirmed state. */
class TurnInterruptCoordinatorTest {
    private val binding = AndroidTurnBinding("amanda", "conversation-1", "turn-1")

    private class Rig(sends: Boolean = true) {
        val timers = ManualVoiceTimers()
        val sent = mutableListOf<AndroidTurnBinding>()
        val statuses = mutableListOf<InterruptStatus>()
        var captures = 0
        val coordinator = TurnInterruptCoordinator(
            timers = timers,
            send = { binding ->
                sent += binding
                sends
            },
            beginCapture = { captures += 1 },
            onStatusChange = { statuses += it },
        )
    }

    @Test
    fun the_wait_is_two_seconds() {
        assertEquals(2_000L, VoiceTimings.Default.interruptAcknowledgementMillis)
    }

    @Test
    fun a_terminal_within_two_seconds_completes_without_an_unconfirmed_state() {
        val rig = Rig()

        assertTrue(rig.coordinator.interrupt(binding))
        rig.timers.advanceBy(1_999)
        rig.coordinator.onTurnSettled()
        rig.timers.advanceBy(10_000)

        assertEquals(listOf(InterruptStatus.Pending, InterruptStatus.None), rig.statuses)
        assertEquals(0, rig.timers.pendingCount)
    }

    @Test
    fun no_terminal_at_two_seconds_shows_the_unconfirmed_state_and_not_before() {
        val rig = Rig()
        rig.coordinator.interrupt(binding)

        rig.timers.advanceBy(1_999)
        assertEquals(InterruptStatus.Pending, rig.coordinator.status)

        rig.timers.advanceBy(1)
        assertEquals(InterruptStatus.Unconfirmed, rig.coordinator.status)
    }

    @Test
    fun a_late_terminal_clears_the_unconfirmed_state() {
        val rig = Rig()
        rig.coordinator.interrupt(binding)
        rig.timers.advanceBy(2_000)

        rig.coordinator.onTurnSettled()

        assertEquals(InterruptStatus.None, rig.coordinator.status)
    }

    @Test
    fun a_second_tap_never_sends_a_second_interrupt() {
        val rig = Rig()

        assertTrue(rig.coordinator.interrupt(binding))
        assertFalse(rig.coordinator.interrupt(binding))
        rig.timers.advanceBy(2_000)
        assertFalse("an unconfirmed interrupt was sent again", rig.coordinator.interrupt(binding))

        assertEquals(1, rig.sent.size)
    }

    @Test
    fun a_port_that_cannot_send_leaves_no_state_behind() {
        val rig = Rig(sends = false)

        assertFalse(rig.coordinator.interrupt(binding, thenListen = true))
        rig.coordinator.onTurnSettled()

        assertEquals(InterruptStatus.None, rig.coordinator.status)
        assertEquals("capture started for an interrupt that was never sent", 0, rig.captures)
        assertEquals(0, rig.timers.pendingCount)
    }

    @Test
    fun interrupt_and_listen_starts_capture_once_after_the_terminal() {
        val rig = Rig()
        rig.coordinator.interrupt(binding, thenListen = true)
        assertEquals("capture started before the terminal", 0, rig.captures)

        rig.coordinator.onTurnSettled()
        rig.coordinator.onTurnSettled()
        rig.timers.advanceBy(10_000)

        assertEquals(1, rig.captures)
    }

    @Test
    fun interrupt_and_listen_starts_capture_once_at_the_deadline_and_not_again_at_the_terminal() {
        val rig = Rig()
        rig.coordinator.interrupt(binding, thenListen = true)

        rig.timers.advanceBy(2_000)
        assertEquals(1, rig.captures)

        rig.coordinator.onTurnSettled()
        assertEquals("the late terminal started a second capture", 1, rig.captures)
    }

    @Test
    fun a_plain_interrupt_never_starts_capture() {
        val rig = Rig()
        rig.coordinator.interrupt(binding)

        rig.coordinator.onTurnSettled()
        rig.timers.advanceBy(10_000)

        assertEquals(0, rig.captures)
    }

    @Test
    fun a_new_turn_discards_the_previous_interrupt() {
        val rig = Rig()
        rig.coordinator.interrupt(binding, thenListen = true)

        rig.coordinator.reset()
        rig.timers.advanceBy(10_000)
        rig.coordinator.onTurnSettled()

        assertEquals(InterruptStatus.None, rig.coordinator.status)
        assertEquals(0, rig.captures)
        assertTrue("the next turn can be interrupted", rig.coordinator.interrupt(binding))
    }
}
