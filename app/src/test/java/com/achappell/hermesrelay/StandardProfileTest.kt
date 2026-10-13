package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardProfileTest {
    @Test fun old_json_classifies_home_links_and_never_infers_standard() {
        val restored = RelayProfileCollection.fromJson("""{"profiles":[
            {"id":"legacy","endpoint":"wss://relay.example/api/ws"},
            {"id":"home","home_binding":{"schema":1,"route":"wss://home.example","conversation_handle":"handle"}},
            {"id":"grant","home_client_grant":{"pairing_id":"pairing","grant_id":"grant"}}
        ]}""")
        assertEquals(listOf(RelayConnectionMode.Legacy, RelayConnectionMode.HomeBridge, RelayConnectionMode.HomeBridge), restored.profiles.map { it.mode })
        assertEquals("legacy", restored.profiles.first().historyKey)
    }

    @Test fun standard_identity_normalizes_endpoint_and_profile_but_never_crosses_modes() {
        val profile = standard()
        assertEquals(profile.historyKey, profile.copy(id = "other", endpoint = " HTTPS://RELAY.EXAMPLE:443/ ", hermesProfile = " ").historyKey)
        assertNotEquals(profile.historyKey, profile.copy(endpoint = "wss://other.example/api/ws").historyKey)
        assertNotEquals(profile.historyKey, profile.copy(hermesProfile = "amanda").historyKey)
        assertEquals("p", profile.copy(mode = RelayConnectionMode.Legacy).historyKey)
        assertEquals("p", profile.copy(mode = RelayConnectionMode.HomeBridge).historyKey)
    }

    @Test fun endpoint_validation_rejects_credentials_fragments_and_encoded_secret_query_keys() {
        listOf("http://relay.example", "ws://relay.example", "wss:///api/ws", "wss://user:pass@relay.example", "wss://relay.example/#fragment", "wss://relay.example?access_token=value", "https://relay.example?%61uthorization=value", "https://relay.example?api_key=value", "https://relay.example:99999").forEach {
            assertTrue(it, StandardEndpoint.validate(it) != null)
        }
        assertNull(StandardEndpoint.validate("https://relay.example/api/ws?region=west"))
        assertEquals("wss://relay.example/api/ws", StandardEndpoint.canonicalize("https://RELAY.example:443/"))
    }

    @Test fun all_four_credential_slots_are_isolated_and_deleted_together() {
        val credentials = InMemoryRelayCredentialStore()
        val home = "A".repeat(43)
        credentials.put("p", "rollback")
        credentials.putHomeCredential("p", home)
        credentials.putHomeAdminCredential("p", "admin")
        assertNull(credentials.readStandardCredential("p"))
        credentials.putStandardCredential("p", "standard")
        assertEquals("rollback", credentials.read("p"))
        assertEquals(home, credentials.readHomeCredential("p"))
        assertEquals("admin", credentials.readHomeAdminCredential("p"))
        assertEquals("standard", credentials.readStandardCredential("p"))
        credentials.deleteStandardCredential("p")
        assertEquals("rollback", credentials.read("p"))
        assertEquals(home, credentials.readHomeCredential("p"))
        credentials.putStandardCredential("p", "standard")
        credentials.delete("p")
        assertNull(credentials.read("p"))
        assertNull(credentials.readHomeCredential("p"))
        assertNull(credentials.readHomeAdminCredential("p"))
        assertNull(credentials.readStandardCredential("p"))
    }

    @Test fun explicit_save_preserves_other_profiles_and_keeps_secret_out_of_json() {
        val legacy = standard().copy(id = "legacy", mode = RelayConnectionMode.Legacy)
        val initial = RelayProfileCollection(listOf(legacy), "legacy")
        val credentials = InMemoryRelayCredentialStore(initial = mapOf("legacy" to "rollback-secret"))
        val controller = RelayConfigurationController(InMemoryRelayProfileStore(initial), credentials, idFactory = { "new" })
        assertTrue(controller.saveStandard("https://RELAY.example:443/", " Standard ", "  ", "private-token").isEmpty())
        assertEquals(legacy, controller.collection.profiles.first())
        assertEquals("legacy", controller.collection.selectedId)
        val profile = controller.collection.profiles.last()
        assertEquals(RelayConnectionMode.Standard, profile.mode)
        assertEquals("default", profile.hermesProfile)
        assertEquals("wss://relay.example/api/ws", profile.endpoint)
        assertEquals("rollback-secret", credentials.read("legacy"))
        assertNull(credentials.read("new"))
        val json = controller.collection.toJson()
        assertFalse(json.contains("private-token"))
        assertEquals(controller.collection, RelayProfileCollection.fromJson(json))
    }

    @Test fun endpoint_change_requires_fresh_entry_and_does_not_reuse_previous_token() {
        val initial = RelayProfileCollection(listOf(standard()), "p")
        val credentials = InMemoryRelayCredentialStore(standardCredentials = mapOf("p" to "old"))
        val controller = RelayConfigurationController(InMemoryRelayProfileStore(initial), credentials)
        assertTrue(controller.saveStandard("https://RELAY.example:443/", "Renamed", "default", "", "p").isEmpty())
        assertEquals(RelayProfileError.Required, controller.saveStandard("wss://other.example/api/ws", "New", "default", "", "p")[RelayProfileField.Token])
        assertEquals("wss://relay.example/api/ws", controller.collection.selected?.endpoint)
        assertEquals("old", credentials.readStandardCredential("p"))
        assertTrue(controller.saveStandard("wss://other.example/api/ws", "New", "default", "fresh", "p").isEmpty())
        assertEquals("fresh", credentials.readStandardCredential("p"))
        assertEquals("wss://other.example/api/ws", controller.collection.selected?.endpoint)
    }

    @Test fun failed_profile_save_restores_old_standard_slot_and_removes_new_orphan() {
        val initial = RelayProfileCollection(listOf(standard()), "p")
        val store = object : RelayProfileStore {
            override fun load() = initial
            override fun save(collection: RelayProfileCollection) = false
        }
        val credentials = InMemoryRelayCredentialStore(standardCredentials = mapOf("p" to "old"))
        val controller = RelayConfigurationController(store, credentials, idFactory = { "new" })
        assertEquals(RelayProfileError.StorageUnavailable, controller.saveStandard("wss://other.example", "New", "default", "fresh", "p")[RelayProfileField.Token])
        assertEquals("old", credentials.readStandardCredential("p"))
        assertEquals(initial, controller.collection)
        assertEquals(RelayProfileError.StorageUnavailable, controller.saveStandard("wss://other.example", "New", "default", "fresh")[RelayProfileField.Token])
        assertNull(credentials.readStandardCredential("new"))
    }

    @Test fun legacy_rollback_is_not_a_standard_credential_and_home_profiles_are_not_overwritten() {
        val legacy = standard().copy(mode = RelayConnectionMode.Legacy)
        val credentials = InMemoryRelayCredentialStore(initial = mapOf("p" to "legacy-secret"))
        val controller = RelayConfigurationController(InMemoryRelayProfileStore(RelayProfileCollection(listOf(legacy), "p")), credentials)
        assertEquals(RelayProfileError.Required, controller.saveStandard(legacy.endpoint, "Standard", "default", "", "p")[RelayProfileField.Token])
        assertTrue(controller.saveStandard(legacy.endpoint, "Standard", "default", "fresh", "p").isEmpty())
        assertEquals("legacy-secret", credentials.read("p"))
        val home = legacy.copy(mode = RelayConnectionMode.HomeBridge, homeBinding = RelayHomeBinding("wss://home.example", "handle"))
        val homeController = RelayConfigurationController(InMemoryRelayProfileStore(RelayProfileCollection(listOf(home), "p")), credentials)
        assertFalse(homeController.saveStandard(legacy.endpoint, "Standard", "default", "fresh", "p").isEmpty())
        assertEquals(home, homeController.collection.selected)
    }

    @Test fun credential_write_failure_does_not_publish_profile_or_touch_rollback() {
        val backing = InMemoryRelayCredentialStore(initial = mapOf("p" to "rollback"))
        val credentials = object : RelayCredentialStore by backing {
            override fun putStandardCredential(profileId: String, credential: String) = false
        }
        val controller = RelayConfigurationController(InMemoryRelayProfileStore(), credentials, idFactory = { "p" })
        assertEquals(RelayProfileError.StorageUnavailable, controller.saveStandard("https://relay.example", "Standard", "", "fresh")[RelayProfileField.Token])
        assertTrue(controller.collection.profiles.isEmpty())
        assertEquals("rollback", backing.read("p"))
        assertNull(backing.readStandardCredential("p"))
    }

    private fun standard() = RelayProfile("p", "wss://relay.example/api/ws", "", "", "Standard", mode = RelayConnectionMode.Standard)
}
