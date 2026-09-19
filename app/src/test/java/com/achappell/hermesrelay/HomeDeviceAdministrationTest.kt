package com.achappell.hermesrelay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeDeviceAdministrationTest {
    @Test
    fun discovery_is_side_effect_free_and_does_not_authorize_a_device() {
        val fixture = fixture()
        val device = fixture.controller.discover("android-1", "Kitchen phone")

        assertEquals("android-1", device.endpointId)
        assertEquals(0, fixture.client.factoryCalls)
        assertNull(fixture.credentials.readHomeCredential("profile-1"))
        assertEquals(RelayHomeAdministrationPhase.Discovered, fixture.controller.state.phase)
    }

    @Test
    fun home_http_separates_admin_and_device_authority() {
        val requests = mutableListOf<HomeHttpRequest>()
        val snapshot = validSnapshot()
        val transport = HomeHttpTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/v1/configuration") -> HomeHttpResponse(
                    200,
                    responseSnapshot(snapshot),
                )
                request.url.endsWith("/api/v1/devices/device-1/configuration") ->
                    HomeHttpResponse(
                        200,
                        JSONObject()
                            .put("schema", 1)
                            .put(
                                "snapshot",
                                JSONObject()
                                    .put("revision", 1)
                                    .put(
                                        "wake_mappings",
                                        org.json.JSONArray().put(
                                            JSONObject()
                                                .put("id", "hey-hermes")
                                                .put("phrase", "Hey Hermes"),
                                        ),
                                    ),
                            )
                            .toString(),
                    )
                else -> HomeHttpResponse(404, errorResponse("not_found"))
            }
        }
        val client = OkHttpHomeDeviceAdministration(
            approvedRoute = "wss://home.example/api/v1/bridge/ws",
            adminCredential = "admin-secret",
            deviceCredential = VALID_CREDENTIAL,
            transport = transport,
        )

        client.fetchConfiguration()
        client.fetchDeviceConfiguration("device-1")

        assertEquals("https://home.example/api/v1/configuration", requests[0].url)
        assertEquals("Bearer admin-secret", requests[0].headers["Authorization"])
        assertEquals(
            "Device $VALID_CREDENTIAL",
            requests[1].headers["Authorization"],
        )
    }

    @Test
    fun consuming_approved_enrollment_preserves_profile_identity_and_stores_device_credential() {
        val fixture = fixture()
        val device = fixture.controller.discover("android-1", "Kitchen phone")
        assertEquals("android-1", device.endpointId)
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest(
            offerCode = "offer-code",
            requestedRooms = listOf("kitchen"),
            requestedCapabilities = listOf("wake_claim"),
            requestedProfileMappings = emptyList(),
        )
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(
                rooms = listOf("kitchen"),
                capabilities = listOf("wake_claim"),
                wakeMappings = listOf("hey-hermes"),
            ),
        )
        fixture.controller.consumeEnrollment("offer-code")

        val profile = fixture.relay.collection.selected!!
        assertEquals("profile-1", profile.id)
        assertEquals("Amanda", profile.displayName)
        assertEquals(RelayHomeAdministrationPhase.Approved, profile.homeAdministration?.phase)
        assertEquals(VALID_CREDENTIAL, fixture.credentials.readHomeCredential("profile-1"))
        assertEquals("profile-1", fixture.relay.collection.selectedId)
    }

    @Test
    fun stale_revision_is_typed_and_does_not_replace_the_loaded_configuration() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.controller.consumeEnrollment("offer-code")
        val loaded = fixture.controller.fetchConfiguration()
        fixture.client.publishFailure = HomeAdministrationException(HomeAdministrationError.RevisionConflict)

        val error = assertThrows(HomeAdministrationException::class.java) {
            fixture.controller.publishConfiguration(loaded)
        }

        assertEquals(HomeAdministrationError.RevisionConflict, error.reason)
        assertEquals(loaded, fixture.controller.state.configuration)
        assertEquals(RelayHomeAdministrationPhase.StaleRevision, fixture.controller.state.phase)
    }

    @Test
    fun publish_requires_device_verification_before_ready() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.controller.consumeEnrollment("offer-code")
        val loaded = fixture.controller.fetchConfiguration()
        fixture.client.deviceConfigurationRevision = 0

        val error = assertThrows(HomeAdministrationException::class.java) {
            fixture.controller.publishConfiguration(loaded)
        }

        assertEquals(HomeAdministrationError.RevisionConflict, error.reason)
        assertEquals(RelayHomeAdministrationPhase.StaleRevision, fixture.controller.state.phase)
    }

    @Test
    fun successful_publish_requires_matching_device_mappings_before_ready() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.controller.consumeEnrollment("offer-code")
        val loaded = fixture.controller.fetchConfiguration()
        fixture.client.deviceConfigurationRevision = 2

        val published = fixture.controller.publishConfiguration(loaded)

        assertEquals(2, published.revision)
        assertEquals(RelayHomeAdministrationPhase.Ready, fixture.controller.state.phase)
        assertEquals(
            2,
            fixture.relay.collection.selected?.homeAdministration?.configurationRevision,
        )
    }

    @Test
    fun raw_revision_conflict_reaches_the_controller_as_stale_revision() {
        val credentials = InMemoryRelayCredentialStore(
            homeCredentials = mapOf("profile-1" to VALID_CREDENTIAL),
            homeAdminCredentials = mapOf("profile-1" to "admin-secret"),
        )
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
            homeAdministration = RelayHomeAdministration(
                phase = RelayHomeAdministrationPhase.Ready,
                deviceId = "device-1",
                generation = 1,
                credentialExpiresAt = 2_000.0,
                requestId = "request-1",
            ),
        )
        val relay = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(RelayProfileCollection(listOf(profile), profile.id)),
            credentials = credentials,
        )
        val transport = HomeHttpTransport {
            HomeHttpResponse(409, errorResponse("revision_conflict"))
        }
        val controller = HomeDeviceAdministrationController(
            relayConfiguration = relay,
            credentials = credentials,
            clientFactory = { route, admin, device ->
                OkHttpHomeDeviceAdministration(route, admin, device, transport)
            },
            nowEpochSeconds = { 1_000.0 },
        )

        val error = assertThrows(HomeAdministrationException::class.java) {
            controller.publishConfiguration(validSnapshot())
        }

        assertEquals(HomeAdministrationError.RevisionConflict, error.reason)
        assertEquals(RelayHomeAdministrationPhase.StaleRevision, controller.state.phase)
    }

    @Test
    fun revocation_removes_the_device_secret_and_marks_the_profile_revoked() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.controller.consumeEnrollment("offer-code")

        fixture.controller.revokeDevice("retired")

        assertNull(fixture.credentials.readHomeCredential("profile-1"))
        assertEquals(RelayHomeAdministrationPhase.Revoked, fixture.relay.collection.selected?.homeAdministration?.phase)
    }

    @Test
    fun expired_enrollment_is_explicit_and_does_not_store_a_device_credential() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.client.nextCredentialExpiresAt = 500.0

        val error = assertThrows(HomeAdministrationException::class.java) {
            fixture.controller.consumeEnrollment("offer-code")
        }

        assertEquals(HomeAdministrationError.Expired, error.reason)
        assertNull(fixture.credentials.readHomeCredential("profile-1"))
        assertEquals(RelayHomeAdministrationPhase.Expired, fixture.controller.state.phase)
    }

    @Test
    fun explicit_reenrollment_replaces_the_credential_and_returns_to_setup_pending() {
        val fixture = fixture()
        fixture.controller.discover("android-1", "Kitchen phone")
        fixture.controller.saveAdminCredential("admin-secret")
        fixture.controller.submitEnrollmentRequest("offer-code", listOf("kitchen"), listOf("wake_claim"))
        fixture.controller.approveEnrollmentRequest(
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        fixture.controller.consumeEnrollment("offer-code")

        fixture.client.nextCredential = "B".repeat(43)
        fixture.controller.reEnrollDevice()

        assertEquals("B".repeat(43), fixture.credentials.readHomeCredential("profile-1"))
        assertEquals(
            RelayHomeAdministrationPhase.SetupPending,
            fixture.relay.collection.selected?.homeAdministration?.phase,
        )
        assertEquals("profile-1", fixture.relay.collection.selectedId)
    }

    @Test
    fun production_home_client_covers_lifecycle_routes_and_authorities() {
        val requests = mutableListOf<HomeHttpRequest>()
        val snapshot = validSnapshot()
        val transport = HomeHttpTransport { request ->
            requests += request
            when {
                request.url.endsWith("/api/v1/enrollment/offers") -> HomeHttpResponse(
                    200,
                    JSONObject()
                        .put("schema", 1)
                        .put("offer_id", "offer-1")
                        .put("enrollment_code", "OFFER")
                        .put("expires_at", 2_000.0)
                        .toString(),
                )
                request.url.endsWith("/api/v1/enrollment/requests") && request.method == "POST" ->
                    HomeHttpResponse(
                        200,
                        JSONObject()
                            .put("schema", 1)
                            .put("request_id", "request-1")
                            .put("confirmation_code", "ABCD2345")
                            .put("expires_at", 2_000.0)
                            .toString(),
                    )
                request.url.endsWith("/approve") -> HomeHttpResponse(
                    200,
                    JSONObject()
                        .put("schema", 1)
                        .put("request", requestJson("approved"))
                        .toString(),
                )
                request.url.endsWith("/consume") -> HomeHttpResponse(200, materialResponse())
                request.url.contains("/revoke") -> HomeHttpResponse(204, "")
                request.url.contains("/credentials/renew") -> HomeHttpResponse(200, materialResponse())
                request.url.contains("/credentials/rotate") -> HomeHttpResponse(
                    200,
                    materialResponse(generation = 2),
                )
                request.method == "GET" && request.url.endsWith("/api/v1/configuration") ->
                    HomeHttpResponse(200, responseSnapshot(snapshot))
                request.method == "PUT" && request.url.endsWith("/api/v1/configuration") ->
                    HomeHttpResponse(200, responseSnapshot(snapshot.copy(revision = 2)))
                request.url.endsWith("/api/v1/devices/device-1/configuration") ->
                    HomeHttpResponse(200, deviceConfigurationResponse(2))
                else -> HomeHttpResponse(404, errorResponse("not_found"))
            }
        }
        val client = OkHttpHomeDeviceAdministration(
            approvedRoute = "wss://home.example/api/v1/bridge/ws",
            adminCredential = "admin-secret",
            deviceCredential = VALID_CREDENTIAL,
            transport = transport,
        )

        client.createOffer()
        client.submitEnrollmentRequest(
            offerCode = "OFFER",
            device = HomeDiscoveredDevice("android-1", "Kitchen phone"),
            requestedRooms = listOf("kitchen"),
            requestedCapabilities = listOf("wake_claim"),
        )
        client.approveEnrollmentRequest(
            "request-1",
            HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
        client.consumeEnrollment("request-1", "OFFER")
        client.revokeDevice("device-1", "retired")
        client.renewDevice("device-1", "request-1", 1)
        client.rotateDevice("device-1", "request-1", 1)
        client.fetchConfiguration()
        client.publishConfiguration(snapshot, 1)
        client.fetchDeviceConfiguration("device-1")

        assertEquals(10, requests.size)
        assertEquals("Bearer admin-secret", requests[0].headers["Authorization"])
        assertNull(requests[1].headers["Authorization"])
        assertEquals("OFFER", JSONObject(requests[1].body!!).getString("enrollment_code"))
        assertEquals("Bearer admin-secret", requests[2].headers["Authorization"])
        assertNull(requests[3].headers["Authorization"])
        assertEquals("Bearer admin-secret", requests[4].headers["Authorization"])
        assertEquals("Device $VALID_CREDENTIAL", requests[5].headers["Authorization"])
        assertEquals("Bearer admin-secret", requests[6].headers["Authorization"])
        assertEquals(1, JSONObject(requests[8].body!!).getInt("expected_revision"))
        assertFalse(JSONObject(requests[8].body!!).getJSONObject("snapshot").has("revision"))
        assertEquals("Device $VALID_CREDENTIAL", requests[9].headers["Authorization"])
    }

    @Test
    fun administration_metadata_round_trips_without_secret_material() {
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
            homeAdministration = RelayHomeAdministration(
                phase = RelayHomeAdministrationPhase.Ready,
                deviceId = "device-1",
                generation = 2,
                configurationRevision = 7,
                requestId = "request-1",
            ),
        )
        val json = RelayProfileCollection(listOf(profile), profile.id).toJson()
        val restored = RelayProfileCollection.fromJson(json).selected!!

        assertEquals(profile.homeAdministration, restored.homeAdministration)
        assertFalse(json.contains("credential"))
        assertFalse(json.contains("AAAAAAAA"))
    }

    private fun fixture(): Fixture {
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
        val credentials = InMemoryRelayCredentialStore()
        val relay = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(
                RelayProfileCollection(listOf(profile), selectedId = profile.id),
            ),
            credentials = credentials,
        )
        val client = FakeHomeClient()
        val controller = HomeDeviceAdministrationController(
            relayConfiguration = relay,
            credentials = credentials,
            clientFactory = { _, _, _ ->
                client.factoryCalls += 1
                client
            },
            nowEpochSeconds = { 1_000.0 },
        )
        return Fixture(relay, credentials, client, controller)
    }

    private data class Fixture(
        val relay: RelayConfigurationController,
        val credentials: InMemoryRelayCredentialStore,
        val client: FakeHomeClient,
        val controller: HomeDeviceAdministrationController,
    )

    private class FakeHomeClient : HomeDeviceAdministrationClient {
        var factoryCalls = 0
        var publishFailure: HomeAdministrationException? = null
        var consumeFailure: HomeAdministrationException? = null
        var deviceConfigurationRevision = 1
        var nextCredential = VALID_CREDENTIAL
        var nextCredentialExpiresAt = 2_000.0

        override fun createOffer(expiresInSeconds: Int) = HomeEnrollmentOffer("offer-1", "offer-code", 10.0)

        override fun submitEnrollmentRequest(
            offerCode: String,
            device: HomeDiscoveredDevice,
            requestedRooms: List<String>,
            requestedCapabilities: List<String>,
            requestedProfileMappings: List<HomeProfileMapping>,
        ) = HomeEnrollmentSubmission("request-1", "ABCD2345", 10.0)

        override fun listEnrollmentRequests() = emptyList<HomeEnrollmentRequest>()

        override fun approveEnrollmentRequest(requestId: String, scope: HomeCredentialScope) =
            HomeEnrollmentRequest(
                requestId,
                "offer-1",
                "android-1",
                "Kitchen phone",
                "android",
                scope,
                emptyList(),
                "platform_secure_store",
                "ABCD2345",
                10.0,
                "approved",
                scope,
            )

        override fun consumeEnrollment(requestId: String, offerCode: String): HomeDeviceCredentialMaterial {
            consumeFailure?.let { throw it }
            return material(nextCredential)
        }

        override fun revokeDevice(deviceId: String, reason: String?) = Unit

        override fun renewDevice(deviceId: String, requestId: String, generation: Int) = material(nextCredential)

        override fun rotateDevice(deviceId: String, requestId: String, generation: Int) =
            material(nextCredential, generation + 1)

        override fun fetchConfiguration() = validSnapshot()

        override fun publishConfiguration(
            snapshot: HomeConfigurationSnapshot,
            expectedRevision: Int,
        ): HomeConfigurationSnapshot {
            publishFailure?.let { throw it }
            return snapshot.copy(revision = snapshot.revision + 1)
        }

        override fun fetchDeviceConfiguration(deviceId: String) = HomeDeviceConfigurationSnapshot(
            deviceConfigurationRevision,
            listOf(HomeDeviceWakeMapping("hey-hermes", "Hey Hermes")),
        )

        private fun material(credential: String, generation: Int = 1) = HomeDeviceCredentialMaterial(
            deviceId = "device-1",
            credential = credential,
            generation = generation,
            expiresAt = nextCredentialExpiresAt,
            scope = HomeCredentialScope(listOf("kitchen"), listOf("wake_claim"), listOf("hey-hermes")),
        )
    }

    companion object {
        private const val VALID_CREDENTIAL = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        private fun validSnapshot() = HomeConfigurationSnapshot(
            revision = 1,
            rooms = listOf(HomeRoom("kitchen", "Kitchen")),
            profiles = listOf(HomeProfile("family", "Family", true)),
            wakeMappings = listOf(HomeWakeMapping("hey-hermes", "Hey Hermes", "family", true)),
            devices = listOf(HomeConfiguredDevice("device-1", "Kitchen phone", "kitchen", 1, true)),
        )

        private fun responseSnapshot(snapshot: HomeConfigurationSnapshot): String = JSONObject()
            .put("schema", 1)
            .put("snapshot", snapshot.candidateJson().put("revision", snapshot.revision))
            .toString()

        private fun requestJson(status: String): JSONObject = JSONObject()
            .put("request_id", "request-1")
            .put("offer_id", "offer-1")
            .put("endpoint_id", "android-1")
            .put("label", "Kitchen phone")
            .put("type", "android")
            .put(
                "requested_scope",
                JSONObject()
                    .put("rooms", org.json.JSONArray().put("kitchen"))
                    .put("capabilities", org.json.JSONArray().put("wake_claim"))
                    .put("wake_mappings", org.json.JSONArray().put("hey-hermes")),
            )
            .put("requested_profile_mappings", org.json.JSONArray())
            .put("secure_storage", "platform_secure_store")
            .put("confirmation_code", "ABCD2345")
            .put("expires_at", 2_000.0)
            .put("status", status)
            .put(
                "approved_scope",
                JSONObject()
                    .put("rooms", org.json.JSONArray().put("kitchen"))
                    .put("capabilities", org.json.JSONArray().put("wake_claim"))
                    .put("wake_mappings", org.json.JSONArray().put("hey-hermes")),
            )

        private fun materialResponse(generation: Int = 1): String = JSONObject()
            .put("schema", 1)
            .put("device_id", "device-1")
            .put("credential", VALID_CREDENTIAL)
            .put("generation", generation)
            .put("expires_at", 2_000.0)
            .put("scope", JSONObject()
                .put("rooms", org.json.JSONArray().put("kitchen"))
                .put("capabilities", org.json.JSONArray().put("wake_claim"))
                .put("wake_mappings", org.json.JSONArray().put("hey-hermes")))
            .toString()

        private fun deviceConfigurationResponse(revision: Int): String = JSONObject()
            .put("schema", 1)
            .put(
                "snapshot",
                JSONObject()
                    .put("revision", revision)
                    .put(
                        "wake_mappings",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id", "hey-hermes")
                                .put("phrase", "Hey Hermes"),
                        ),
                    ),
            )
            .toString()

        private fun errorResponse(code: String): String = JSONObject()
            .put("schema", 1)
            .put("error", JSONObject().put("code", code))
            .toString()
    }
}
