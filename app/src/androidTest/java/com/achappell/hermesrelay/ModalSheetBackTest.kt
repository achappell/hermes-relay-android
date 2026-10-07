package com.achappell.hermesrelay

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ModalSheetBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun back_dismisses_only_the_conversations_sheet() {
        assertBackDismissesOnlySheet("android_conversations_sheet") { visible, onDismiss ->
            if (visible) {
                HomeConversationsSheet(
                    state = HomeConversationsState.Loading,
                    currentRef = null,
                    canRename = false,
                    actionsEnabled = true,
                    renameMessage = null,
                    onNew = {},
                    onResume = {},
                    onRename = {},
                    onRefresh = {},
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun back_dismisses_only_the_approvals_sheet() {
        assertBackDismissesOnlySheet("android_approvals_sheet") { visible, onDismiss ->
            if (visible) {
                HomeApprovalsSheet(
                    state = HomeApprovalsState.Loading,
                    message = null,
                    busy = false,
                    onDecide = { _, _ -> },
                    onRefresh = {},
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun back_dismisses_only_the_history_sheet() {
        val recorder = AndroidHistoryRecorder(InMemoryAndroidHistoryStore()).apply {
            open("ux-09-test-profile")
        }
        assertBackDismissesOnlySheet("android_history_sheet") { visible, onDismiss ->
            LocalHistoryZone(
                recorder = recorder,
                exporter = TranscriptExporter(),
                profileDisplayName = null,
                onClear = {},
                sheetVisibility = visible,
                onDismiss = onDismiss,
            )
        }
    }

    private fun assertBackDismissesOnlySheet(
        tag: String,
        sheet: @Composable (Boolean, () -> Unit) -> Unit,
    ) {
        var visible by mutableStateOf(true)
        val dismissed = AtomicBoolean(false)
        composeRule.setContent {
            HermesRelayTheme {
                Text("Home surface", modifier = Modifier.testTag("ux09_back_underlay"))
                sheet(visible) {
                    dismissed.set(true)
                    visible = false
                }
            }
        }

        composeRule.onNodeWithTag(tag).assertIsDisplayed()
        pressBack()
        composeRule.waitForIdle()

        assertTrue("back should dismiss only $tag", dismissed.get())
        composeRule.onAllNodesWithTag(tag).assertCountEquals(0)
        composeRule.onNodeWithTag("ux09_back_underlay").assertIsDisplayed()
    }
}
