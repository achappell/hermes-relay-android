package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ANDROID-HOME-13`: Refresh Profiles and Unpair this Home, on the pairing coordinator. */
class HomePairedHomesTest {
    private val active = HomeClientGrant("grant-a", "Amanda", HomeClientGrantStatus.Active, true)
    private val pending = HomeClientGrant("grant-j", "Jensen", HomeClientGrantStatus.PendingOwner, true)
    private val approvedLater = HomeClientGrant("grant-j", "Jensen", HomeClientGrantStatus.Active, true)

    // --- Refresh Profiles ------------------------------------------------

    @Test
    fun a_grant_that_turns_active_after_pairing_becomes_a_profile_and_a_second_refresh_adds_none() {
        val fixture = pairedFixture()
        assertEquals(listOf("grant-a"), fixture.profileGrants())
        fixture.service.grants = listOf(active, approvedLater)

        val first = fixture.coordinator.refreshGrants(fixture.pairingId)
        val second = fixture.coordinator.refreshGrants(fixture.pairingId)

        assertEquals(HomeRefreshOutcome.Refreshed(1), first)
        assertEquals(HomeRefreshOutcome.Refreshed(0), second)
        assertEquals(listOf("grant-a", "grant-j"), fixture.profileGrants())
        assertEquals(
            "the record carries the refreshed grants",
            listOf(HomeClientGrantStatus.Active, HomeClientGrantStatus.Active),
            fixture.store.load().records.single().grants.map { it.status },
        )
    }

    @Test
    fun a_refresh_with_nothing_new_reports_no_new_profiles_and_a_pending_grant_gets_none() {
        val fixture = pairedFixture()

        val outcome = fixture.coordinator.refreshGrants(fixture.pairingId)

        assertEquals(HomeRefreshOutcome.Refreshed(0), outcome)
        assertEquals(listOf("grant-a"), fixture.profileGrants())
        assertEquals(
            PairedHomesMessage(textRes = R.string.android_paired_homes_no_new),
            PairedHomesMessages.refresh(outcome),
        )
        assertEquals(
            PairedHomesMessage(pluralRes = R.plurals.android_paired_homes_added, quantity = 2),
            PairedHomesMessages.refresh(HomeRefreshOutcome.Refreshed(2)),
        )
    }

    @Test
    fun a_refresh_that_cannot_reach_home_changes_nothing() {
        val fixture = pairedFixture()
        fixture.service.grants = listOf(active, approvedLater)
        fixture.service.error = HomeAdministrationError.TransportUnavailable

        val outcome = fixture.coordinator.refreshGrants(fixture.pairingId)

        assertEquals(HomeRefreshOutcome.Failed(HomeAdministrationError.TransportUnavailable), outcome)
        assertEquals(listOf("grant-a"), fixture.profileGrants())
        assertEquals(
            listOf(HomeClientGrantStatus.Active, HomeClientGrantStatus.PendingOwner),
            fixture.store.load().records.single().grants.map { it.status },
        )
    }

    @Test
    fun a_refresh_after_home_stops_accepting_the_phone_says_to_pair_again() {
        val fixture = pairedFixture()
        fixture.service.error = HomeAdministrationError.Unauthorized

        assertEquals(HomeRefreshOutcome.PairAgain, fixture.coordinator.refreshGrants(fixture.pairingId))
    }

    @Test
    fun a_refresh_with_an_expired_credential_does_not_call_home() {
        val fixture = pairedFixture()
        fixture.now = EXPIRES_AT + 1

        assertEquals(HomeRefreshOutcome.PairAgain, fixture.coordinator.refreshGrants(fixture.pairingId))
        assertEquals(0, fixture.service.configurationCalls)
    }

    // --- Unpair ----------------------------------------------------------

    @Test
    fun unpair_removes_the_credential_then_the_record_and_keeps_the_profiles() {
        val events = mutableListOf<String>()
        val fixture = pairedFixture(events)
        val slot = fixture.store.load().records.single().credentialSlot
        assertNotNull(fixture.credentials.readHomeCredential(slot))

        val outcome = fixture.coordinator.unpair(fixture.pairingId)

        assertEquals(HomeUnpairOutcome.Unpaired(profilesKept = 1), outcome)
        assertEquals(
            "credential first, then the record",
            listOf("credential-removed:record-present", "record-saved:credential-absent"),
            events,
        )
        assertNull(fixture.credentials.readHomeCredential(slot))
        assertTrue(fixture.store.load().records.isEmpty())
        assertEquals("the Profiles stay", listOf("grant-a"), fixture.profileGrants())
        assertEquals(1, fixture.coordinator.profilesNeedingPairing().size)
        assertTrue(fixture.coordinator.pairedHomes().isEmpty())
    }

    @Test
    fun pairing_the_same_home_again_reattaches_the_kept_profiles_without_duplicates() {
        val fixture = pairedFixture()
        val keptProfileId = fixture.configuration.collection.profiles.single().id
        fixture.coordinator.unpair(fixture.pairingId)

        fixture.service.consumeResults += approved(listOf(active, approvedLater))
        val outcome = fixture.pair() as HomePairingOutcome.Paired

        assertEquals("same Home identity", fixture.pairingId, outcome.pairingId)
        assertEquals("only the new grant gets a Profile", 1, outcome.addedProfileIds.size)
        val profiles = fixture.configuration.collection.profiles
        assertEquals(2, profiles.size)
        assertTrue(profiles.any { it.id == keptProfileId })
        assertTrue(fixture.coordinator.profilesNeedingPairing().isEmpty())
        // The reattached Profile reaches the new credential.
        val claims = fixture.claims()
        assertNotNull(claims.credentialFor(profiles.first { it.id == keptProfileId }.homeClientGrant!!))
    }

    @Test
    fun a_failed_credential_removal_leaves_the_pairing_intact() {
        val events = mutableListOf<String>()
        val fixture = pairedFixture(events, failCredentialDelete = true)

        val outcome = fixture.coordinator.unpair(fixture.pairingId)

        assertEquals(HomeUnpairOutcome.CredentialNotRemoved, outcome)
        assertEquals("the record is not touched", 1, fixture.store.load().records.size)
        assertEquals(0, fixture.store.load().unpairedIds.size)
        assertNotNull(
            fixture.credentials.readHomeCredential(fixture.store.load().records.single().credentialSlot),
        )
        assertEquals(1, fixture.coordinator.pairedHomes().size)
        assertEquals(
            PairedHomesMessage(R.string.android_paired_homes_unpair_credential_failed),
            PairedHomesMessages.unpair(outcome),
        )
    }

    @Test
    fun a_failed_record_removal_can_be_retried_and_unpair_is_then_complete() {
        val fixture = pairedFixture()
        fixture.store.failSaves = true

        assertEquals(HomeUnpairOutcome.RecordNotRemoved, fixture.coordinator.unpair(fixture.pairingId))
        assertEquals(1, fixture.store.load().records.size)

        fixture.store.failSaves = false
        assertTrue(fixture.coordinator.unpair(fixture.pairingId) is HomeUnpairOutcome.Unpaired)
        assertTrue(fixture.store.load().records.isEmpty())
    }

    @Test
    fun unpair_of_an_unknown_pairing_does_nothing() {
        val fixture = pairedFixture()

        assertEquals(HomeUnpairOutcome.NotPaired, fixture.coordinator.unpair("missing"))
        assertEquals(1, fixture.store.load().records.size)
    }

    @Test
    fun deleting_the_last_kept_profile_forgets_the_home_identity() {
        val fixture = pairedFixture()
        fixture.coordinator.unpair(fixture.pairingId)
        assertEquals(1, fixture.store.load().unpairedIds.size)

        fixture.configuration.delete(fixture.configuration.collection.profiles.single().id)

        assertTrue(fixture.store.load().unpairedIds.isEmpty())
        assertTrue(fixture.coordinator.profilesNeedingPairing().isEmpty())
    }

    @Test
    fun the_unpaired_identity_survives_a_json_round_trip() {
        val fixture = pairedFixture()
        fixture.coordinator.unpair(fixture.pairingId)
        val saved = fixture.store.load()

        assertEquals(saved, HomeClientPairings.fromJson(saved.toJson()))
    }

    // --- The list --------------------------------------------------------

    @Test
    fun the_list_shows_status_grants_and_expiry_without_secrets() {
        val fixture = pairedFixture()

        val home = fixture.coordinator.pairedHomes().single()

        assertEquals(PairedHomeStatus.Active, home.status)
        assertEquals("home.example.ts.net", home.host)
        assertEquals(EXPIRES_AT, home.credentialExpiresAt, 0.0)
        assertEquals(
            listOf(
                PairedHomeGrant("Amanda", HomeClientGrantStatus.Active, hasProfile = true),
                PairedHomeGrant("Jensen", HomeClientGrantStatus.PendingOwner, hasProfile = false),
            ),
            home.grants,
        )
        val rendered = home.toString()
        assertFalse(rendered.contains(VALID_CREDENTIAL))
        assertFalse(rendered.contains("grant-a"))
        assertFalse(rendered.contains("grant-j"))
    }

    @Test
    fun the_status_is_pending_when_no_grant_is_active_and_expired_past_the_credential_expiry() {
        val onlyPending = pairedFixture(grants = listOf(pending))
        assertEquals(PairedHomeStatus.Pending, onlyPending.coordinator.pairedHomes().single().status)

        val expired = pairedFixture()
        expired.now = EXPIRES_AT + 1
        assertEquals(PairedHomeStatus.Expired, expired.coordinator.pairedHomes().single().status)
    }

    // --- Fixtures --------------------------------------------------------

    private fun pairedFixture(
        events: MutableList<String> = mutableListOf(),
        failCredentialDelete: Boolean = false,
        grants: List<HomeClientGrant> = listOf(active, pending),
    ): Fixture = Fixture(events, failCredentialDelete).also {
        it.service.consumeResults += approved(grants)
        val paired = it.pair() as HomePairingOutcome.Paired
        it.pairingId = paired.pairingId
        it.service.grants = grants
        it.service.configurationCalls = 0
    }

    private class RecordingPairingStore : HomeClientPairingStore {
        private val inner = InMemoryHomeClientPairingStore()
        var failSaves = false
        var onSave: (HomeClientPairings) -> Unit = {}

        override fun load() = inner.load()

        override fun save(pairings: HomeClientPairings): Boolean {
            if (failSaves) return false
            onSave(pairings)
            return inner.save(pairings)
        }
    }

    private class Fixture(events: MutableList<String>, failCredentialDelete: Boolean) {
        val store = RecordingPairingStore()
        val service = FakeService()
        var now = 100.0
        var pairingId = ""
        private var nextId = 0
        private val memory = InMemoryRelayCredentialStore()
        val credentials: RelayCredentialStore = object : RelayCredentialStore by memory {
            override fun deleteHomeCredential(profileId: String): Boolean {
                if (failCredentialDelete) return false
                events += "credential-removed:record-" +
                    if (store.load().records.any { it.credentialSlot == profileId }) "present" else "absent"
                return memory.deleteHomeCredential(profileId)
            }
        }
        val configuration = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
            homeClientPairings = store,
            idFactory = { "profile-${nextId++}" },
        )
        val coordinator = HomeClientPairingCoordinator(
            store = store,
            credentials = credentials,
            service = service,
            configuration = configuration,
            deviceLabel = "Pixel 6a",
            clock = { now },
            sleeper = {},
            idFactory = { "id-${nextId++}" },
        )

        init {
            store.onSave = { saved ->
                if (saved.records.none { it.pairingId == pairingId } && pairingId.isNotEmpty()) {
                    events += "record-saved:credential-" +
                        if (memory.readHomeCredential("pairing:$pairingId") == null) "absent" else "present"
                }
            }
        }

        fun pair(): HomePairingOutcome {
            val target = HomePairingTarget(HOME_URL, "K7Q4MX")
            val submission = coordinator.submit(target)
            return coordinator.awaitApproval(target, submission) { false }
        }

        fun profileGrants() = configuration.collection.profiles.mapNotNull { it.homeClientGrant?.grantId }

        fun claims() = HomeClientClaimProvider(
            store = store,
            credentials = credentials,
            service = service,
            clock = { now },
            idFactory = { "claim-${nextId++}" },
        )
    }

    private class FakeService : HomeClientService {
        val consumeResults = ArrayDeque<HomeConsumeResult>()
        var grants: List<HomeClientGrant> = emptyList()
        var error: HomeAdministrationError? = null
        var configurationCalls = 0

        override fun submit(target: HomePairingTarget, endpointId: String, label: String) =
            HomeEnrollmentSubmission("req-1", "482913", 1_000.0)

        override fun consume(homeUrl: String, requestId: String, code: String) =
            consumeResults.removeFirst()

        override fun renew(
            homeUrl: String,
            credential: String,
            deviceId: String,
            requestId: String,
            generation: Int,
        ): HomeDeviceCredentialMaterial = throw UnsupportedOperationException()

        override fun configuration(homeUrl: String, credential: String, deviceId: String): HomeClientConfiguration {
            configurationCalls += 1
            error?.let { throw HomeAdministrationException(it) }
            return HomeClientConfiguration(7, grants)
        }

        override fun claim(
            homeUrl: String,
            credential: String,
            deviceId: String,
            revision: Int,
            grantId: String,
            claimId: String,
            session: HomeClientSessionChoice,
        ): HomeClientClaimResult = throw UnsupportedOperationException()

        override fun listSessions(
            homeUrl: String,
            credential: String,
            grantId: String,
            limit: Int,
        ): List<HomeClientSession> = throw UnsupportedOperationException()

        override fun claimSession(homeUrl: String, credential: String, conversationHandle: String): String? =
            throw UnsupportedOperationException()

        override fun pendingGrants(homeUrl: String, credential: String): List<HomeProfileHolder> =
            throw UnsupportedOperationException()

        override fun profileHolders(homeUrl: String, credential: String): List<HomeProfileHolder> =
            throw UnsupportedOperationException()

        override fun decideGrant(
            homeUrl: String,
            credential: String,
            grantId: String,
            action: HomeGrantAction,
        ): HomeGrantActionResult = throw UnsupportedOperationException()
    }

    private companion object {
        const val HOME_URL = "https://home.example.ts.net"
        const val DEVICE_ID = "id-7"
        const val VALID_CREDENTIAL = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val EXPIRES_AT = 100.0 + 90 * 24 * 60 * 60.0
        val CLIENT_SCOPE = HomeCredentialScope(emptyList(), listOf("client_claim"))

        fun approved(grants: List<HomeClientGrant>) = HomeConsumeResult.Approved(
            HomeDeviceCredentialMaterial(DEVICE_ID, VALID_CREDENTIAL, 1, EXPIRES_AT, CLIENT_SCOPE),
            grants,
        )
    }
}
