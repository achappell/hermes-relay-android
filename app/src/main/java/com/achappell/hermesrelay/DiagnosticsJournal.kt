package com.achappell.hermesrelay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor

/**
 * A small on-device record of how Home connections went (`ANDROID-DIAG-01`).
 *
 * Entries are fixed event names, codes, phases, check-site names and durations
 * only. **Never** pass a prompt, reply, transcript, title, token, credential,
 * conversation handle, claim or session reference, correlation id or audio.
 * Callers build lines from enum names and numbers; the journal does not try to
 * scrub content it was handed.
 *
 * Line grammar reuses the iOS names so one grep works on both platforms.
 */
internal interface DiagnosticsJournal {
    /** Never blocks the caller on disk; safe from audio, feed and main threads. */
    fun record(event: String)

    /** Everything recorded so far, oldest first, including entries not yet on disk. */
    fun snapshot(): List<JournalEntry>

    /** One JSON header line, then one JSON line per entry. */
    fun exportText(header: DiagnosticsHeader): String {
        val lines = ArrayList<String>()
        lines += header.toJson().toJsonLine()
        snapshot().forEach { lines += it.toJson().toJsonLine() }
        return lines.joinToString("\n", postfix = "\n")
    }

    companion object {
        /** Drops every event. For code paths that have no journal wired. */
        val None: DiagnosticsJournal = object : DiagnosticsJournal {
            override fun record(event: String) = Unit

            override fun snapshot(): List<JournalEntry> = emptyList()
        }
    }
}

/**
 * One compact JSON line. Android's `org.json` writes `/` as `\/`; both decode
 * to the same text, but the readable form keeps the header kind greppable and
 * matches the iOS export. Found on a Pixel 6a (API 37): the JVM `org.json`
 * does not escape, so only a device run showed it.
 */
internal fun JSONObject.toJsonLine(): String = toString().replace(ESCAPED_SLASH) { it.groupValues[1] + "/" }

/** An even run of backslashes (escaped backslashes) followed by an escaped slash. */
private val ESCAPED_SLASH = Regex("""(?<!\\)((?:\\\\)*)\\/""")

internal data class JournalEntry(val timeMillis: Long, val event: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", Instant.ofEpochMilli(timeMillis).toString())
        .put("e", event)

    companion object {
        fun fromJson(line: String): JournalEntry? = runCatching {
            val json = JSONObject(line)
            JournalEntry(Instant.parse(json.getString("t")).toEpochMilli(), json.getString("e"))
        }.getOrNull()
    }
}

/**
 * Identifies the build and device a shared journal came from. No user,
 * profile, account, serial or network identity.
 */
internal data class DiagnosticsHeader(
    val appVersion: String,
    val versionCode: Long,
    /** `debug` or `release` (`ANDROID-REL-01`). */
    val buildType: String,
    /** Short git revision of the build, or `unknown`. */
    val revision: String,
    val androidRelease: String,
    val apiLevel: Int,
    val model: String,
    val exportedAtMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", KIND)
        .put("app_version", appVersion)
        .put("version_code", versionCode)
        .put("build_type", buildType)
        .put("revision", revision)
        .put("system", "Android $androidRelease (API $apiLevel)")
        .put("model", model)
        .put("t", Instant.ofEpochMilli(exportedAtMillis).toString())

    companion object {
        const val KIND = "hermes-relay-diagnostics/1"

        fun current(context: Context, now: Long = System.currentTimeMillis()): DiagnosticsHeader {
            val build = AppBuildIdentity.current(context)
            return DiagnosticsHeader(
                appVersion = build.versionName,
                versionCode = build.versionCode,
                buildType = build.buildType,
                revision = build.revision,
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                apiLevel = Build.VERSION.SDK_INT,
                model = Build.MODEL ?: "unknown",
                exportedAtMillis = now,
            )
        }
    }
}

