package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileTest {
    @Test
    fun a_wss_endpoint_with_a_host_name_validates() {
        val errors = RelayProfileValidator.validate(
            endpoint = "wss://media-server.example.ts.net/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun a_cleartext_endpoint_is_rejected() {
        val errors = RelayProfileValidator.validate(
            endpoint = "ws://media-server.example.ts.net:8792/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
        )

        assertEquals(RelayProfileError.EndpointNotSecure, errors[RelayProfileField.Endpoint])
    }

    @Test
    fun a_bare_tailnet_address_is_rejected_because_it_cannot_match_a_certificate() {
        val errors = RelayProfileValidator.validate(
            endpoint = "wss://100.90.186.57:8792/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
        )

        assertEquals(RelayProfileError.EndpointBareAddress, errors[RelayProfileField.Endpoint])
    }

    @Test
    fun a_malformed_endpoint_is_rejected() {
        val errors = RelayProfileValidator.validate(
            endpoint = "media-server.example.ts.net",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
        )

        assertEquals(RelayProfileError.EndpointMalformed, errors[RelayProfileField.Endpoint])
    }

    @Test
    fun approved_route_rejects_non_bridge_paths() {
        val routes = listOf(
            "wss://home.example/voice-session",
            "wss://home.example/api/v1/bridge/ws/extra",
            "wss://home.example/api/v1/bridge",
        )

        routes.forEach { route ->
            assertEquals(
                RelayProfileError.EndpointMalformed,
                RelayProfileValidator.validateApprovedHomeRoute(route),
            )
        }
        assertEquals(
            null,
            RelayProfileValidator.validateApprovedHomeRoute("wss://home.example"),
        )
        assertEquals(
            null,
            RelayProfileValidator.validateApprovedHomeRoute(
                "wss://home.example/api/v1/bridge/ws/",
            ),
        )
    }

    @Test
    fun every_blank_identity_field_reports_its_own_error() {
        val errors = RelayProfileValidator.validate(
            endpoint = "",
            clientId = " ",
            deviceId = "",
            displayName = "",
        )

        assertEquals(RelayProfileError.Required, errors[RelayProfileField.Endpoint])
        assertEquals(RelayProfileError.Required, errors[RelayProfileField.ClientId])
        assertEquals(RelayProfileError.Required, errors[RelayProfileField.DeviceId])
        assertEquals(RelayProfileError.Required, errors[RelayProfileField.DisplayName])
    }

    @Test
    fun the_first_saved_profile_becomes_the_selected_one() {
        val controller = controller()

        val errors = controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "relay-token",
        )

        assertTrue(errors.isEmpty())
        assertEquals(1, controller.collection.profiles.size)
        assertEquals("Amanda", controller.collection.selected?.displayName)
    }

    @Test
    fun a_blank_token_reports_a_token_error_and_saves_nothing() {
        val controller = controller()

        val errors = controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "  ",
        )

        assertEquals(RelayProfileError.Required, errors[RelayProfileField.Token])
        assertTrue(controller.collection.profiles.isEmpty())
    }

    @Test
    fun deleting_the_selected_profile_clears_the_selection_and_its_credential() {
        val credentials = InMemoryRelayCredentialStore()
        val controller = controller(credentials = credentials)
        controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "relay-token",
        )
        val id = controller.collection.profiles.single().id
        assertTrue(credentials.hasToken(id))

        controller.delete(id)

        assertTrue(controller.collection.profiles.isEmpty())
        assertNull(controller.collection.selectedId)
        assertTrue("the credential outlived its profile", !credentials.hasToken(id))
    }

    @Test
    fun a_collection_round_trips_through_json_without_a_token() {
        val collection = RelayProfileCollection(
            profiles = listOf(
                RelayProfile("p1", "wss://relay.example/voice-session", "amanda-laptop", "android", "Amanda"),
            ),
            selectedId = "p1",
        )

        val json = collection.toJson()
        val restored = RelayProfileCollection.fromJson(json)

        assertEquals(collection, restored)
        assertTrue("a token must never be serialized", !json.contains("token"))
    }

    @Test
    fun an_unreadable_profile_file_degrades_to_an_empty_collection() {
        assertEquals(RelayProfileCollection(), RelayProfileCollection.fromJson("{ not json"))
        assertEquals(RelayProfileCollection(), RelayProfileCollection.fromJson(""))
    }

    @Test
    fun a_selected_id_naming_a_missing_profile_is_dropped_on_load() {
        val restored = RelayProfileCollection.fromJson(
            """{"profiles":[],"selected_id":"ghost"}""",
        )

        assertNull(restored.selectedId)
    }

    @Test
    fun home_credentials_use_the_opaque_32_byte_base64url_shape() {
        assertTrue(HomeCredentialValidator.isValid("A".repeat(43)))
        assertTrue(!HomeCredentialValidator.isValid("A".repeat(42)))
        assertTrue(!HomeCredentialValidator.isValid("A".repeat(43) + "="))
        assertTrue(!HomeCredentialValidator.isValid("old-hermes-bearer"))
    }

    @Test
    fun home_migration_updates_one_profile_and_keeps_the_legacy_secret_rollback_only() {
        val credentials = InMemoryRelayCredentialStore()
        val profiles = InMemoryRelayProfileStore()
        val controller = RelayConfigurationController(
            profiles = profiles,
            credentials = credentials,
            idFactory = { "profile-1" },
        )
        controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "legacy-hermes-bearer",
        )
        val profileId = controller.collection.selectedId!!
        val pairing = RelayHomePairing(
            approvedRoute = "wss://home.example/",
            deviceCredential = "A".repeat(43),
            conversationHandle = "opaque-conversation-1",
        )

        val first = controller.migrateToHome(profileId, pairing)
        assertTrue(first is RelayHomeMigrationResult.Migrated)
        assertEquals(1, controller.collection.profiles.size)
        assertEquals(profileId, controller.collection.selectedId)
        assertEquals(
            RelayHomeBinding("wss://home.example", "opaque-conversation-1"),
            controller.collection.selected?.homeBinding,
        )
        assertEquals("legacy-hermes-bearer", credentials.readRollbackCredential(profileId))
        assertEquals("A".repeat(43), credentials.readHomeCredential(profileId))

        val second = controller.migrateToHome(
            profileId,
            pairing.copy(
                deviceCredential = "B".repeat(43),
                conversationHandle = "opaque-conversation-2",
            ),
        )
        assertTrue(second is RelayHomeMigrationResult.Migrated)
        assertEquals(1, controller.collection.profiles.size)
        assertEquals("opaque-conversation-2", controller.collection.selected?.homeBinding?.conversationHandle)
        assertEquals("legacy-hermes-bearer", credentials.readRollbackCredential(profileId))
        assertEquals("B".repeat(43), credentials.readHomeCredential(profileId))

        val json = profiles.load().toJson()
        assertTrue(json.contains("opaque-conversation-2"))
        assertTrue(!json.contains("legacy-hermes-bearer"))
        assertTrue(!json.contains("deviceCredential"))
        assertEquals(profiles.load(), RelayProfileCollection.fromJson(json))
    }

    @Test
    fun a_failed_home_migration_leaves_the_source_profile_and_secret_untouched() {
        val credentials = InMemoryRelayCredentialStore()
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
            idFactory = { "profile-1" },
        )
        controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "legacy-hermes-bearer",
        )
        val profileId = controller.collection.selectedId!!
        val before = controller.collection

        val result = controller.migrateToHome(
            profileId,
            RelayHomePairing(
                approvedRoute = "ws://home.example",
                deviceCredential = "not-valid",
                conversationHandle = "opaque-conversation-1",
            ),
        )

        assertEquals(
            RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.RouteInvalid),
            result,
        )
        assertEquals(before, controller.collection)
        assertEquals("legacy-hermes-bearer", credentials.readRollbackCredential(profileId))
        assertTrue(credentials.readHomeCredential(profileId) == null)
    }

    @Test
    fun a_home_secure_write_failure_does_not_publish_the_new_binding() {
        val credentials = HomeWriteFailureCredentialStore()
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
            idFactory = { "profile-1" },
        )
        controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "legacy-hermes-bearer",
        )
        val profileId = controller.collection.selectedId!!
        val before = controller.collection

        val result = controller.migrateToHome(
            profileId,
            RelayHomePairing(
                approvedRoute = "wss://home.example",
                deviceCredential = "A".repeat(43),
                conversationHandle = "opaque-conversation-1",
            ),
        )

        assertEquals(
            RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.SecureStorageUnavailable),
            result,
        )
        assertEquals(before, controller.collection)
        assertEquals("legacy-hermes-bearer", credentials.readRollbackCredential(profileId))
        assertNull(credentials.readHomeCredential(profileId))
    }

    @Test
    fun a_profile_store_failure_restores_the_previous_home_credential() {
        val profile = RelayProfile(
            id = "profile-1",
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
        )
        val profiles = FailingProfileStore(
            RelayProfileCollection(listOf(profile), selectedId = profile.id),
        )
        val credentials = InMemoryRelayCredentialStore(
            homeCredentials = mapOf(profile.id to "B".repeat(43)),
        )
        val controller = RelayConfigurationController(profiles, credentials)

        val result = controller.migrateToHome(
            profile.id,
            RelayHomePairing(
                approvedRoute = "wss://home.example",
                deviceCredential = "A".repeat(43),
                conversationHandle = "opaque-conversation-1",
            ),
        )

        assertEquals(
            RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.SecureStorageUnavailable),
            result,
        )
        assertEquals(profile, controller.collection.selected)
        assertEquals("B".repeat(43), credentials.readHomeCredential(profile.id))
    }

    @Test
    fun serialized_home_binding_round_trips_without_runtime_session_or_secret_fields() {
        val collection = RelayProfileCollection(
            profiles = listOf(
                RelayProfile(
                    id = "p1",
                    endpoint = "wss://legacy.example/voice-session",
                    clientId = "android-client",
                    deviceId = "android",
                    displayName = "Amanda",
                    homeBinding = RelayHomeBinding(
                        approvedRoute = "wss://home.example",
                        conversationHandle = "opaque-conversation-1",
                    ),
                ),
            ),
            selectedId = "p1",
        )

        val json = collection.toJson()
        val restored = RelayProfileCollection.fromJson(json)

        assertEquals(collection, restored)
        assertTrue(!json.contains("session_id"))
        assertTrue(!json.contains("token"))
        assertTrue(!json.contains("credential"))
    }

    private fun controller(
        credentials: RelayCredentialStore = InMemoryRelayCredentialStore(),
    ) = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(),
        credentials = credentials,
        idFactory = { "profile-1" },
    )

    private class HomeWriteFailureCredentialStore : RelayCredentialStore {
        private val delegate = InMemoryRelayCredentialStore()

        override fun put(profileId: String, token: String) = delegate.put(profileId, token)

        override fun hasToken(profileId: String): Boolean = delegate.hasToken(profileId)

        override fun read(profileId: String): String? = delegate.read(profileId)

        override fun putHomeCredential(profileId: String, credential: String): Boolean = false

        override fun readHomeCredential(profileId: String): String? = delegate.readHomeCredential(profileId)

        override fun delete(profileId: String) = delegate.delete(profileId)
    }

    private class FailingProfileStore(
        private val initial: RelayProfileCollection,
    ) : RelayProfileStore {
        override fun load(): RelayProfileCollection = initial

        override fun save(collection: RelayProfileCollection): Boolean = false
    }
}
