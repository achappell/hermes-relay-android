package com.achappell.hermesrelay

/** [VoiceTimers] that fire only when the test advances time. Single-threaded. */
internal class ManualVoiceTimers : VoiceTimers {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var now = 0L

    val pendingCount: Int get() = entries.count { !it.cancelled }

    override fun after(delayMillis: Long, action: () -> Unit): VoiceTimer {
        val entry = Entry(now + delayMillis, action)
        entries += entry
        return VoiceTimer { entry.cancelled = true }
    }

    /** Moves time forward, firing every timer that becomes due, earliest first. */
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
