package com.achappell.hermesrelay

/** Where a user's Interrupt stands. */
internal enum class InterruptStatus {
    None,

    /** Sent; waiting up to the acknowledgement deadline for the turn to end. */
    Pending,

    /**
     * The deadline passed with no terminal event. Local audio stays stopped
     * (the port cancels it before sending); the user is told Hermes has not
     * confirmed, instead of the app claiming the turn stopped.
     */
    Unconfirmed,
}

/**
 * Interrupt as one action with a bounded wait (`ANDROID-VOICE-04`).
 *
 * The port stops local audio first, then sends `session.interrupt`. This class
 * adds what the UI needs on top: one interrupt per turn however often the
 * control is tapped, a 2 s wait for the turn's terminal event, a visible
 * unconfirmed state if it does not come, and "interrupt and listen": when
 * hands-free is off, one tap also starts capture exactly once, after the
 * terminal or the deadline.
 *
 * The 2 s value mirrors iOS `HomeTurnAudioDeadlines.interruptAcknowledgement`.
 */
internal class TurnInterruptCoordinator(
    private val timers: VoiceTimers,
    private val timings: VoiceTimings = VoiceTimings.Default,
    /** Stops local audio and sends the interrupt; true when the frame went out. */
    private val send: (AndroidTurnBinding) -> Boolean,
    private val beginCapture: () -> Unit,
    private val onStatusChange: (InterruptStatus) -> Unit,
) {
    var status: InterruptStatus = InterruptStatus.None
        private set(value) {
            if (field == value) return
            field = value
            onStatusChange(value)
        }

    private var timer: VoiceTimer? = null
    private var listenAfter = false

    /**
     * Interrupts [binding]. Returns false, sending nothing, when an interrupt
     * for this turn already went out or the port could not send one.
     */
    fun interrupt(binding: AndroidTurnBinding, thenListen: Boolean = false): Boolean {
        if (status != InterruptStatus.None) return false
        if (!send(binding)) return false
        listenAfter = thenListen
        status = InterruptStatus.Pending
        timer = timers.after(timings.interruptAcknowledgementMillis) { deadlinePassed() }
        return true
    }

    /** The turn reached a terminal state, however it got there. */
    fun onTurnSettled() {
        timer?.cancel()
        timer = null
        status = InterruptStatus.None
        startListeningIfRequested()
    }

    /** A new turn begins; nothing about the last interrupt applies to it. */
    fun reset() {
        timer?.cancel()
        timer = null
        listenAfter = false
        status = InterruptStatus.None
    }

    private fun deadlinePassed() {
        timer = null
        if (status != InterruptStatus.Pending) return
        status = InterruptStatus.Unconfirmed
        startListeningIfRequested()
    }

    private fun startListeningIfRequested() {
        if (!listenAfter) return
        listenAfter = false
        beginCapture()
    }
}
