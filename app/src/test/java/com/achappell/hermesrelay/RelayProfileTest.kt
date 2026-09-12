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

    private fun controller(
        credentials: RelayCredentialStore = InMemoryRelayCredentialStore(),
    ) = RelayConfigurationController(
        profiles = InMemoryRelayProfileStore(),
        credentials = credentials,
        idFactory = { "profile-1" },
    )
}