/**
 * File-backed journal: append-only JSON lines, capped at [maxEntries], kept in
 * app-private storage and out of backup.
 *
 * [record] only enqueues. A single [writer] drains the bounded queue; when the
 * queue overflows the oldest queued entries are dropped and a
 * `journal dropped=N` marker is written in their place, so the audio, feed and
 * main threads are never blocked behind the disk.
 */
internal class FileDiagnosticsJournal(
    private val file: File,
    private val writer: Executor,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiagnosticsJournal {
    private val queueLock = Any()
    private val queue = ArrayDeque<JournalEntry>()
    private var dropped = 0
    private var drainScheduled = false

    private val fileLock = Any()
    private var entries: MutableList<JournalEntry>? = null

    /** Lines past [maxEntries] tolerated before the file is rewritten. */
    private val trimSlack = maxOf(1, maxEntries / 8)

    override fun record(event: String) {
        val entry = JournalEntry(clock(), event.take(MAX_EVENT_LENGTH).replace('\n', ' '))
        val schedule = synchronized(queueLock) {
            if (queue.size >= queueCapacity) {
                queue.removeFirst()
                dropped += 1
            }
            queue.addLast(entry)
            (!drainScheduled).also { if (it) drainScheduled = true }
        }
        if (schedule) {
            try {
                writer.execute(::drain)
            } catch (_: RuntimeException) {
                // Writer unavailable (shut down): entries stay queued for the
                // next snapshot or export, which drains on the caller.
                synchronized(queueLock) { drainScheduled = false }
            }
        }
    }

    override fun snapshot(): List<JournalEntry> {
        drain()
        return synchronized(fileLock) { loaded().takeLast(maxEntries) }
    }

    /** Moves everything queued onto disk. Called by the writer, and by readers that need a flush. */
    fun drain() {
        val batch = ArrayList<JournalEntry>()
        val lost: Int
        synchronized(queueLock) {
            batch.addAll(queue)
            queue.clear()
            lost = dropped
            dropped = 0
            drainScheduled = false
        }
        if (lost > 0) {
            batch.add(0, JournalEntry(batch.firstOrNull()?.timeMillis ?: clock(), "journal dropped=$lost"))
        }
        if (batch.isEmpty()) return
        synchronized(fileLock) {
            val current = loaded()
            current.addAll(batch)
            if (current.size > maxEntries + trimSlack) {
                while (current.size > maxEntries) current.removeAt(0)
                rewrite(current)
            } else {
                append(batch)
            }
        }
    }

    private fun loaded(): MutableList<JournalEntry> {
        entries?.let { return it }
        val loaded = ArrayList<JournalEntry>()
        if (file.exists()) {
            runCatching {
                file.forEachLine { line ->
                    // A line torn by a crash mid-append is skipped, not fatal.
                    JournalEntry.fromJson(line)?.let(loaded::add)
                }
            }
        }
        entries = loaded
        return loaded
    }

    private fun append(batch: List<JournalEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(batch.joinToString("") { it.toJson().toJsonLine() + "\n" })
        }
    }

    private fun rewrite(all: List<JournalEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(all.joinToString("") { it.toJson().toJsonLine() + "\n" })
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000
        const val DEFAULT_QUEUE_CAPACITY = 512
        private const val MAX_EVENT_LENGTH = 240

        /** Journal in `noBackupFilesDir`, written by a single daemon thread. */
        fun forApplication(context: Context): FileDiagnosticsJournal {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "hermes-diagnostics").apply { isDaemon = true }
            }
            return FileDiagnosticsJournal(
                file = File(context.noBackupFilesDir, "diagnostics/connection-journal.jsonl"),
                writer = executor,
            )
        }
    }
}

/** Debug builds also write every line to logcat under one tag. */
internal class LogcatEchoJournal(
    private val delegate: DiagnosticsJournal,
    private val echo: (String) -> Unit = { Log.d(TAG, it) },
) : DiagnosticsJournal by delegate {
    override fun record(event: String) {
        echo(event)
        delegate.record(event)
    }

    companion object {
        const val TAG = "HermesDiag"

        fun isDebuggable(context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
