package com.achappell.hermesrelay

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The paced reconnect schedule (`ANDROID-HOME-04`), mirroring iOS
 * `ReconnectPolicy.default` and `HomeOperationDeadlines.reconnectOverall`.
 *
 * The first attempt runs at once. Each entry of [delaysMillis] is the wait
 * before one retry, matching iOS `ReconnectPolicy.default` (retries after 0.5,
 * 1, 2, 4 and 8 seconds). The overall and per-attempt deadlines are injectable
 * too; the adapter enforces [attemptMillis] across connection and ready
 * handshake.
 */
internal data class ReconnectPolicy(
    val delaysMillis: List<Long>,
    val overallMillis: Long,
    val attemptMillis: Long,
) {
    /** The first attempt plus one retry for each scheduled delay. */
    val maxAttempts: Int
        get() = delaysMillis.size + 1

    /** Wait before the given 1-based [attempt]; the first attempt has none. */
    fun delayBefore(attempt: Int): Long? = delaysMillis.getOrNull(attempt - 2)

    companion object {
        val default = ReconnectPolicy(
            delaysMillis = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L),
            overallMillis = 60_000L,
            attemptMillis = 10_000L,
        )
    }
}

/**
 * The wait between reconnect attempts. Unlike a bare sleep it can be cut short:
 * [wake] ends the current (or next) [pause] early, which is how a cancelled
 * ladder stops at once and how a returning network skips the rest of a backoff
 * step.
 */
internal interface RecoveryPacer {
    /** Blocks for up to [millis]; returns early after [wake]. */
    @Throws(InterruptedException::class)
    fun pause(millis: Long)

    /** Ends a pause early. A wake with no pause pending makes the next pause return at once. */
    fun wake()

    /** Forgets a wake that nobody consumed, so a new ladder starts clean. */
    fun reset()
}

/** Production [RecoveryPacer]: a lock and condition, with one remembered wake. */
internal class LockRecoveryPacer : RecoveryPacer {
    private val lock = ReentrantLock()
    private val woken = lock.newCondition()
    private var signalled = false

    override fun pause(millis: Long) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(millis)
        lock.withLock {
            while (!signalled && remaining > 0) {
                remaining = woken.awaitNanos(remaining)
            }
            signalled = false
        }
    }

    override fun wake() {
        lock.withLock {
            signalled = true
            woken.signalAll()
        }
    }

    override fun reset() {
        lock.withLock { signalled = false }
    }
}

/** Events from the platform's default network (`ANDROID-HOME-04`, extended by `ANDROID-NET-01`). */
internal interface NetworkEvents {
    /** Starts delivering events to [listener]; cancel the returned handle to stop. */
    fun observe(listener: NetworkListener): AndroidTurnObservation

    companion object {
        /** No platform source: nothing is ever delivered. */
        val None: NetworkEvents = object : NetworkEvents {
            override fun observe(listener: NetworkListener) = AndroidTurnObservation {}
        }
    }
}

internal interface NetworkListener {
    /** A default network became available (or was already, at registration). */
    fun onAvailable()
}

/**
 * Orders the foreground and background inputs of the Home runtime
 * (`ANDROID-HOME-04`).
 *
 * Inputs arrive on the main thread, one at a time, and each advances
 * [generation]. Work that runs later on the single worker thread captures the
 * generation it was queued under and drops itself when [isCurrent] is false,
 * so a superseded stop cannot close what a newer start reopened and a
 * superseded start cannot reconnect after a newer stop. Only the newest input
 * wins.
 */
internal class HomeLifecycleCoordinator {
    private val counter = AtomicLong(0)

    val generation: Long
        get() = counter.get()

    /** Records `ON_START`; generation changes even before the resumed reconnect is considered. */
    fun enterStarted(): Long = counter.incrementAndGet()

    /** Records `ON_RESUME` and returns the new generation. */
    fun enterForeground(): Long = counter.incrementAndGet()

    /** Records the app leaving the foreground and returns the new generation. */
    fun enterBackground(): Long = counter.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = counter.get() == generation
}
