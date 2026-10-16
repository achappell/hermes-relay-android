package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/**
 * LazyColumn only composes the visible part of the conversation. Tests must
 * scroll its actual container before asking for a rail node.
 */
internal fun ComposeContentTestRule.scrollToConversationTag(tag: String) {
    onNodeWithTag("android_conversation_rail")
        .performScrollToNode(hasTestTag(tag))
}

/** Connection readiness is a runtime result, not merely an idle Compose frame. */
internal fun ComposeContentTestRule.connectToReady(connected: () -> Boolean) {
    scrollToConversationTag("android_connect")
    onNodeWithTag("android_connect").assertIsEnabled().performClick()
    waitUntil { runOnIdle { connected() } }
}

/** Exercise the visible button physically, after the IME has stopped obscuring it. */
internal fun ComposeContentTestRule.submitTypedPrompt(prompt: String, observed: () -> Boolean) {
    onNodeWithTag("android_typed_prompt").performScrollTo().performTextInput(prompt)
    closeSoftKeyboard()
    onNodeWithText("Start typed turn").performScrollTo().assertIsDisplayed()
        .assertIsEnabled().performClick()
    waitUntil { runOnIdle { observed() } }
}
