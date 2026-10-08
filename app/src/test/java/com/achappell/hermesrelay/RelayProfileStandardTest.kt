package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileStandardTest {
    private fun standard(
        id: String = "s1",
        endpoint: String = "wss://hermes.example/api/ws",
        hermesProfile: String? = "default",
    ) = RelayProfile(
        id = id,
        endpoint = endpoint,
        clientId = "android",
        deviceId = "",
        displayName = "Standard",
        mode = RelayProfileMode.Standard,
        hermesProfile = hermesProfile,
    )

    private fun legacy(id: String = "p1") = RelayProfile(
        id = id,
        endpoint = "wss://relay.example/voice-session",
        clientId = "c",
        deviceId = "d",
        displayName = "Amanda",
    )

    private fun endpointError(endpoint: String) =
        RelayProfileValidator.validateStandard(endpoint, "default").errors[RelayProfileField.Endpoint]

    // ---- mode ----

    @Test
    fun mode_defaults_to_legacy_without_a_home_link() {
        assertEquals(RelayProfileMode.Legacy, legacy().mode)
    }

    @Test
    fun mode_defaults_to_home_bridge_with_a_binding_or_grant() {
        assertEquals(
            RelayProfileMode.HomeBridge,
            RelayProfile("p", "e", "c", "d", "n", homeBinding = RelayHomeBinding("wss://h", "handle")).mode,
        )
        assertEquals(
            RelayProfileMode.HomeBridge,
            RelayProfile("p", "e", "c", "d", "n", homeClientGrant = RelayHomeClientGrantRef("a", "b")).mode,
        )
    }

    @Test
    fun old_json_without_mode_derives_from_the_home_link() {
        val restored = RelayProfileCollection.fromJson(
            """{"profiles":[
              {"id":"old","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n"},
              {"id":"bound","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n",
               "home_binding":{"schema":1,"route":"wss://h","conversation_handle":"x"}},
              {"id":"granted","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n",
               "home_client_grant":{"pairing_id":"p","grant_id":"g"}},
              {"id":"adminonly","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n",
               "home_administration":{"schema":1,"phase":"Approved","device_id":"dev"}}
            ]}""",
        )
        val modes = restored.profiles.associate { it.id to it.mode }
        assertEquals(RelayProfileMode.Legacy, modes["old"])
        assertEquals(RelayProfileMode.HomeBridge, modes["bound"])
        assertEquals(RelayProfileMode.HomeBridge, modes["granted"])
        assertEquals(RelayProfileMode.Legacy, modes["adminonly"])
    }

    @Test
    fun unknown_or_standard_mode_in_json_never_classifies_a_plain_profile_as_standard() {
        val restored = RelayProfileCollection.fromJson(
            """{"profiles":[
              {"id":"u","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n","mode":"quantum"},
              {"id":"l","endpoint":"wss://a/x","client_id":"c","device_id":"d","display_name":"n","mode":"legacy"}
            ]}""",
        )
        assertTrue(restored.profiles.all { it.mode == RelayProfileMode.Legacy })
    }

    @Test
    fun mode_and_hermes_profile_round_trip() {
        val collection = RelayProfileCollection(
            profiles = listOf(
                legacy(),
                standard(hermesProfile = "work"),
                RelayProfile("h", "wss://h/x", "c", "d", "n", mode = RelayProfileMode.HomeBridge),
            ),
            selectedId = "s1",
        )
        val json = collection.toJson()
        assertTrue(json.contains("\"standard\""))
        assertTrue(json.contains("\"home_bridge\""))
        assertTrue(json.contains("\"legacy\""))
        assertEquals(collection, RelayProfileCollection.fromJson(json))
    }

    @Test
    fun a_standard_profile_loads_without_its_home_fields_and_blank_hermes_profile_is_default() {
        val restored = RelayProfileCollection.fromJson(
            """{"profiles":[{"id":"s","endpoint":"wss://a/api/ws","client_id":"android","device_id":"","display_name":"n",
              "mode":"standard","hermes_profile":"  ",
              "home_binding":{"schema":1,"route":"wss://h","conversation_handle":"x"},
              "home_administration":{"schema":1,"phase":"Approved","device_id":"dev"},
              "home_client_grant":{"pairing_id":"p","grant_id":"g"}}]}""",
        )
        val profile = restored.profiles.single()
        assertEquals(RelayProfileMode.Standard, profile.mode)
        assertEquals("default", profile.hermesProfile)
        assertNull(profile.homeBinding)
        assertNull(profile.homeAdministration)
        assertNull(profile.homeClientGrant)
    }

    // ---- history key ----

    @Test
    fun a_non_standard_history_key_is_the_profile_id() {
        assertEquals("p1", legacy("p1").historyKey)
        assertEquals(
            "h",
            RelayProfile("h", "wss://h/x", "c", "d", "n", mode = RelayProfileMode.HomeBridge).historyKey,
        )
    }

    @Test
    fun a_standard_history_key_is_stable_and_never_the_profile_id() {
        val key = standard(id = "s1").historyKey
        assertTrue(key.startsWith("std-"))
        assertEquals(44, key.length)
        assertTrue(key.removePrefix("std-").all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(key, standard(id = "s1").historyKey)
        // The id is not an input: re-creating the same identity keeps its history.
        assertEquals(key, standard(id = "other-id").historyKey)
        assertEquals(key, RelayProfile.standardHistoryKey("wss://hermes.example/api/ws", "default"))
    }

    @Test
    fun host_case_and_trailing_slash_do_not_change_the_standard_history_key() {
        val base = standard(endpoint = "wss://hermes.example/api/ws").historyKey
        assertEquals(base, standard(endpoint = "WSS://Hermes.Example/api/ws").historyKey)
        assertEquals(base, standard(endpoint = "wss://hermes.example/api/ws/").historyKey)
    }

    @Test
    fun hermes_profile_endpoint_port_and_mode_each_change_the_history_key() {
        val base = standard().historyKey
        assertNotEquals(base, standard(hermesProfile = "work").historyKey)
        assertNotEquals(base, standard(endpoint = "wss://other.example/api/ws").historyKey)
        assertNotEquals(base, standard(endpoint = "wss://hermes.example:9443/api/ws").historyKey)
        // A Home profile on the same endpoint keeps its id, so it cannot share a Standard key.
        val home = RelayProfile(
            "s1", "wss://hermes.example/api/ws", "c", "d", "n", mode = RelayProfileMode.HomeBridge,
        )
        assertNotEquals(base, home.historyKey)
        assertEquals("s1", home.historyKey)
    }

    // ---- validation ----

    @Test
    fun a_valid_standard_endpoint_is_normalized() {
        val result = RelayProfileValidator.validateStandard("  HTTPS://Hermes.Example:8443/api/ws/  ", " work ")
        assertTrue(result.errors.isEmpty())
        assertEquals("wss://hermes.example:8443/api/ws", result.endpoint)
        assertEquals("work", result.hermesProfile)
        assertEquals("/api/ws", RelayProfileValidator.STANDARD_GATEWAY_PATH)
    }

    @Test
    fun wss_in_any_case_and_a_plain_path_are_accepted() {
        assertNull(endpointError("WSS://hermes.example/api/ws"))
        assertNull(endpointError("wss://hermes.example/api/ws/"))
        assertEquals(
            "wss://hermes.example:1/api/ws",
            RelayProfileValidator.validateStandard("wss://hermes.example:1/api/ws", "").endpoint,
        )
    }

    @Test
    fun cleartext_and_other_schemes_are_not_secure() {
        assertEquals(RelayProfileError.EndpointNotSecure, endpointError("http://hermes.example/api/ws"))
        assertEquals(RelayProfileError.EndpointNotSecure, endpointError("ws://hermes.example/api/ws"))
        assertEquals(RelayProfileError.EndpointNotSecure, endpointError("ftp://hermes.example/api/ws"))
    }

    @Test
    fun blank_schemeless_and_unparseable_endpoints_fail() {
        assertEquals(RelayProfileError.Required, endpointError("   "))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("hermes.example/api/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("hermes.example:8443/api/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://her mes.example/api/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss:///api/ws"))
    }

    @Test
    fun userinfo_is_a_carried_credential() {
        assertEquals(
            RelayProfileError.EndpointCarriesCredential,
            endpointError("wss://user:pw@hermes.example/api/ws"),
        )
        assertEquals(
            RelayProfileError.EndpointCarriesCredential,
            endpointError("wss://abc@hermes.example/api/ws"),
        )
    }

    @Test
    fun credential_like_query_keys_are_carried_credentials() {
        listOf(
            "token=x", "access_token=x", "API_KEY=x", "apikey=x", "key=x", "secret=x", "password=x",
            "auth=x", "authorization=x", "bearer=x", "jwt=x", "credential=x", "credentials=x",
            "sig=x", "signature=x", "my_token=x", "clientSecret=x", "pass_password=x",
            "tok%65n=x", "access%5Ftoken=x", "a=1&token=2", "token",
        ).forEach { query ->
            assertEquals(
                query,
                RelayProfileError.EndpointCarriesCredential,
                endpointError("wss://hermes.example/api/ws?$query"),
            )
        }
    }

    @Test
    fun any_other_query_is_malformed_because_the_client_adds_its_own() {
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws?profile=work"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws?a=b"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws?"))
    }

    @Test
    fun any_fragment_is_malformed() {
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws#x"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws#"))
    }

    @Test
    fun a_bare_address_is_rejected() {
        assertEquals(RelayProfileError.EndpointBareAddress, endpointError("wss://192.168.1.5/api/ws"))
        assertEquals(RelayProfileError.EndpointBareAddress, endpointError("wss://[::1]:8443/api/ws"))
    }

    @Test
    fun the_path_must_be_exactly_the_gateway_path() {
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/v1/bridge/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/prefix/api/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/API/ws"))
        assertEquals(RelayProfileError.EndpointMalformed, endpointError("wss://hermes.example/api/ws/extra"))
    }

    @Test
    fun hermes_profile_rules() {
        fun profileError(value: String) =
            RelayProfileValidator.validateStandard("wss://hermes.example/api/ws", value)
                .errors[RelayProfileField.HermesProfile]

        assertEquals("default", RelayProfileValidator.validateStandard("wss://hermes.example/api/ws", "  ").hermesProfile)
        assertNull(profileError(""))
        assertNull(profileError("work.v2_a-b"))
        assertNull(profileError("a".repeat(64)))
        assertEquals(RelayProfileError.HermesProfileInvalid, profileError("a".repeat(65)))
        assertEquals(RelayProfileError.HermesProfileInvalid, profileError("has space"))
        assertEquals(RelayProfileError.HermesProfileInvalid, profileError("a/b"))
        assertEquals(RelayProfileError.HermesProfileInvalid, profileError("é"))
    }

    @Test
    fun endpoint_and_hermes_profile_errors_are_reported_together() {
        val result = RelayProfileValidator.validateStandard("http://x.example/api/ws", "no good")
        assertEquals(RelayProfileError.EndpointNotSecure, result.errors[RelayProfileField.Endpoint])
        assertEquals(RelayProfileError.HermesProfileInvalid, result.errors[RelayProfileField.HermesProfile])
        assertFalse(result.isValid)
    }
}
