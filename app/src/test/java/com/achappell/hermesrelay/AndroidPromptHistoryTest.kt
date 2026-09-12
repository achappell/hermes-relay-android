package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPromptHistoryTest {
    private fun historyOf(vararg prompts: String): AndroidPromptHistory =
        prompts.fold(AndroidPromptHistory()) { history, prompt -> history.record(prompt) }

    @Test
    fun stepping_back_walks_from_newest_to_oldest() {
        var history = historyOf("first", "second", "third")

        val (h1, p1) = history.previous("")
        val (h2, p2) = h1.previous("")
        val (h3, p3) = h2.previous("")
        history = h3

        assertEquals("third", p1)
        assertEquals("second", p2)
        assertEquals("first", p3)

        // Nothing older exists.
        val (_, beyond) = history.previous("")
        assertNull(beyond)
    }

    @Test
    fun stepping_forward_past_the_newest_restores_the_half_typed_draft() {
        val history = historyOf("first", "second")

        val (back, _) = history.previous("half typed")
        val (forward, restored) = back.next()

        assertEquals("half typed", restored)
        assertTrue("navigation did not end", !forward.isNavigating)
    }

    @Test
    fun the_draft_is_preserved_across_several_steps_back() {
        val history = historyOf("first", "second", "third")

        val (a, _) = history.previous("half typed")
        val (b, _) = a.previous("")
        val (c, _) = b.next()
        val (_, restored) = c.next()

        assertEquals("half typed", restored)
    }

    @Test
    fun recording_a_prompt_ends_navigation() {
        val history = historyOf("first", "second")
        val (navigating, _) = history.previous("draft")
        assertTrue(navigating.isNavigating)

        val recorded = navigating.record("third")

        assertTrue(!recorded.isNavigating)
        val (_, newest) = recorded.previous("")
        assertEquals("third", newest)
    }

    @Test
    fun blank_and_repeated_prompts_are_not_recorded() {
        val history = historyOf("first", "   ", "first", "first")

        assertEquals(1, history.size)
    }

    @Test
    fun retention_is_bounded() {
        var history = AndroidPromptHistory()
        repeat(AndroidPromptHistory.DEFAULT_LIMIT + 10) { index ->
            history = history.record("prompt $index")
        }

        assertEquals(AndroidPromptHistory.DEFAULT_LIMIT, history.size)
        val (_, newest) = history.previous("")
        assertEquals("prompt ${AndroidPromptHistory.DEFAULT_LIMIT + 9}", newest)
    }

    @Test
    fun an_empty_history_offers_nothing_to_recall() {
        val history = AndroidPromptHistory()
        val (unchanged, prompt) = history.previous("draft")

        assertNull(prompt)
        assertTrue(unchanged.isEmpty)
        assertNull(history.next().second)
    }
}
