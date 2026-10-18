package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ANDROID-UX-11`: grouped Settings checklist, section order, 2.0x font
 * scale and the Revoke confirmation. In-memory stores only; no network.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun every_existing_settings_action_is_reachable() {
        val fixture = show()

        assertTrue(composeRule.onAllNodesWithText("Select").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty())
        listOf(
            "android_choose_home",
            "android_choose_standard",
            "android_home_pair_scan",
            "android_home_pair_link",
            "android_home_pair_link_submit",
            "android_home_pair_code",
            "android_home_pair_address",
            "android_home_pair_code_submit",
            "android_paired_home_refresh",
            "android_paired_home_unpair",
            "android_settings_microphone_open",
            "android_settings_advanced_toggle",
            "app-version",
            "android_diagnostics_share",
        ).forEach(::assertReachable)

        composeRule.onNodeWithTag("android_settings_advanced_toggle").performScrollTo().performClick()
        listOf(
            "android_relay_endpoint",
            "android_relay_client_id",
            "android_relay_device_id",
            "android_relay_display_name",
            "android_relay_token",
            "android_relay_save",
            "android_home_admin_credential",
            "android_home_save_admin",
            "android_home_device_id",
            "android_home_device_label",
            "android_home_discover",
            "android_home_create_offer",
            "android_home_offer_code",
            "android_home_request_id",
            "android_home_requested_rooms",
            "android_home_requested_room_add",
            "android_home_requested_capabilities",
            "android_home_wake_mapping_ids",
            "android_home_submit_request",
            "android_home_approve",
            "android_home_consume",
            "android_home_fetch_configuration",
            "android_home_revoke",
        ).forEach(::assertReachable)
        composeRule.onNodeWithText("Renew").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Re-enroll").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("android_choose_standard").performScrollTo().performClick()
        listOf(
            "android_standard_endpoint",
            "android_standard_name",
            "android_standard_profile",
            "android_standard_token",
            "android_standard_save",
        ).forEach(::assertReachable)
        assertEquals(2, fixture.configuration.collection.profiles.size)
    }

    @Test
    fun sections_and_headings_follow_the_grouped_settings_order() {
        show()

        // Layout positions, not viewport-clipped bounds: later sections sit below the fold.
        val tops = SettingsSection.entries.map { section ->
            composeRule.onNodeWithTag(section.tag).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(tops.sorted(), tops)
        assertEquals(tops.size, tops.toSet().size)
        val headings = composeRule.onAllNodes(isHeading(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertEquals(listOf("Profiles", "Home pairing", "Voice", "Advanced", "Troubleshooting"), headings)
    }

    @Test
    fun two_x_font_scale_never_needs_horizontal_scrolling() {
        show(fontScale = 2f)
        composeRule.onNodeWithTag("android_settings_advanced_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()

        val container = composeRule.onNodeWithTag("settings_container").fetchSemanticsNode().boundsInRoot
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        // Unclipped extents, so nodes scrolled below the fold are still measured.
        val overflowing = composeRule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter {
                val left = it.positionInRoot.x
                left < container.left - 1f || left + it.size.width > container.right + 1f
            }
            .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.getOrNull(SemanticsProperties.Text) }
        assertEquals(emptyList<Any?>(), overflowing)
    }

    @Test
    fun revoke_asks_for_confirmation_and_keep_changes_nothing() {
        val fixture = show()
        composeRule.onNodeWithTag("android_settings_advanced_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        val phaseBefore = fixture.administration.state.phase
        composeRule.onNodeWithTag("android_home_revoke").performScrollTo().performClick()

        composeRule.onNodeWithTag("android_home_revoke_confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Keep").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("android_home_revoke_confirm").assertCountEquals(0)
        composeRule.onAllNodesWithTag("android_home_message").assertCountEquals(0)
        assertEquals(phaseBefore, fixture.administration.state.phase)
    }

    @Test
    fun requested_rooms_are_chips_not_comma_separated_text() {
        show()
        composeRule.onNodeWithTag("android_settings_advanced_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("android_home_requested_rooms").performScrollTo().performTextInput("kitchen")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("android_home_requested_room_add").performScrollTo().performClick()

        composeRule.onNodeWithTag("android_home_requested_room_kitchen").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("android_home_requested_rooms").assertTextContainsNothing()

        composeRule.onNodeWithTag("android_home_requested_room_kitchen").performClick()
        composeRule.onAllNodesWithTag("android_home_requested_room_kitchen").assertCountEquals(0)
    }

    private fun assertReachable(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsNothing() {
        val text = fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals("", text)
    }

    private fun show(fontScale: Float? = null): Fixture {
        val operator = RelayProfile(
            id = "operator-profile",
            endpoint = "wss://home.example",
            clientId = "amanda-phone",
            deviceId = "android-1",
            displayName = "Operator",
            homeBinding = RelayHomeBinding(
                approvedRoute = "wss://home.example/api/v1/bridge/ws",
                conversationHandle = "conversation-1",
            ),
        )
        val grant = HomeClientGrant("grant-one", "First", HomeClientGrantStatus.Active, true)
        val record = HomeClientPairingRecord(
            pairingId = "test-pairing", homeUrl = "https://home.example", deviceId = "test-device",
            generation = 1, credentialExpiresAt = 10_000.0, grants = listOf(grant),
        )
        val store = InMemoryHomeClientPairingStore(HomeClientPairings(records = listOf(record)))
        val credentials = InMemoryRelayCredentialStore().apply {
            putHomeCredential(record.credentialSlot, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        }
        var nextId = 0
        val configuration = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(RelayProfileCollection(listOf(operator), operator.id)),
            credentials = credentials,
            homeClientPairings = store,
            idFactory = { "test-profile-${nextId++}" },
        )
        configuration.addHomeClientProfiles(record)
        configuration.select(operator.id)
        val administration = HomeDeviceAdministrationController(
            relayConfiguration = configuration,
            credentials = credentials,
            clientFactory = { _, _, _ -> error("Settings layout must not create a Home client") },
        )
        val service = HttpHomeClientService(HomeHttpTransport {
            error("Settings layout must not perform network requests")
        })
        val coordinator = HomeClientPairingCoordinator(
            store = store, credentials = credentials, service = service, configuration = configuration,
            deviceLabel = "Test", clock = { 100.0 }, sleeper = {},
        )
        composeRule.setContent {
            HermesRelayTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale ?: base.fontScale),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .testTag("settings_container")
                            .verticalScroll(rememberScrollState()),
                    ) {
                        RelayConfigurationScreen(
                            controller = configuration,
                            homeAdministration = administration,
                            homePairing = coordinator,
                            versionLabel = "Version 0.0.0 (1, debug, test)",
                            onShareDiagnostics = {},
                            onChanged = {},
                        )
                    }
                }
            }
        }
        return Fixture(configuration, administration)
    }

    private data class Fixture(
        val configuration: RelayConfigurationController,
        val administration: HomeDeviceAdministrationController,
    )
}
