package com.achappell.hermesrelay

import android.Manifest
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardConfigurationRecoveryTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    @Test
    fun reselecting_the_current_profile_restores_submission() = verifyRecovery(deleteOther = false)

    @Test
    fun deleting_another_profile_restores_submission_with_the_existing_credential() = verifyRecovery(deleteOther = true)

    private fun verifyRecovery(deleteOther: Boolean) {
        val selected = RelayProfile(
            id = "selected", endpoint = "wss://example.test/api/ws", clientId = "", deviceId = "",
            displayName = "Selected", mode = RelayConnectionMode.Standard, hermesProfile = "amanda",
        )
        val other = selected.copy(id = "other", displayName = "Other", hermesProfile = "default")
        val credentials = InMemoryRelayCredentialStore().apply {
            putStandardCredential(selected.id, "selected-secret")
            putStandardCredential(other.id, "other-secret")
        }
        val configuration = RelayConfigurationController(
            InMemoryRelayProfileStore(RelayProfileCollection(listOf(selected, other), selected.id)),
            credentials,
        )
        val port = ConfigurationPort(configuration, credentials)
        composeRule.setContent {
            HermesRelayTheme { AndroidClientScreen(clientPort = port, configuration = configuration) }
        }
        composeRule.waitUntil { port.connected }
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithText("Configure relay").performClick()
        composeRule.onAllNodesWithText(if (deleteOther) "Delete" else "Select")[if (deleteOther) 1 else 0]
            .performScrollTo().performClick()
        composeRule.waitUntil { port.disconnects > 0 && port.connected }
        composeRule.onNodeWithTag("android_typed_prompt").performTextInput("after configuration")
        closeSoftKeyboard()
        composeRule.onNodeWithText("Start typed turn").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil { port.submitted }
        composeRule.runOnIdle {
            assertEquals(selected.id, configuration.collection.selectedId)
            assertEquals("selected-secret", credentials.readStandardCredential(selected.id))
            if (deleteOther) assertEquals(listOf(selected), configuration.collection.profiles)
            assertTrue(port.submitted)
        }
    }

    @Test fun a_pending_draft_blocks_connection_changes_until_cleared() = verifyGuard(Guard.Draft)

    @Test fun an_active_standard_turn_blocks_connection_changes_until_settled() = verifyGuard(Guard.Active)

    @Test fun an_uncertain_standard_turn_blocks_connection_changes_until_resolved() = verifyGuard(Guard.Uncertain)

    private enum class Guard { Draft, Active, Uncertain }

    private fun verifyGuard(guard: Guard) {
        val selected = RelayProfile(
            id = "selected", endpoint = "wss://example.test/api/ws", clientId = "", deviceId = "",
            displayName = "Selected", mode = RelayConnectionMode.Standard,
        )
        val other = selected.copy(id = "other", displayName = "Other", hermesProfile = "other")
        val credentials = InMemoryRelayCredentialStore(
            standardCredentials = mapOf(selected.id to "selected-secret", other.id to "other-secret"),
        )
        val initial = RelayProfileCollection(listOf(selected, other), selected.id)
        val configuration = RelayConfigurationController(InMemoryRelayProfileStore(initial), credentials)
        val port = ConfigurationPort(configuration, credentials).apply {
            active = guard == Guard.Active
            uncertain = guard == Guard.Uncertain
        }
        composeRule.setContent {
            HermesRelayTheme { AndroidClientScreen(clientPort = port, configuration = configuration) }
        }
        if (guard == Guard.Draft) {
            composeRule.waitUntil { port.connected }
            composeRule.onNodeWithTag("android_typed_prompt").performTextInput("unsent draft")
            closeSoftKeyboard()
        }
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithText("Configure relay").performClick()
        composeRule.onNodeWithTag("android_relay_configuration").assertDoesNotExist()
        composeRule.onAllNodesWithText("Select").assertCountEquals(0)
        composeRule.onAllNodesWithText("Delete").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(initial, configuration.collection) }

        if (guard == Guard.Draft) {
            androidx.test.espresso.Espresso.pressBack()
            composeRule.onNodeWithTag("android_typed_prompt").performTextClearance()
            closeSoftKeyboard()
            composeRule.onNodeWithTag("android_more_menu").performClick()
            composeRule.onNodeWithText("Configure relay").performClick()
        } else {
            composeRule.runOnIdle { port.active = false; port.uncertain = false }
        }
        composeRule.onAllNodesWithText("Select")[1].performScrollTo().assertIsEnabled()
        composeRule.onAllNodesWithText("Delete")[1].performScrollTo().assertIsEnabled()
    }

    private class ConfigurationPort(
        private val configuration: RelayConfigurationController,
        private val credentials: RelayCredentialStore,
    ) : AndroidClientPort {
        @Volatile var connected = false
        @Volatile var disconnects = 0
        @Volatile var submitted = false
        var active by mutableStateOf(false)
        var uncertain by mutableStateOf(false)
        override fun hasActiveTurn() = active
        override fun hasUncertainTurn() = uncertain
        override fun connectionMode() = RelayConnectionMode.Standard
        override fun hasLiveTransport() = connected
        override fun snapshot() = AndroidClientSnapshot(
            titleRes = R.string.android_mode_standard,
            descriptionRes = R.string.android_standard_limits,
            boundaryRes = R.string.android_standard_limits,
            selectedProfile = configuration.collection.selected?.let { AndroidProfile(it.id, it.displayName) },
            authorizationState = AndroidAuthorizationState.Verified,
        )
        override fun reconnect(): AndroidReconnectOutcome {
            check(credentials.readStandardCredential(configuration.collection.selectedId!!) == "selected-secret")
            connected = true
            return AndroidReconnectOutcome.Connected("session")
        }
        override fun endSession() {
            connected = false
            disconnects += 1
        }
        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            check(connected)
            submitted = true
            return AndroidInitiationResult.Accepted(AndroidTurnBinding("selected", "session", "turn"))
        }
        override fun observeTurn(binding: AndroidTurnBinding, onEvent: (AndroidNormalizedEvent) -> Unit): AndroidTurnObservation {
            onEvent(AndroidNormalizedEvent.TurnCompleted(binding))
            return AndroidTurnObservation {}
        }
    }
}
