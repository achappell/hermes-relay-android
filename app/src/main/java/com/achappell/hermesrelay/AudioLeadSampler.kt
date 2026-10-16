package com.achappell.hermesrelay

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A single bounded, content-free background-entry observation window; never controls output. */
internal class AudioLeadSampler(
    private val readLead: () -> AudioPlaybackLead?,
    private val journal: DiagnosticsJournal,
    private val clock: MonotonicClock,
    scheduler: ScheduledExecutorService? = null,
    private val readStream: () -> Any?,
) {
    private val worker = scheduler ?: Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hermes-audio-diagnostics").apply { isDaemon = true }
    }
    private val ownsWorker = scheduler == null
    private val lock = Any()
    private var generation = 0L
    private var pending: ScheduledFuture<*>? = null
    private var background = false

    fun backgroundEntered(stream: Any?) = synchronized(lock) {
        if (background) return@synchronized
        background = true
        if (stream == null) return@synchronized
        val expected = ++generation
        val entered = clock.nanoTime()
        pending = worker.schedule({ sample(expected, entered, 0, stream) }, 0, TimeUnit.MILLISECONDS)
    }

    fun stop() = synchronized(lock) {
        background = false
        generation++
        pending?.cancel(false)
        pending = null
    }

    private fun sample(expected: Long, entered: Long, index: Int, stream: Any) {
        val limitMillis = if (index == 20) 5_250L else 5_000L
        fun elapsedMillis() = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - entered)
        synchronized(lock) {
            if (generation != expected || !background || elapsedMillis() > limitMillis ||
                readStream() !== stream) return
        }
        // Native work stays outside both the sampler and sink's ownership locks.
        // A concurrent write may make this one snapshot unavailable; the next slot can recover.
        val lead = readLead()
        synchronized(lock) {
            if (generation != expected || !background || elapsedMillis() > limitMillis ||
                readStream() !== stream) return
            if (lead != null && lead.stream === stream) {
                lead.publish {
                    val elapsed = elapsedMillis()
                    if (elapsed <= limitMillis) {
                        journal.record("audio output lead_ms=${lead.millis} t_ms=$elapsed state=${lead.state} playing=${lead.playing}")
                    }
                }
            }
            // Never synthesize missed observations by dispatching a zero-delay catch-up burst.
            val nextIndex = maxOf(index + 1, (elapsedMillis() / 250 + 1).toInt())
            pending = if (nextIndex <= 20) {
                val next = entered + nextIndex * 250_000_000L
                worker.schedule(
                    { sample(expected, entered, nextIndex, stream) },
                    (next - clock.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS,
                )
            } else null
        }
    }

    fun close() {
        stop()
        if (ownsWorker) worker.shutdownNow()
    }
}
