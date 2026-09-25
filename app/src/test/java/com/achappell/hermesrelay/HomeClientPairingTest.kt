package com.achappell.hermesrelay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeClientPairingTest {

    // --- Link and typed-code parsing -------------------------------------

    @Test
    fun an_encoded_pairing_link_yields_the_https_home_and_normalized_code() {
        val parsed = HomePairingLink.parse(
            "hermes-home://pair?home=https%3A%2F%2FHome.Example.ts.net&code=k7q-4mx2pnv",
        ) as HomePairingInput.Parsed

        assertEquals("https://home.example.ts.net", parsed.target.homeUrl)
        assertEquals("K7Q4MX2PNV", parsed.target.code)
        assertEquals("home.example.ts.net", parsed.target.host)
        assertFalse(parsed.target.toString().contains("K7Q4MX2PNV"))
    }

    @Test
    fun malformed_or_ambiguous_links_are_refused_before_anything_is_submitted() {
        listOf(
            "https://home.example.ts.net/pair?code=K7Q4MX",
            "hermes-home://other?home=https%3A%2F%2Fhome.example.ts.net&code=K7Q4MX",
            "hermes-home://pair?code=K7Q4MX",
            "hermes-home://pair?home=https%3A%2F%2Fhome.example.ts.net",
            "hermes-home://pair?home=https%3A%2F%2Fa.ts.net&home=https%3A%2F%2Fb.ts.net&code=K7Q4MX",
            "hermes-home://pair?home=https%3A%2F%2Fhome.example.ts.net&code=K7Q4MX#x",
        ).forEach { link ->
            assertEquals(
                link,
                HomePairingInput.Invalid(HomePairingInputError.NotPairingLink),
                HomePairingLink.parse(link),
            )
        }
    }

    @Test
    fun only_an_https_host_name_is_accepted_as_a_home_address() {
        listOf(
            "http://home.example.ts.net",
            "https://100.106.8.34",
            "https://localhost:8443",
            "https://user@home.example.ts.net",
            "https://home.example.ts.net/api",
            "https://home.example.ts.net?x=1",
            "home.example.ts.net",
        ).forEach { address ->
            assertNull(address, HomePairingLink.normalizeHomeUrl(address))
        }
        assertEquals(
            "https://home.example.ts.net",
            HomePairingLink.normalizeHomeUrl("https://home.example.ts.net:443/"),
        )
        assertEquals(
            "https://home.example.ts.net:8443",
            HomePairingLink.normalizeHomeUrl(" https://home.example.ts.net:8443 "),
        )
    }

    @Test
    fun typed_codes_accept_any_case_and_an_optional_dash() {
        val parsed = HomePairingLink.fromTyped(" k7q-4mx ", "https://home.example.ts.net")
            as HomePairingInput.Parsed
        assertEquals("K7Q4MX", parsed.target.code)
        assertEquals(
            HomePairingInput.Invalid(HomePairingInputError.CodeInvalid),
            HomePairingLink.fromTyped("K7!", "https://home.example.ts.net"),
        )
        assertEquals(
            HomePairingInput.Invalid(HomePairingInputError.HomeAddressInvalid),
            HomePairingLink.fromTyped("K7Q4MX", "http://home.example.ts.net"),
        )
    }

    @Test
    fun the_bridge_route_is_derived_from_the_home_authority() {
        assertEquals(
            "wss://home.example.ts.net:8443/api/v1/bridge/ws",
            HomePairingLink.bridgeRoute("https://home.example.ts.net:8443"),
        )
        assertEquals(
            null,
            RelayProfileValidator.validateApprovedHomeRoute(
                HomePairingLink.bridgeRoute(HOME_URL),
            ),
        )
    }

    // --- Wire client -----------------------------------------------------

    @Test
    fun enrollment_requests_only_a_client_claim_without_rooms_or_authorization() {
        val transport = RecordingTransport(
            HomeHttpResponse(
                200,
                """{"schema":1,"request_id":"req-1","confirmation_code":"482913","expires_at":1000.0}""",
            ),
        )
        val submission = HttpHomeClientService(transport).submit(
            HomePairingTarget(HOME_URL, "K7Q4MX"),
            endpointId = "endpoint-1",
            label = "Pixel 6a",
        )

        assertEquals("req-1", submission.requestId)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("$HOME_URL/api/v1/enrollment/requests", request.url)
        assertFalse(request.headers.containsKey("Authorization"))
        val body = JSONObject(request.body!!)
        assertEquals("android", body.getString("type"))
        assertEquals("K7Q4MX", body.getString("enrollment_code"))
        assertEquals("endpoint-1", body.getString("endpoint_id"))
        assertEquals(0, body.getJSONArray("requested_rooms").length())
        assertEquals("client_claim", body.getJSONArray("requested_capabilities").getString(0))
        assertEquals(1, body.getJSONArray("requested_capabilities").length())
        assertEquals("platform_secure_store", body.getString("secure_storage"))
    }

    @Test
    fun consume_maps_pending_rejected_expired_and_approved() {
        fun consume(status: Int, body: String) = HttpHomeClientService(
            RecordingTransport(HomeHttpResponse(status, body)),
        ).consume(HOME_URL, "req-1", "K7Q4MX")

        assertEquals(HomeConsumeResult.Pending, consume(409, error("approval_pending")))
        assertEquals(HomeConsumeResult.Rejected, consume(403, error("rejected")))
        assertEquals(HomeConsumeResult.Expired, consume(410, error("expired_or_consumed")))

        val approved = consume(200, materialJson(grants = GRANTS_JSON)) as HomeConsumeResult.Approved
        assertEquals(DEVICE_ID, approved.material.deviceId)
        assertEquals(VALID_CREDENTIAL, approved.material.credential)
        assertEquals(
            listOf(
                HomeClientGrant("grant-a", "Amanda", HomeClientGrantStatus.Active, true),
                HomeClientGrant("grant-j", "Jensen", HomeClientGrantStatus.PendingOwner, true),
            ),
            approved.grants,
        )
    }

    @Test
    fun a_malformed_credential_from_consume_is_refused() {
        val transport = RecordingTransport(
            HomeHttpResponse(200, materialJson(credential = "short")),
        )
        try {
            HttpHomeClientService(transport).consume(HOME_URL, "req-1", "K7Q4MX")
            fail("expected InvalidResponse")
        } catch (error: HomeAdministrationException) {
            assertEquals(HomeAdministrationError.InvalidResponse, error.reason)
        }
    }

    @Test
    fun a_claim_names_only_the_grant_and_starts_a_new_session() {
        val transport = RecordingTransport(
            HomeHttpResponse(
                200,
                """{"schema":1,"claim_id":"client-1","decision":"granted","configuration_revision":13,""" +
                    """"conversation_handle":"opaque-home-claim-1","session":{"mode":"new"}}""",
            ),
        )
        val result = HttpHomeClientService(transport).claim(
            HOME_URL,
            VALID_CREDENTIAL,
            DEVICE_ID,
            revision = 13,
            grantId = "grant-a",
            claimId = "client-1",
        )

        assertEquals("opaque-home-claim-1", (result as HomeClientClaimResult.Granted).conversationHandle)
        val request = transport.requests.single()
        assertEquals("$HOME_URL/api/v1/client-claims", request.url)
        assertEquals("Device $VALID_CREDENTIAL", request.headers["Authorization"])
        val body = JSONObject(request.body!!)
        assertEquals(
            setOf("schema", "claim_id", "device_id", "configuration_revision", "grant_id", "session"),
            body.keys().asSequence().toSet(),
        )
        assertEquals("new", body.getJSONObject("session").getString("mode"))
        assertFalse(result.toString().contains("opaque-home-claim-1"))
    }

    @Test
    fun claim_denials_are_typed_and_unauthorized_is_an_error() {
        listOf(
            409 to "grant_pending",
            409 to "stale_configuration",
            409 to "claim_limit",
            403 to "client_claim_unavailable",
            409 to "profile_unavailable",
        ).forEach { (status, code) ->
            val result = HttpHomeClientService(
                RecordingTransport(HomeHttpResponse(status, error(code))),
            ).claim(HOME_URL, VALID_CREDENTIAL, DEVICE_ID, 1, "grant-a", "client-1")
            assertEquals(HomeClientClaimResult.Denied(code), result)
        }
        try {
            HttpHomeClientService(
                RecordingTransport(HomeHttpResponse(401, error("unauthorized"))),
            ).claim(HOME_URL, VALID_CREDENTIAL, DEVICE_ID, 1, "grant-a", "client-1")
            fail("expected Unauthorized")
        } catch (error: HomeAdministrationException) {
            assertEquals(HomeAdministrationError.Unauthorized, error.reason)
        }
    }

    @Test
    fun device_configuration_returns_revision_and_client_grants() {
        val transport = RecordingTransport(
            HomeHttpResponse(
                200,
                """{"schema":1,"snapshot":{"revision":13,"wake_mappings":[],"client_grants":$GRANTS_JSON}}""",
            ),
        )
        val configuration = HttpHomeClientService(transport)
            .configuration(HOME_URL, VALID_CREDENTIAL, DEVICE_ID)

        assertEquals(13, configuration.revision)
        assertEquals(listOf("grant-a", "grant-j"), configuration.grants.map { it.grantId })
        assertEquals("GET", transport.requests.single().method)
        assertEquals(
            "$HOME_URL/api/v1/devices/$DEVICE_ID/configuration",
            transport.requests.single().url,
        )
    }

    // --- Pairing coordinator ---------------------------------------------

    @Test
    fun an_approved_pairing_stores_one_credential_and_one_profile_per_active_grant() {
        val fixture = Fixture()
        fixture.service.consumeResults += HomeConsumeResult.Pending
        fixture.service.consumeResults += approved()

        val outcome = fixture.pair() as HomePairingOutcome.Paired

        assertEquals(1, fixture.sleeps.size)
        assertEquals(listOf("Jensen"), outcome.pendingOwnerLabels)
        val profiles = fixture.configuration.collection.profiles
        assertEquals(listOf("Amanda · home.example.ts.net"), profiles.map { it.displayName })
        assertEquals(profiles.single().id, fixture.configuration.collection.selectedId)
        val grant = profiles.single().homeClientGrant!!
        assertEquals("grant-a", grant.grantId)
        assertEquals(
            VALID_CREDENTIAL,
            fixture.credentials.readHomeCredential(HomeClientPairingRecord.credentialSlot(grant.pairingId)),
        )
        val record = fixture.store.load().find(grant.pairingId)!!
        assertEquals(DEVICE_ID, record.deviceId)
        assertEquals(HOME_URL, record.homeUrl)
    }

    @Test
    fun no_secret_reaches_the_profile_or_pairing_files() {
        val fixture = Fixture()
        fixture.service.consumeResults += approved()
        fixture.pair()

        val persisted = fixture.configuration.collection.toJson() + fixture.store.load().toJson()
        assertFalse(persisted.contains(VALID_CREDENTIAL))
        assertFalse(persisted.contains("K7Q4MX"))
        assertFalse(persisted.contains("482913"))
    }

    @Test
    fun a_rejected_or_expired_pairing_stores_nothing() {
        listOf(HomeConsumeResult.Rejected, HomeConsumeResult.Expired).forEach { result ->
            val fixture = Fixture()
            fixture.service.consumeResults += result
            val outcome = fixture.pair()
            assertTrue(outcome is HomePairingOutcome.Rejected || outcome is HomePairingOutcome.Expired)
            assertTrue(fixture.configuration.collection.profiles.isEmpty())
            assertTrue(fixture.store.load().records.isEmpty())
        }
    }

    @Test
    fun polling_stops_at_the_request_expiry_and_on_cancel() {
        val fixture = Fixture()
        repeat(10) { fixture.service.consumeResults += HomeConsumeResult.Pending }
        fixture.now = 2_000.0
        assertEquals(HomePairingOutcome.Expired, fixture.pair())

        val cancelled = Fixture()
        repeat(10) { cancelled.service.consumeResults += HomeConsumeResult.Pending }
        assertEquals(HomePairingOutcome.Cancelled, cancelled.pair(cancel = true))
    }

    @Test
    fun a_secure_storage_failure_saves_no_pairing_or_profile() {
        val fixture = Fixture(credentials = RefusingCredentialStore())
        fixture.service.consumeResults += approved()

        assertEquals(
            HomePairingOutcome.Failed(HomeAdministrationError.SecureStorageUnavailable),
            fixture.pair(),
        )
        assertTrue(fixture.store.load().records.isEmpty())
        assertTrue(fixture.configuration.collection.profiles.isEmpty())
    }

    @Test
    fun pairing_the_same_home_again_keeps_its_identity_and_profiles() {
        val fixture = Fixture()
        fixture.service.consumeResults += approved()
        fixture.pair()
        val firstEndpoint = fixture.service.submittedEndpointIds.single()
        val firstPairing = fixture.store.load().records.single().pairingId

        fixture.service.consumeResults += approved(generation = 2)
        fixture.pair()

        assertEquals(listOf(firstEndpoint, firstEndpoint), fixture.service.submittedEndpointIds)
        assertEquals(firstPairing, fixture.store.load().records.single().pairingId)
        assertEquals(2, fixture.store.load().records.single().generation)
        assertEquals(1, fixture.configuration.collection.profiles.size)
    }

    @Test
    fun deleting_the_last_profile_of_a_pairing_removes_its_credential() {
        val fixture = Fixture()
        fixture.service.consumeResults += approved(
            grants = listOf(
                HomeClientGrant("grant-a", "Amanda", HomeClientGrantStatus.Active, true),
                HomeClientGrant("grant-s", "Spark", HomeClientGrantStatus.Active, true),
            ),
        )
        fixture.pair()
        val profiles = fixture.configuration.collection.profiles
        val pairingId = profiles.first().homeClientGrant!!.pairingId
        val slot = HomeClientPairingRecord.credentialSlot(pairingId)

        fixture.configuration.delete(profiles[0].id)
        assertEquals(VALID_CREDENTIAL, fixture.credentials.readHomeCredential(slot))
        assertTrue(fixture.store.load().find(pairingId) != null)

        fixture.configuration.delete(profiles[1].id)
        assertNull(fixture.credentials.readHomeCredential(slot))
        assertNull(fixture.store.load().find(pairingId))
    }

    // --- Claim provider --------------------------------------------------

    @Test
    fun a_connect_reads_the_revision_then_claims_the_grant() {
        val fixture = pairedFixture()
        val outcome = fixture.claims().claim(fixture.grant()) as HomeClientClaimOutcome.Claimed

        assertEquals(listOf("configuration", "claim"), fixture.service.calls)
        assertEquals("wss://home.example.ts.net/api/v1/bridge/ws", outcome.binding.approvedRoute)
        assertEquals("opaque-home-claim-1", outcome.binding.conversationHandle)
        assertEquals(VALID_CREDENTIAL, outcome.credential)
        assertEquals("grant-a", fixture.service.claimedGrants.single())
    }

    @Test
    fun a_credential_inside_the_renewal_window_is_renewed_before_claiming() {
        val fixture = pairedFixture()
        fixture.now = EXPIRES_AT - 13 * DAY
        val outcome = fixture.claims().claim(fixture.grant()) as HomeClientClaimOutcome.Claimed

        assertEquals(listOf("renew", "configuration", "claim"), fixture.service.calls)
        assertEquals(RENEWED_CREDENTIAL, outcome.credential)
        val record = fixture.store.load().records.single()
        assertEquals(2, record.generation)
        assertEquals(RENEWED_CREDENTIAL, fixture.credentials.readHomeCredential(record.credentialSlot))
        assertEquals(1, fixture.service.renewGenerations.single())
    }

    @Test
    fun an_unreachable_renewal_proceeds_with_the_still_valid_credential() {
        val fixture = pairedFixture()
        fixture.now = EXPIRES_AT - DAY
        fixture.service.renewError = HomeAdministrationError.TransportUnavailable
        val outcome = fixture.claims().claim(fixture.grant()) as HomeClientClaimOutcome.Claimed
        assertEquals(VALID_CREDENTIAL, outcome.credential)
    }

    @Test
    fun an_expired_credential_asks_to_pair_again_without_calling_home() {
        val fixture = pairedFixture()
        fixture.now = EXPIRES_AT + 1
        val outcome = fixture.claims().claim(fixture.grant()) as HomeClientClaimOutcome.Unavailable
        assertEquals(AndroidHomeUnavailableReason.Unauthorized, outcome.reason)
        assertTrue(fixture.service.calls.isEmpty())
    }

    @Test
    fun a_stale_configuration_is_refreshed_once_and_retried() {
        val fixture = pairedFixture()
        fixture.service.claimResults += HomeClientClaimResult.Denied("stale_configuration")
        fixture.service.claimResults += HomeClientClaimResult.Denied("stale_configuration")

        val outcome = fixture.claims().claim(fixture.grant())

        assertEquals(listOf("configuration", "claim", "configuration", "claim"), fixture.service.calls)
        assertEquals(AndroidHomeUnavailableReason.RequestRejected, (outcome as HomeClientClaimOutcome.Unavailable).reason)
    }

    @Test
    fun a_pending_or_unavailable_grant_is_reported_without_claiming() {
        val pending = pairedFixture()
        pending.service.configurationGrants = listOf(
            HomeClientGrant("grant-a", "Amanda", HomeClientGrantStatus.PendingOwner, true),
        )
        val waiting = pending.claims().claim(pending.grant()) as HomeClientClaimOutcome.Unavailable
        assertTrue(waiting.message.contains("owner"))
        assertEquals(listOf("configuration"), pending.service.calls)

        val removed = pairedFixture()
        removed.service.configurationGrants = emptyList()
        val gone = removed.claims().claim(removed.grant()) as HomeClientClaimOutcome.Unavailable
        assertEquals(AndroidHomeUnavailableReason.AuthorizationUnavailable, gone.reason)
        assertFalse(gone.retryable)
    }

    @Test
    fun an_unreachable_home_is_retryable() {
        val fixture = pairedFixture()
        fixture.service.configurationError = HomeAdministrationError.TransportUnavailable
        val outcome = fixture.claims().claim(fixture.grant()) as HomeClientClaimOutcome.Unavailable
        assertTrue(outcome.retryable)
        assertEquals(AndroidHomeUnavailableReason.TransportUnavailable, outcome.reason)
    }

    @Test
    fun the_first_ready_route_is_pinned_and_a_different_one_is_refused() {
        val fixture = pairedFixture()
        val claims = fixture.claims()
        assertTrue(claims.acceptRoute(fixture.grant(), "route-home"))
        assertTrue(claims.acceptRoute(fixture.grant(), "route-home"))
        assertFalse(claims.acceptRoute(fixture.grant(), "route-elsewhere"))
    }

    @Test
    fun an_unreadable_pairing_file_yields_no_pairings() {
        assertTrue(HomeClientPairings.fromJson("{not json").records.isEmpty())
        assertTrue(HomeClientPairings.fromJson("""{"schema":99,"records":[]}""").records.isEmpty())
    }

    @Test
    fun a_profile_grant_reference_survives_a_json_round_trip() {
        val collection = RelayProfileCollection(
            profiles = listOf(
                RelayProfile(
                    id = "p1",
                    endpoint = "wss://home.example.ts.net/api/v1/bridge/ws",
                    clientId = "android",
                    deviceId = DEVICE_ID,
                    displayName = "Amanda · home.example.ts.net",
                    homeClientGrant = RelayHomeClientGrantRef("pairing-1", "grant-a"),
                ),
            ),
            selectedId = "p1",
        )
        assertEquals(collection, RelayProfileCollection.fromJson(collection.toJson()))
    }

    // --- Fixtures --------------------------------------------------------

    private fun pairedFixture(): Fixture = Fixture().also {
        it.service.consumeResults += approved()
        it.pair()
        it.service.calls.clear()
    }

    private class Fixture(
        val credentials: RelayCredentialStore = InMemoryRelayCredentialStore(),
    ) {
        val store = InMemoryHomeClientPairingStore()
        val service = FakeHomeClientService()
        var now = 100.0
        val sleeps = mutableListOf<Long>()
        private var nextId = 0
        val configuration = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
            homeClientPairings = store,
            idFactory = { "profile-${nextId++}" },
        )

        fun pair(cancel: Boolean = false): HomePairingOutcome {
            val coordinator = HomeClientPairingCoordinator(
                store = store,
                credentials = credentials,
                service = service,
                configuration = configuration,
                deviceLabel = "Pixel 6a",
                clock = { now },
                sleeper = { sleeps += it },
                idFactory = { "id-${nextId++}" },
            )
            val target = HomePairingTarget(HOME_URL, "K7Q4MX")
            val submission = coordinator.submit(target)
            return coordinator.awaitApproval(target, submission) { cancel }
        }

        fun grant(): RelayHomeClientGrantRef = configuration.collection.profiles.first().homeClientGrant!!

        fun claims() = HomeClientClaimProvider(
            store = store,
            credentials = credentials,
            service = service,
            clock = { now },
            idFactory = { "claim-${nextId++}" },
        )
    }

    private class FakeHomeClientService : HomeClientService {
        val calls = mutableListOf<String>()
        val consumeResults = ArrayDeque<HomeConsumeResult>()
        val claimResults = ArrayDeque<HomeClientClaimResult>()
        val submittedEndpointIds = mutableListOf<String>()
        val claimedGrants = mutableListOf<String>()
        val renewGenerations = mutableListOf<Int>()
        var configurationGrants: List<HomeClientGrant> = GRANTS
        var configurationError: HomeAdministrationError? = null
        var renewError: HomeAdministrationError? = null

        override fun submit(target: HomePairingTarget, endpointId: String, label: String) =
            HomeEnrollmentSubmission("req-1", "482913", 1_000.0).also {
                submittedEndpointIds += endpointId
            }

        override fun consume(homeUrl: String, requestId: String, code: String) =
            consumeResults.removeFirst()

        override fun renew(
            homeUrl: String,
            credential: String,
            deviceId: String,
            requestId: String,
            generation: Int,
        ): HomeDeviceCredentialMaterial {
            calls += "renew"
            renewError?.let { throw HomeAdministrationException(it) }
            renewGenerations += generation
            return HomeDeviceCredentialMaterial(
                deviceId = deviceId,
                credential = RENEWED_CREDENTIAL,
                generation = generation + 1,
                expiresAt = EXPIRES_AT + 90 * DAY,
                scope = CLIENT_SCOPE,
            )
        }

        override fun configuration(homeUrl: String, credential: String, deviceId: String): HomeClientConfiguration {
            calls += "configuration"
            configurationError?.let { throw HomeAdministrationException(it) }
            return HomeClientConfiguration(13, configurationGrants)
        }

        override fun claim(
            homeUrl: String,
            credential: String,
            deviceId: String,
            revision: Int,
            grantId: String,
            claimId: String,
        ): HomeClientClaimResult {
            calls += "claim"
            claimedGrants += grantId
            return claimResults.removeFirstOrNull()
                ?: HomeClientClaimResult.Granted("opaque-home-claim-1")
        }
    }

    private class RecordingTransport(private val response: HomeHttpResponse) : HomeHttpTransport {
        val requests = mutableListOf<HomeHttpRequest>()

        override fun execute(request: HomeHttpRequest): HomeHttpResponse {
            requests += request
            return response
        }
    }

    /** Keystore that accepts writes but never reads them back. */
    private class RefusingCredentialStore : RelayCredentialStore by InMemoryRelayCredentialStore() {
        override fun putHomeCredential(profileId: String, credential: String) = false
    }

    private companion object {
        const val HOME_URL = "https://home.example.ts.net"
        const val DEVICE_ID = "id-7"
        const val VALID_CREDENTIAL = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val RENEWED_CREDENTIAL = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val DAY = 24 * 60 * 60.0
        const val EXPIRES_AT = 100.0 + 90 * DAY
        val CLIENT_SCOPE = HomeCredentialScope(emptyList(), listOf("client_claim"))
        val GRANTS = listOf(
            HomeClientGrant("grant-a", "Amanda", HomeClientGrantStatus.Active, true),
            HomeClientGrant("grant-j", "Jensen", HomeClientGrantStatus.PendingOwner, true),
        )
        const val GRANTS_JSON =
            """[{"grant_id":"grant-a","label":"Amanda","status":"active","available":true},""" +
                """{"grant_id":"grant-j","label":"Jensen","status":"pending_owner","available":true}]"""

        fun approved(
            generation: Int = 1,
            grants: List<HomeClientGrant> = GRANTS,
        ) = HomeConsumeResult.Approved(
            HomeDeviceCredentialMaterial(DEVICE_ID, VALID_CREDENTIAL, generation, EXPIRES_AT, CLIENT_SCOPE),
            grants,
        )

        fun error(code: String) = JSONObject()
            .put("schema", 1)
            .put("error", JSONObject().put("code", code))
            .toString()

        fun materialJson(
            credential: String = VALID_CREDENTIAL,
            grants: String? = null,
        ): String {
            val json = JSONObject()
                .put("schema", 1)
                .put("device_id", DEVICE_ID)
                .put("credential", credential)
                .put("generation", 1)
                .put("expires_at", EXPIRES_AT)
                .put(
                    "scope",
                    JSONObject()
                        .put("rooms", JSONArray())
                        .put("capabilities", JSONArray().put("client_claim"))
                        .put("wake_mappings", JSONArray()),
                )
            grants?.let { json.put("client_grants", JSONArray(it)) }
            return json.toString()
        }
    }
}
