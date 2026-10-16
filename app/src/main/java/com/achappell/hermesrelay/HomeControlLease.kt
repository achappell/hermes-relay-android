package com.achappell.hermesrelay

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Home/iOS control-channel limits, independent of audio and background retention. */
internal data class HomeTurnDeadlines(
    val controlIdleMillis: Long = 45_000,
    val controlTerminalMillis: Long = 120_000,
    val controlBackstopMillis: Long = 1_800_000,
) {
    init {
        require(controlIdleMillis > 0 && controlTerminalMillis > 0 && controlBackstopMillis > 0)
    }
}

/** All calls, including the scheduled callback, serialize on the client's inbound lock. */
internal class HomeControlLease(
    private val lock: Any,
    private val scheduler: ScheduledExecutorService,
    private val clock: MonotonicClock,
    private val deadlines: HomeTurnDeadlines,
    private val onExpired: (AndroidTurnBinding, String) -> Unit,
) {
    private var binding: AndroidTurnBinding? = null
    private var keepalive = false
    private var hardDeadline = 0L
    private var activityAt = 0L
    private var awaitingInput = false
    private var structuredPromptPending = false
    private var scheduled: ScheduledFuture<*>? = null
    private var generation = 0L
    private var scheduledAt = 0L
    private val idleNanos = TimeUnit.MILLISECONDS.toNanos(deadlines.controlIdleMillis)

    fun start(turn: AndroidTurnBinding, usesKeepalive: Boolean) {
        cancel()
        binding = turn
        keepalive = usesKeepalive
        activityAt = clock.nanoTime()
        hardDeadline = activityAt + TimeUnit.MILLISECONDS.toNanos(
            if (keepalive) deadlines.controlBackstopMillis else deadlines.controlTerminalMillis,
        )
        awaitingInput = false
        structuredPromptPending = false
        schedule()
    }

    fun activity(turn: AndroidTurnBinding, phase: HomeTurnAlivePhase? = null) {
        if (binding != turn || !keepalive) return
        activityAt = clock.nanoTime()
        if (phase != null) awaitingInput = phase == HomeTurnAlivePhase.AwaitingInput
        // PCM and text activity only update the timestamp. The outstanding alarm
        // rechecks it; only leaving an input wait can bring the deadline earlier.
        if (deadline() < scheduledAt) schedule()
    }

    fun promptPending(turn: AndroidTurnBinding) {
        if (binding != turn) return
        structuredPromptPending = true
        activity(turn)
    }

    fun cancel() {
        generation++
        scheduled?.cancel(false)
        scheduled = null
        binding = null
    }

    private fun deadline(): Long =
        if (keepalive && !awaitingInput && !structuredPromptPending) {
            minOf(hardDeadline, activityAt + idleNanos)
        } else hardDeadline

    private fun schedule() {
        scheduled?.cancel(false)
        val token = ++generation
        val turn = binding ?: return
        val due = deadline()
        scheduledAt = due
        scheduled = scheduler.schedule({
            synchronized(lock) {
                if (generation != token || binding != turn) return@synchronized
                if (clock.nanoTime() < deadline()) {
                    schedule()
                    return@synchronized
                }
                val kind = when {
                    !keepalive -> "controlTerminalMissing"
                    hardDeadline <= deadline() -> "controlBackstop"
                    else -> "controlIdle"
                }
                cancel()
                onExpired(turn, kind)
            }
        }, (due - clock.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }
}
