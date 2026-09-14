package com.achappell.hermesrelay

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
