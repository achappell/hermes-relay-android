package com.achappell.hermesrelay

import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Standard executor seam with explicit monotonic deadlines; no timer thread or sleeps. */
internal class ManualAudioScheduler(val clock: ManualClock = ManualClock()) : ScheduledThreadPoolExecutor(1) {
    private val tasks = mutableListOf<Task>()
    private val scheduled = LinkedBlockingQueue<Task>()

    internal inner class Task(val command: Runnable, val at: Long) :
        FutureTask<Unit>(command, Unit), RunnableScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(at - clock.nanoTime(), TimeUnit.NANOSECONDS)
        override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        override fun isPeriodic() = false
        /** Simulates an expired task already dequeued when cancel raced with its dispatch. */
        fun runStale() = command.run()
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val task = Task(command, clock.nanoTime() + unit.toNanos(delay))
        synchronized(tasks) { tasks += task }
        scheduled.put(task)
        return task
    }

    fun awaitScheduled(): Task = checkNotNull(scheduled.poll(5, TimeUnit.SECONDS)) { "startup deadline was not armed" }

    fun advanceMillis(millis: Long) {
        clock.advanceMillis(millis)
        val due = synchronized(tasks) {
            tasks.filter { it.at <= clock.nanoTime() }.also { tasks.removeAll(it.toSet()) }
        }
        due.forEach { it.run() }
    }
}
