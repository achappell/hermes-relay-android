package com.achappell.hermesrelay

/**
 * Recall of previously submitted prompts, in the shell tradition.
 *
 * Navigating away from a half-typed prompt preserves it, so stepping back
 * through history and returning never costs the user what they were writing.
 */
internal data class AndroidPromptHistory(
    private val entries: List<String> = emptyList(),
    private val cursor: Int? = null,
    private val draftBeforeNavigation: String? = null,
    private val limit: Int = DEFAULT_LIMIT,
) {
    val isEmpty: Boolean
        get() = entries.isEmpty()

    val size: Int
        get() = entries.size

    val isNavigating: Boolean
        get() = cursor != null

    /** Records a submitted prompt and resets navigation. */
    fun record(text: String): AndroidPromptHistory {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return resetNavigation()
        // Repeating the last prompt should not stack duplicates.
        if (entries.lastOrNull() == trimmed) return resetNavigation()

        return copy(
            entries = (entries + trimmed).takeLast(limit),
            cursor = null,
            draftBeforeNavigation = null,
        )
    }

    /**
     * Steps to the previous prompt.
     *
     * Returns the history unchanged and a null value when there is nothing
     * older to show.
     */
    fun previous(currentDraft: String): Pair<AndroidPromptHistory, String?> {
        if (entries.isEmpty()) return this to null

        val nextCursor = when (cursor) {
            null -> entries.lastIndex
            0 -> return this to null
            else -> cursor - 1
        }
        val preserved = draftBeforeNavigation ?: currentDraft
        return copy(cursor = nextCursor, draftBeforeNavigation = preserved) to entries[nextCursor]
    }

    /**
     * Steps back toward the present.
     *
     * Stepping past the newest entry restores the draft the user was typing
     * before they started navigating.
     */
    fun next(): Pair<AndroidPromptHistory, String?> {
        val current = cursor ?: return this to null

        if (current >= entries.lastIndex) {
            val restored = draftBeforeNavigation ?: ""
            return copy(cursor = null, draftBeforeNavigation = null) to restored
        }
        return copy(cursor = current + 1) to entries[current + 1]
    }

    fun resetNavigation(): AndroidPromptHistory =
        if (cursor == null && draftBeforeNavigation == null) {
            this
        } else {
            copy(cursor = null, draftBeforeNavigation = null)
        }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
