package com.achappell.hermesrelay

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** [attempts] attempts with no wait between them (the pre-HOME-04 ladder shape). */
internal fun ReconnectPolicy.Companion.unpaced(attempts: Int) =
    ReconnectPolicy(
        delaysMillis = List(attempts - 1) { 0L },
        overallMillis = Long.MAX_VALUE,
        attemptMillis = Long.MAX_VALUE,
    )

/**
 * A [RecoveryPacer] on a [ManualClock]: a pause advances the clock unless a
 * wake is pending, so a test sees exactly when each attempt would have run.
 * [onPause] runs inside the wait, which is where a test injects a network
 * event, a stop or a profile switch.
 */
internal class ManualPacer(private val clock: ManualClock) : RecoveryPacer {
    val pauses = mutableListOf<Long>()
    var onPause: (Long) -> Unit = {}
    private var woken = false

    override fun pause(millis: Long) {
        pauses += millis
        onPause(millis)
        if (woken) woken = false else clock.advanceMillis(millis)
    }

    override fun wake() {
        woken = true
    }

    override fun reset() {
        woken = false
    }
}

/** Queues work until [drain] runs it, so a test controls what a newer input overtakes. */
internal class QueuedExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false

    val pending: Int
        get() = tasks.size

    fun drain() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

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

/** Delivers default-network events when the test says so. */
internal class FakeNetworkEvents : NetworkEvents {
    private var listener: NetworkListener? = null
    var observers = 0
        private set
    var cancelled = 0
        private set

    override fun observe(listener: NetworkListener): AndroidTurnObservation {
        this.listener = listener
        observers += 1
        return AndroidTurnObservation {
            this.listener = null
            cancelled += 1
        }
    }

    fun available() {
        listener?.onAvailable()
    }
}
