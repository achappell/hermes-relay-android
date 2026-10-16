package com.achappell.hermesrelay

/** Explicit-time counterpart of the JVM fixture; advance only on the UI thread. */
internal class ManualVoiceTimers : VoiceTimers, MonotonicClock {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var now = 0L

    override fun nanoTime(): Long = now * 1_000_000

    override fun after(delayMillis: Long, action: () -> Unit): VoiceTimer {
        val entry = Entry(now + delayMillis, action)
        entries += entry
        return VoiceTimer { entry.cancelled = true }
    }

    fun advanceBy(millis: Long) {
        val target = now + millis
        while (true) {
            val next = entries.filter { !it.cancelled && it.dueAt <= target }
                .minByOrNull { it.dueAt } ?: break
            now = next.dueAt
            entries.remove(next)
            next.action()
        }
        now = target
        entries.removeAll { it.cancelled }
    }
}
