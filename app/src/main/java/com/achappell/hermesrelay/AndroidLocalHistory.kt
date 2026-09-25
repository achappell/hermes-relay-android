package com.achappell.hermesrelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class AndroidTranscriptRole {
    User,
    Assistant,

    /** A local marker such as "New conversation"; never sent to Hermes. */
    Divider,
}

internal data class AndroidTranscriptEntry(
    val role: AndroidTranscriptRole,
    val text: String,
    val createdAtMillis: Long,
)

/**
 * One Profile's deliberate Local History.
 *
 * Local History is text the household chose to keep on this device. It holds
 * what was said and what was answered — never audio, never a credential, and
 * never another Client's conversation. Retention is bounded on purpose: an
 * unbounded transcript is a privacy liability that grows on its own.
 */
internal data class AndroidLocalHistory(
    val entries: List<AndroidTranscriptEntry> = emptyList(),
    val draft: String = "",
) {
    fun appending(entry: AndroidTranscriptEntry, limit: Int = ENTRY_LIMIT): AndroidLocalHistory {
        if (entry.text.isBlank()) return this
        val next = entries + entry
        return copy(entries = next.takeLast(limit))
    }

    fun withDraft(value: String): AndroidLocalHistory = copy(draft = value)

    fun cleared(): AndroidLocalHistory = AndroidLocalHistory(draft = draft)

    fun toJson(): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("role", entry.role.name.lowercase())
                    .put("text", entry.text)
                    .put("created_at", entry.createdAtMillis),
            )
        }
        return JSONObject()
            .put("entries", array)
            .put("draft", draft)
            .toString()
    }

    companion object {
        const val ENTRY_LIMIT = 200

        fun fromJson(raw: String): AndroidLocalHistory {
            if (raw.isBlank()) return AndroidLocalHistory()
            return runCatching {
                val root = JSONObject(raw)
                val array = root.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val text = item.optString("text")
                    if (text.isBlank()) return@mapNotNull null
                    AndroidTranscriptEntry(
                        role = when (item.optString("role")) {
                            "assistant" -> AndroidTranscriptRole.Assistant
                            "divider" -> AndroidTranscriptRole.Divider
                            else -> AndroidTranscriptRole.User
                        },
                        text = text,
                        createdAtMillis = item.optLong("created_at"),
                    )
                }
                AndroidLocalHistory(
                    entries = entries.takeLast(ENTRY_LIMIT),
                    draft = root.optString("draft"),
                )
            }.getOrElse { AndroidLocalHistory() }
        }
    }
}

/** Per-Profile history storage. One Profile's history never reaches another. */
internal interface AndroidHistoryStore {
    fun load(profileId: String): AndroidLocalHistory

    fun save(profileId: String, history: AndroidLocalHistory)

    fun delete(profileId: String)
}

internal class FileAndroidHistoryStore(
    private val directory: File,
) : AndroidHistoryStore {
    constructor(context: Context) : this(context.filesDir)

    override fun load(profileId: String): AndroidLocalHistory =
        runCatching {
            val file = fileFor(profileId)
            if (!file.exists()) {
                AndroidLocalHistory()
            } else {
                AndroidLocalHistory.fromJson(file.readText())
            }
        }.getOrElse { AndroidLocalHistory() }

    override fun save(profileId: String, history: AndroidLocalHistory) {
        runCatching { fileFor(profileId).writeText(history.toJson()) }
    }

    override fun delete(profileId: String) {
        runCatching { fileFor(profileId).delete() }
    }

    // The profile id comes from our own storage, but a filename is built from
    // it, so it is constrained rather than trusted.
    private fun fileFor(profileId: String): File {
        val safe = profileId.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("").take(64).ifBlank { "unknown" }
        return File(directory, "history-$safe.json")
    }
}

internal class InMemoryAndroidHistoryStore : AndroidHistoryStore {
    private val stored = mutableMapOf<String, AndroidLocalHistory>()

    override fun load(profileId: String) = stored[profileId] ?: AndroidLocalHistory()

    override fun save(profileId: String, history: AndroidLocalHistory) {
        stored[profileId] = history
    }

    override fun delete(profileId: String) {
        stored.remove(profileId)
    }

    fun contains(profileId: String) = stored.containsKey(profileId)
}

/**
 * Records a Profile's conversation as it happens.
 *
 * Nothing is recorded speculatively: a turn is written when it is submitted,
 * and an answer when the turn settles with text to keep.
 */
internal class AndroidHistoryRecorder(
    private val store: AndroidHistoryStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var profileId: String? = null
        private set

    var history: AndroidLocalHistory = AndroidLocalHistory()
        private set

    /** Loads the given Profile's history, replacing whatever was in view. */
    fun open(profileId: String?) {
        this.profileId = profileId
        history = profileId?.let { store.load(it) } ?: AndroidLocalHistory()
    }

    fun recordUserTurn(text: String) = record(AndroidTranscriptRole.User, text)

    fun recordResponse(text: String) = record(AndroidTranscriptRole.Assistant, text)

    /** Marks a conversation boundary. A leading divider is pointless and skipped. */
    fun recordDivider(text: String) {
        if (history.entries.isEmpty()) return
        record(AndroidTranscriptRole.Divider, text)
    }

    fun recordDraft(value: String) {
        val id = profileId ?: return
        if (history.draft == value) return
        history = history.withDraft(value)
        store.save(id, history)
    }

    fun clear() {
        val id = profileId ?: return
        history = history.cleared()
        store.save(id, history)
    }

    private fun record(role: AndroidTranscriptRole, text: String) {
        val id = profileId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // An identical consecutive entry is a re-render, not a new utterance.
        val last = history.entries.lastOrNull()
        if (last?.role == role && last.text == trimmed) return

        history = history.appending(
            AndroidTranscriptEntry(role, trimmed, clock()),
        )
        store.save(id, history)
    }
}
