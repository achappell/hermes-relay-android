package com.achappell.hermesrelay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Uses only in-memory pairing/credential state; safe on an already paired device. */
@RunWith(AndroidJUnit4::class)
class PairedHomesFeedbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun refresh_with_no_new_profiles_keeps_parent_sheet_and_result_visible() {
        val fixture = showConfiguration()
        refreshAndAssertMessage("No new profiles.")
        assertEquals(1, fixture.calls.get())
        assertEquals(1, fixture.configuration.collection.profiles.size)
    }

    @Test
    fun refresh_with_new_grant_keeps_parent_sheet_and_reports_actual_count() {
        val fixture = showConfiguration(newGrant = true)
        refreshAndAssertMessage("1 new profile added.")
        assertEquals(1, fixture.calls.get())
        assertEquals(2, fixture.configuration.collection.profiles.size)
    }

    @Test
    fun failed_refresh_keeps_parent_sheet_and_failure_visible() {
        val fixture = showConfiguration(fail = true)
        refreshAndAssertMessage("Home could not be reached. Check that this phone is on the tailnet and try again.")
        assertEquals(1, fixture.calls.get())
        assertEquals(1, fixture.configuration.collection.profiles.size)
    }

    @Test
    fun selecting_a_profile_still_completes_configuration() {
        showConfiguration()
        composeRule.onNodeWithText("Select").performScrollTo().performClick()
        composeRule.onAllNodesWithTag("android_relay_configuration_sheet").assertCountEquals(0)
    }

    @Test
    fun deleting_a_profile_with_another_selected_still_completes_configuration() {
        val fixture = showConfiguration(twoProfiles = true)
        composeRule.onAllNodesWithText("Delete")[1].performScrollTo().performClick()
        assertEquals(1, fixture.configuration.collection.profiles.size)
        composeRule.onAllNodesWithTag("android_relay_configuration_sheet").assertCountEquals(0)
    }

    @Test
    fun saving_a_profile_still_completes_configuration() {
        showConfiguration()
        enter("android_relay_endpoint", "wss://relay.example/voice-session")
        enter("android_relay_client_id", "test-client")
        enter("android_relay_device_id", "test-device")
        enter("android_relay_display_name", "Saved")
        enter("android_relay_token", "test-only-token")
        composeRule.onNodeWithTag("android_relay_save").performScrollTo().performClick()
        composeRule.onAllNodesWithTag("android_relay_configuration_sheet").assertCountEquals(0)
    }

    @Test
    fun completing_pairing_still_completes_configuration() {
        showConfiguration()
        enter("android_home_pair_code", "K7Q4MX")
        enter("android_home_pair_address", "https://home.example")
        composeRule.onNodeWithTag("android_home_pair_code_submit").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("android_relay_configuration_sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun enter(tag: String, value: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextInput(value)
        closeSoftKeyboard()
    }

    private fun refreshAndAssertMessage(message: String) {
        composeRule.onNodeWithTag("android_paired_home_refresh").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("android_paired_home_message").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("android_relay_configuration_sheet").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("android_relay_configuration_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("android_paired_home_message")
            .performScrollTo().assertIsDisplayed().assertTextEquals(message)
    }

    private fun showConfiguration(
        newGrant: Boolean = false,
        fail: Boolean = false,
        twoProfiles: Boolean = false,
    ): Fixture {
        val grant = HomeClientGrant("grant-one", "First", HomeClientGrantStatus.Active, true)
        val second = HomeClientGrant("grant-two", "Second", HomeClientGrantStatus.Active, true)
        val record = HomeClientPairingRecord(
            pairingId = "test-pairing", homeUrl = "https://home.example", deviceId = "test-device",
            generation = 1, credentialExpiresAt = 10_000.0,
            grants = if (twoProfiles) listOf(grant, second) else listOf(grant),
        )
        val store = InMemoryHomeClientPairingStore(HomeClientPairings(records = listOf(record)))
        val credentials = InMemoryRelayCredentialStore().apply {
            putHomeCredential(record.credentialSlot, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        }
        var nextId = 0
        val configuration = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(), credentials = credentials,
            homeClientPairings = store, idFactory = { "test-profile-${nextId++}" },
        )
        configuration.addHomeClientProfiles(record)
        val calls = AtomicInteger()
        val service = object : HomeClientService by HttpHomeClientService(HomeHttpTransport {
            error("This regression must not perform network requests")
        }) {
            override fun submit(target: HomePairingTarget, endpointId: String, label: String) =
                HomeEnrollmentSubmission("test-request", "123456", 1_000.0)

            override fun consume(homeUrl: String, requestId: String, code: String) =
                HomeConsumeResult.Approved(
                    HomeDeviceCredentialMaterial(
                        "test-device", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1, 10_000.0,
                        HomeCredentialScope(emptyList(), listOf("client_claim")),
                    ),
                    listOf(grant),
                )

            override fun configuration(homeUrl: String, credential: String, deviceId: String): HomeClientConfiguration {
                calls.incrementAndGet()
                if (fail) throw HomeAdministrationException(HomeAdministrationError.TransportUnavailable)
                val grants = if (newGrant) {
                    listOf(grant, HomeClientGrant("grant-two", "Second", HomeClientGrantStatus.Active, true))
                } else listOf(grant)
                return HomeClientConfiguration(1, grants)
            }
        }
        val coordinator = HomeClientPairingCoordinator(
            store = store, credentials = credentials, service = service, configuration = configuration,
            deviceLabel = "Test", clock = { 100.0 }, sleeper = {},
        )
        composeRule.setContent {
            HermesRelayTheme {
                AndroidClientScreen(
                    clientPort = BootstrapClientPort,
                    configuration = configuration,
                    homePairing = coordinator,
                )
            }
        }
        composeRule.onNodeWithTag("android_more_menu").performClick()
        composeRule.onNodeWithTag("android_menu_configure_relay").performClick()
        composeRule.onNodeWithTag("android_relay_configuration_sheet").assertIsDisplayed()
        return Fixture(configuration, calls)
    }

    private data class Fixture(val configuration: RelayConfigurationController, val calls: AtomicInteger)
}
