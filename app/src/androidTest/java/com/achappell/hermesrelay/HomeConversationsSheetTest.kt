package com.achappell.hermesrelay

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeConversationsSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun open_claims_show_server_count_current_marker_and_only_close_non_current_refs() {
        val submitted = AtomicReference<Pair<String, List<String>>?>()
        val claims = listOf(
            HomeClientClaim(
                claimRef = "current-ref",
                grantId = "grant-current",
                profileLabel = "Work",
                sessionRef = null,
                createdAt = 1_700_000_000.0,
                openedAt = null,
                state = HomeClientClaimState.Connecting,
            ),
            HomeClientClaim(
                claimRef = "other-ref",
                grantId = "grant-other",
                profileLabel = "Family",
                sessionRef = "session-other",
                createdAt = 1_700_000_100.0,
                openedAt = 1_700_000_200.0,
                state = HomeClientClaimState.Idle,
                title = "Groceries",
            ),
            HomeClientClaim(
                claimRef = "untitled-ref",
                grantId = "grant-untitled",
                profileLabel = null,
                sessionRef = null,
                createdAt = 1_700_000_300.0,
                openedAt = null,
                state = HomeClientClaimState.WaitingToReconnect,
            ),
        )
        composeRule.setContent {
            HermesRelayTheme {
                HomeConversationsSheet(
                    state = HomeConversationsState.Listed(emptyList()),
                    currentRef = null,
                    canRename = false,
                    actionsEnabled = true,
                    renameMessage = null,
                    onNew = {},
                    onResume = {},
                    onRename = {},
                    onRefresh = {},
                    onDismiss = {},
                    openClaimsState = HomeOpenClaimsState.Listed(
                        pairingId = "pairing-1",
                        maxClaims = 2,
                        claims = claims,
                    ),
                    currentClaimRef = "current-ref",
                    onCloseClaims = { pairingId, refs -> submitted.set(pairingId to refs) },
                )
            }
        }

        composeRule.onNodeWithText("Open on Home (3 open · max 2)").assertIsDisplayed()
        composeRule.onNodeWithText("Current conversation", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Groceries", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Untitled conversation", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Profile name unavailable", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("android_home_claim_close_0").assertCountEquals(0)
        composeRule.onNodeWithTag("android_home_claim_close_1").assertIsEnabled()
        composeRule.onNodeWithTag("android_home_claim_close_1").performClick()
        assertEquals("pairing-1" to listOf("other-ref"), submitted.get())

        composeRule.onNodeWithTag("android_home_claims_close_all_others").performClick()
        assertEquals("pairing-1" to listOf("other-ref", "untitled-ref"), submitted.get())
    }

    @Test
    fun close_results_are_announced_in_a_polite_live_region() {
        composeRule.setContent {
            HermesRelayTheme {
                HomeConversationsSheet(
                    state = HomeConversationsState.Listed(emptyList()),
                    currentRef = null,
                    canRename = false,
                    actionsEnabled = true,
                    renameMessage = null,
                    onNew = {},
                    onResume = {},
                    onRename = {},
                    onRefresh = {},
                    onDismiss = {},
                    openClaimsState = HomeOpenClaimsState.Listed(
                        pairingId = "pairing-1",
                        maxClaims = 4,
                        claims = emptyList(),
                    ),
                    claimManagementMessage = "Open conversations refreshed.",
                )
            }
        }

        val semantics = composeRule.onNodeWithText("Open conversations refreshed.")
            .fetchSemanticsNode()
            .config
        assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])
    }

    @Test
    fun unsupported_claim_routes_keep_the_section_hidden() {
        composeRule.setContent {
            HermesRelayTheme {
                HomeConversationsSheet(
                    state = HomeConversationsState.Listed(emptyList()),
                    currentRef = null,
                    canRename = false,
                    actionsEnabled = true,
                    renameMessage = null,
                    onNew = {},
                    onResume = {},
                    onRename = {},
                    onRefresh = {},
                    onDismiss = {},
                    openClaimsState = HomeOpenClaimsState.Hidden,
                )
            }
        }

        composeRule.onAllNodesWithText("Open on Home").assertCountEquals(0)
        composeRule.onAllNodesWithTag("android_home_claim_row_0").assertCountEquals(0)
        composeRule.onAllNodesWithTag("android_home_claims_close_all_others").assertCountEquals(0)
    }

}
