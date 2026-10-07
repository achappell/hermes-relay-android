package com.achappell.hermesrelay

import java.util.Collections

/** In-memory journal for tests: keeps every line, in order, with no writer thread. */
internal class RecordingJournal : DiagnosticsJournal {
    private val recorded = Collections.synchronizedList(mutableListOf<String>())

    val lines: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    fun count(prefix: String): Int = lines.count { it.startsWith(prefix) }

    override fun record(event: String) {
        recorded += event
    }

    override fun snapshot(): List<JournalEntry> =
        lines.mapIndexed { index, event -> JournalEntry(index.toLong(), event) }
}

/** Runs queued writer tasks only when the test says so. */
internal class ManualExecutor : java.util.concurrent.Executor {
    private val tasks = ArrayDeque<Runnable>()

    val pending: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
