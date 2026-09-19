package com.achappell.hermesrelay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.achappell.hermesrelay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayConfigurationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var credentials: KeystoreRelayCredentialStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("hermes_relay_credentials", 0)
            .edit().clear().commit()
        credentials = KeystoreRelayCredentialStore(context)
    }

    @Test
    fun a_token_round_trips_through_the_android_keystore() {
        credentials.put("profile-1", "relay-token-value")

        assertTrue(credentials.hasToken("profile-1"))
        assertEquals("relay-token-value", credentials.read("profile-1"))
    }

    @Test
    fun the_stored_envelope_is_not_the_plaintext_token() {
        credentials.put("profile-1", "relay-token-value")

        val stored = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("hermes_relay_credentials", 0)
            .getString("rollback:profile-1", null)

        assertTrue(stored != null && stored.isNotBlank())
        assertTrue(
            "the token was persisted in the clear",
            !stored!!.contains("relay-token-value"),
        )
    }

    @Test
    fun deleting_a_credential_removes_it() {
        credentials.put("profile-1", "relay-token-value")

        credentials.delete("profile-1")

        assertTrue(!credentials.hasToken("profile-1"))
        assertNull(credentials.read("profile-1"))
    }

    @Test
    fun separate_profiles_hold_separate_credentials() {
        credentials.put("profile-1", "token-one")
        credentials.put("profile-2", "token-two")

        assertEquals("token-one", credentials.read("profile-1"))
        assertEquals("token-two", credentials.read("profile-2"))
    }

    @Test
    fun the_home_device_credential_has_its_own_keystore_slot() {
        val credential = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        assertTrue(credentials.putHomeCredential("profile-1", credential))
        assertTrue(credentials.hasReadableHomeCredential("profile-1"))
        assertEquals(credential, credentials.readHomeCredential("profile-1"))

        credentials.deleteHomeCredential("profile-1")

        assertTrue(!credentials.hasReadableHomeCredential("profile-1"))
        assertNull(credentials.readHomeCredential("profile-1"))
        assertTrue(!credentials.hasToken("profile-1"))
    }

    @Test
    fun the_home_admin_credential_has_a_distinct_keystore_slot() {
        assertTrue(credentials.putHomeAdminCredential("profile-1", "admin-secret"))
        assertTrue(credentials.hasReadableHomeAdminCredential("profile-1"))
        assertEquals("admin-secret", credentials.readHomeAdminCredential("profile-1"))
        assertNull(credentials.readHomeCredential("profile-1"))

        val stored = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("hermes_relay_credentials", 0)
            .getString("home-admin:profile-1", null)
        assertTrue(stored != null && !stored!!.contains("admin-secret"))

        credentials.deleteHomeAdminCredential("profile-1")
        assertNull(credentials.readHomeAdminCredential("profile-1"))
    }

    @Test
    fun the_home_administration_surface_is_mounted_and_discovery_is_not_authorization() {
        val profile = RelayProfile(
            id = "profile-1",
            endpoint = "wss://home.example",
            clientId = "amanda-phone",
            deviceId = "android-1",
            displayName = "Amanda",
            homeBinding = RelayHomeBinding(
                approvedRoute = "wss://home.example/api/v1/bridge/ws",
                conversationHandle = "conversation-1",
            ),
        )
        val relay = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(RelayProfileCollection(listOf(profile), profile.id)),
            credentials = credentials,
        )
        val home = HomeDeviceAdministrationController(
            relayConfiguration = relay,
            credentials = credentials,
            clientFactory = { _, _, _ -> error("discovery must not create a Home client") },
        )

        composeRule.setContent {
            HermesRelayTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RelayConfigurationScreen(
                        controller = relay,
                        homeAdministration = home,
                        onChanged = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("android_home_device_id")
            .performScrollTo()
            .performTextInput("android-1")
        composeRule.onNodeWithTag("android_home_device_label")
            .performScrollTo()
            .performTextInput("Kitchen phone")
        composeRule.onNodeWithTag("android_home_discover")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(5_000) {
            home.state.phase == RelayHomeAdministrationPhase.Discovered
        }

        assertNull(credentials.readHomeCredential("profile-1"))
        assertEquals(RelayHomeAdministrationPhase.Discovered, home.state.phase)
        composeRule.onNodeWithTag("android_home_administration").assertIsDisplayed()
    }

    @Test
    fun corrupt_home_device_ciphertext_fails_closed() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("hermes_relay_credentials", 0)
            .edit()
            .putString("home-device:profile-1", "not-a-keystore-envelope")
            .commit()

        assertTrue(!credentials.hasReadableHomeCredential("profile-1"))
        assertNull(credentials.readHomeCredential("profile-1"))
    }

    @Test
    fun saving_a_profile_never_renders_the_token_back() {
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
        )

        composeRule.setContent {
            HermesRelayTheme {
                // Host it inside a scrolling column, as the real screen does.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RelayConfigurationScreen(controller = controller, onChanged = {})
                }
            }
        }

        composeRule.onNodeWithTag("android_relay_endpoint")
            .performTextInput("wss://relay.example/voice-session")
        composeRule.onNodeWithTag("android_relay_client_id").performTextInput("amanda-laptop")
        composeRule.onNodeWithTag("android_relay_device_id").performTextInput("android")
        composeRule.onNodeWithTag("android_relay_display_name").performTextInput("Amanda")
        composeRule.onNodeWithTag("android_relay_token").performTextInput("super-secret-token")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("android_relay_save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, controller.collection.profiles.size)
        composeRule.onAllNodesWithText("super-secret-token").assertCountEquals(0)
        composeRule.onNodeWithText("wss://relay.example/voice-session")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun a_cleartext_endpoint_is_refused_with_field_level_guidance() {
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
        )

        composeRule.setContent {
            HermesRelayTheme {
                // Host it inside a scrolling column, as the real screen does.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RelayConfigurationScreen(controller = controller, onChanged = {})
                }
            }
        }

        composeRule.onNodeWithTag("android_relay_endpoint")
            .performTextInput("ws://relay.example:8792/voice-session")
        composeRule.onNodeWithTag("android_relay_client_id").performTextInput("amanda-laptop")
        composeRule.onNodeWithTag("android_relay_device_id").performTextInput("android")
        composeRule.onNodeWithTag("android_relay_display_name").performTextInput("Amanda")
        composeRule.onNodeWithTag("android_relay_token").performTextInput("token")
        closeSoftKeyboard()
        composeRule.onNodeWithTag("android_relay_save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(controller.collection.profiles.isEmpty())
        composeRule
            .onNodeWithText(
                "The endpoint must use wss://. A cleartext relay would send the token in the clear.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }
}
