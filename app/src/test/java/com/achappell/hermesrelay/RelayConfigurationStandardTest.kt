package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigurationStandardTest {
    private val endpoint = "wss://hermes.example/api/ws"
    private val token = "std-token-value"

    private fun AndroidLocalHistory.draftOnly(value: String) = withDraft(value)

    private class RecordingChecker(
        var result: StandardCheckResult = StandardCheckResult.Verified,
    ) : StandardConnectionChecker {
        data class Call(val endpoint: String, val hermesProfile: String, val token: String)

        val calls = mutableListOf<Call>()

        override fun check(endpoint: String, hermesProfile: String, token: String): StandardCheckResult {
            calls += Call(endpoint, hermesProfile, token)
            return result
        }
    }

    private class TogglingProfileStore(
        var collection: RelayProfileCollection = RelayProfileCollection(),
        var failSaves: Boolean = false,
    ) : RelayProfileStore {
        override fun load() = collection

        override fun save(collection: RelayProfileCollection): Boolean {
            if (failSaves) return false
            this.collection = collection
            return true
        }
    }

    /** Fails the test if Standard code touches the legacy/rollback slot. */
    private class NoRollbackAccessStore(
        private val delegate: InMemoryRelayCredentialStore,
    ) : RelayCredentialStore by delegate {
        override fun put(profileId: String, token: String): Boolean = throw AssertionError("rollback write")

        override fun hasToken(profileId: String): Boolean = throw AssertionError("rollback read")

        override fun read(profileId: String): String? = throw AssertionError("rollback read")

        override fun putRollbackCredential(profileId: String, credential: String): Boolean =
            throw AssertionError("rollback write")

        override fun readRollbackCredential(profileId: String): String? =
            throw AssertionError("rollback read")

        override fun hasReadableRollbackCredential(profileId: String): Boolean =
            throw AssertionError("rollback read")

        override fun hasStoredRollbackCredential(profileId: String): Boolean =
            throw AssertionError("rollback read")
    }

    private class Fixture(
        initial: RelayProfileCollection = RelayProfileCollection(),
        val credentials: InMemoryRelayCredentialStore = InMemoryRelayCredentialStore(),
        val checker: RecordingChecker = RecordingChecker(),
        guardCredentials: Boolean = false,
        withChecker: Boolean = true,
    ) {
        val profiles = TogglingProfileStore(initial)
        val history = InMemoryAndroidHistoryStore()
        private var next = 0
        val controller = RelayConfigurationController(
            profiles = profiles,
            credentials = if (guardCredentials) NoRollbackAccessStore(credentials) else credentials,
            history = history,
            idFactory = { "id-${++next}" },
            standardChecker = if (withChecker) checker else null,
        )
    }

    private fun legacyProfile(id: String = "legacy-1") = RelayProfile(
        id = id,
        endpoint = "wss://relay.example/voice-session",
        clientId = "c",
        deviceId = "d",
        displayName = "Amanda",
    )

    private fun standardProfile(
        id: String,
        endpoint: String = this.endpoint,
        hermesProfile: String = "default",
    ) = RelayProfile(
        id = id,
        endpoint = endpoint,
        clientId = "android",
        deviceId = "",
        displayName = "Standard",
        mode = RelayProfileMode.Standard,
        hermesProfile = hermesProfile,
    )

    private fun fixtureWithStandard(
        id: String = "s1",
        extra: List<RelayProfile> = emptyList(),
        selected: String? = id,
        checker: RecordingChecker = RecordingChecker(),
        guardCredentials: Boolean = false,
    ): Fixture {
        val profile = standardProfile(id)
        return Fixture(
            initial = RelayProfileCollection(listOf(profile) + extra, selectedId = selected),
            credentials = InMemoryRelayCredentialStore(standardCredentials = mapOf(id to "old-token")),
            checker = checker,
            guardCredentials = guardCredentials,
        )
    }

    // ---- setupStandard ----

    @Test
    fun setup_saves_a_standard_profile_after_a_verified_check() {
        val f = Fixture(guardCredentials = true)

        val result = f.controller.setupStandard("HTTPS://Hermes.Example/api/ws/", " work ", "  $token  ")

        val saved = (result as StandardSetupResult.Saved).profile
        assertEquals(RelayProfileMode.Standard, saved.mode)
        assertEquals("wss://hermes.example/api/ws", saved.endpoint)
        assertEquals("work", saved.hermesProfile)
        assertEquals("android", saved.clientId)
        assertEquals("", saved.deviceId)
        assertEquals("Standard · hermes.example · work", saved.displayName)
        assertEquals(saved, f.controller.collection.selected)
        assertEquals(token, f.credentials.readStandardCredential(saved.id))
        assertEquals(
            listOf(RecordingChecker.Call("wss://hermes.example/api/ws", "work", token)),
            f.checker.calls,
        )
        assertEquals(f.controller.collection, f.profiles.collection)
    }

    @Test
    fun the_default_hermes_profile_is_not_part_of_the_display_name() {
        val f = Fixture()
        val saved = (f.controller.setupStandard(endpoint, "", token) as StandardSetupResult.Saved).profile
        assertEquals("default", saved.hermesProfile)
        assertEquals("Standard · hermes.example", saved.displayName)
    }

    @Test
    fun invalid_input_saves_nothing_and_does_not_check() {
        val f = Fixture()
        val badEndpoint = f.controller.setupStandard("http://hermes.example/api/ws", "default", token)
        assertEquals(
            mapOf(RelayProfileField.Endpoint to RelayProfileError.EndpointNotSecure),
            (badEndpoint as StandardSetupResult.Invalid).errors,
        )
        val badProfile = f.controller.setupStandard(endpoint, "bad name", token)
        assertEquals(
            mapOf(RelayProfileField.HermesProfile to RelayProfileError.HermesProfileInvalid),
            (badProfile as StandardSetupResult.Invalid).errors,
        )
        listOf("", "   ", "two words").forEach { blank ->
            val result = f.controller.setupStandard(endpoint, "default", blank)
            assertEquals(
                mapOf(RelayProfileField.Token to RelayProfileError.Required),
                (result as StandardSetupResult.Invalid).errors,
            )
        }
        assertTrue(f.checker.calls.isEmpty())
        assertTrue(f.controller.collection.profiles.isEmpty())
        assertNull(f.credentials.readStandardCredential("id-1"))
    }

    @Test
    fun a_failed_check_saves_nothing_and_stores_no_token() {
        val f = Fixture(checker = RecordingChecker(StandardCheckResult.Failed(AndroidHomeUnavailableReason.Unauthorized)))

        val result = f.controller.setupStandard(endpoint, "default", token)

        assertEquals(StandardSetupResult.CheckFailed(AndroidHomeUnavailableReason.Unauthorized), result)
        assertTrue(f.controller.collection.profiles.isEmpty())
        assertTrue(f.profiles.collection.profiles.isEmpty())
        assertNull(f.credentials.readStandardCredential("id-1"))
        assertEquals(1, f.checker.calls.size)
    }

    @Test
    fun without_a_checker_setup_fails_closed() {
        val f = Fixture(withChecker = false)
        val result = f.controller.setupStandard(endpoint, "default", token)
        assertEquals(
            StandardSetupResult.CheckFailed(AndroidHomeUnavailableReason.TransportUnavailable),
            result,
        )
        assertTrue(f.controller.collection.profiles.isEmpty())
    }

    @Test
    fun the_same_identity_is_a_duplicate_but_another_hermes_profile_is_not() {
        val f = Fixture()
        assertTrue(f.controller.setupStandard(endpoint, "default", token) is StandardSetupResult.Saved)
        f.checker.calls.clear()

        val duplicate = f.controller.setupStandard("wss://HERMES.example/api/ws/", "", "another-token")
        assertEquals(
            StandardSetupResult.Invalid(mapOf(RelayProfileField.Endpoint to RelayProfileError.Duplicate)),
            duplicate,
        )
        assertTrue(f.checker.calls.isEmpty())
        assertEquals(1, f.controller.collection.profiles.size)
        assertEquals(token, f.credentials.readStandardCredential("id-1"))

        assertTrue(f.controller.setupStandard(endpoint, "work", "work-token") is StandardSetupResult.Saved)
        assertEquals(2, f.controller.collection.profiles.size)
        assertEquals("id-1", f.controller.collection.selectedId)
    }

    @Test
    fun setup_leaves_other_profiles_credentials_and_selection_alone() {
        val other = legacyProfile()
        val credentials = InMemoryRelayCredentialStore(
            initial = mapOf(other.id to "legacy-bearer"),
            homeCredentials = mapOf(other.id to "H".repeat(43)),
        )
        val f = Fixture(
            initial = RelayProfileCollection(listOf(other), selectedId = other.id),
            credentials = credentials,
        )

        val saved = (f.controller.setupStandard(endpoint, "default", token) as StandardSetupResult.Saved).profile

        assertEquals(other.id, f.controller.collection.selectedId)
        assertEquals(other, f.controller.collection.profiles.first())
        assertEquals(saved, f.controller.collection.profiles.last())
        assertEquals("legacy-bearer", credentials.readRollbackCredential(other.id))
        assertEquals("H".repeat(43), credentials.readHomeCredential(other.id))
        assertNull(credentials.readStandardCredential(other.id))
        assertEquals(token, credentials.readStandardCredential(saved.id))
    }

    @Test
    fun a_profile_save_failure_rolls_the_credential_back() {
        val f = Fixture()
        f.profiles.failSaves = true

        val result = f.controller.setupStandard(endpoint, "default", token)

        assertEquals(StandardSetupResult.StorageUnavailable, result)
        assertNull(f.credentials.readStandardCredential("id-1"))
        assertTrue(f.controller.collection.profiles.isEmpty())
    }

    @Test
    fun a_credential_write_failure_saves_no_profile() {
        val refusing = object : RelayCredentialStore by InMemoryRelayCredentialStore() {
            override fun putStandardCredential(profileId: String, token: String) = false
        }
        val profiles = TogglingProfileStore()
        val controller = RelayConfigurationController(
            profiles = profiles,
            credentials = refusing,
            standardChecker = RecordingChecker(),
        )
        assertEquals(StandardSetupResult.StorageUnavailable, controller.setupStandard(endpoint, "default", token))
        assertTrue(profiles.collection.profiles.isEmpty())
    }

    @Test
    fun serialized_profiles_never_contain_the_token() {
        val f = Fixture()
        f.controller.setupStandard(endpoint, "default", "very-secret-standard-token")
        f.controller.updateStandard("id-1", "wss://other.example/api/ws", "work", "second-secret-token")

        val json = f.profiles.collection.toJson()
        assertFalse(json.contains("very-secret-standard-token"))
        assertFalse(json.contains("second-secret-token"))
        assertFalse(json.contains("secret", ignoreCase = true))
        assertFalse(json.contains("credential", ignoreCase = true))
        assertTrue(json.contains("other.example"))
    }

    // ---- updateStandard ----

    @Test
    fun changing_the_endpoint_requires_a_new_token_and_never_reuses_the_old_one() {
        val f = fixtureWithStandard(guardCredentials = true)
        listOf(null, "", "   ").forEach { blank ->
            val result = f.controller.updateStandard("s1", "wss://new.example/api/ws", "default", blank)
            assertEquals(
                StandardSetupResult.Invalid(mapOf(RelayProfileField.Token to RelayProfileError.Required)),
                result,
            )
        }
        val hermesChange = f.controller.updateStandard("s1", endpoint, "work", null)
        assertEquals(
            StandardSetupResult.Invalid(mapOf(RelayProfileField.Token to RelayProfileError.Required)),
            hermesChange,
        )
        assertTrue(f.checker.calls.isEmpty())
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
        assertEquals(endpoint, f.controller.collection.profiles.single().endpoint)
    }

    @Test
    fun a_verified_identity_change_replaces_the_credential_and_profile_and_drops_old_history() {
        val f = fixtureWithStandard(guardCredentials = true)
        val oldKey = f.controller.collection.profiles.single().historyKey
        f.history.save(oldKey, AndroidLocalHistory().appending(
            AndroidTranscriptEntry(AndroidTranscriptRole.User, "hello", 1L),
        ))

        val result = f.controller.updateStandard("s1", "wss://New.example:8443/api/ws", "work", "  new-token  ")

        val updated = (result as StandardSetupResult.Saved).profile
        assertEquals("s1", updated.id)
        assertEquals("wss://new.example:8443/api/ws", updated.endpoint)
        assertEquals("work", updated.hermesProfile)
        assertEquals("Standard · new.example · work", updated.displayName)
        assertEquals(updated, f.controller.collection.selected)
        assertEquals("new-token", f.credentials.readStandardCredential("s1"))
        assertEquals(
            listOf(RecordingChecker.Call("wss://new.example:8443/api/ws", "work", "new-token")),
            f.checker.calls,
        )
        assertFalse(f.history.contains(oldKey))
        assertFalse(f.history.contains(updated.historyKey))
        assertTrue(oldKey != updated.historyKey)
    }

    @Test
    fun a_failed_check_changes_nothing() {
        val checker = RecordingChecker(StandardCheckResult.Failed(AndroidHomeUnavailableReason.TransportTimeout))
        val f = fixtureWithStandard(checker = checker)
        val before = f.controller.collection
        val oldKey = before.profiles.single().historyKey
        f.history.save(oldKey, AndroidLocalHistory().draftOnly("keep"))

        val result = f.controller.updateStandard("s1", "wss://new.example/api/ws", "default", "new-token")

        assertEquals(StandardSetupResult.CheckFailed(AndroidHomeUnavailableReason.TransportTimeout), result)
        assertEquals(before, f.controller.collection)
        assertEquals(before, f.profiles.collection)
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
        assertTrue(f.history.contains(oldKey))
    }

    @Test
    fun a_profile_save_failure_restores_the_old_credential_and_keeps_history() {
        val f = fixtureWithStandard()
        val oldKey = f.controller.collection.profiles.single().historyKey
        f.history.save(oldKey, AndroidLocalHistory().draftOnly("keep"))
        f.profiles.failSaves = true

        val result = f.controller.updateStandard("s1", "wss://new.example/api/ws", "default", "new-token")

        assertEquals(StandardSetupResult.StorageUnavailable, result)
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
        assertEquals(endpoint, f.controller.collection.profiles.single().endpoint)
        assertTrue(f.history.contains(oldKey))
    }

    @Test
    fun an_unchanged_identity_with_a_blank_token_is_a_no_op() {
        val f = fixtureWithStandard()
        val before = f.controller.collection

        val result = f.controller.updateStandard("s1", "WSS://hermes.example/api/ws/", "", null)

        assertEquals(StandardSetupResult.Saved(before.profiles.single()), result)
        assertTrue(f.checker.calls.isEmpty())
        assertEquals(before, f.controller.collection)
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
    }

    @Test
    fun an_unchanged_identity_with_a_token_rechecks_and_replaces_only_the_credential() {
        val f = fixtureWithStandard()
        val before = f.controller.collection
        val key = before.profiles.single().historyKey
        f.history.save(key, AndroidLocalHistory().draftOnly("keep"))

        val result = f.controller.updateStandard("s1", endpoint, "default", "rotated-token")

        assertEquals(StandardSetupResult.Saved(before.profiles.single()), result)
        assertEquals(listOf(RecordingChecker.Call(endpoint, "default", "rotated-token")), f.checker.calls)
        assertEquals("rotated-token", f.credentials.readStandardCredential("s1"))
        assertEquals(before, f.controller.collection)
        assertTrue(f.history.contains(key))
    }

    @Test
    fun an_unchanged_identity_with_a_failed_check_keeps_the_old_token() {
        val checker = RecordingChecker(StandardCheckResult.Failed(AndroidHomeUnavailableReason.Unauthorized))
        val f = fixtureWithStandard(checker = checker)
        val result = f.controller.updateStandard("s1", endpoint, "default", "rotated-token")
        assertEquals(StandardSetupResult.CheckFailed(AndroidHomeUnavailableReason.Unauthorized), result)
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
    }

    @Test
    fun moving_onto_another_standard_profiles_identity_is_a_duplicate() {
        val second = standardProfile("s2", hermesProfile = "work")
        val f = fixtureWithStandard(extra = listOf(second))

        val result = f.controller.updateStandard("s1", endpoint, "work", "new-token")

        assertEquals(
            StandardSetupResult.Invalid(mapOf(RelayProfileField.Endpoint to RelayProfileError.Duplicate)),
            result,
        )
        assertTrue(f.checker.calls.isEmpty())
    }

    @Test
    fun invalid_update_input_is_reported_before_any_check() {
        val f = fixtureWithStandard()
        val result = f.controller.updateStandard("s1", "ws://hermes.example/api/ws", "default", null)
        assertEquals(
            StandardSetupResult.Invalid(mapOf(RelayProfileField.Endpoint to RelayProfileError.EndpointNotSecure)),
            result,
        )
        assertTrue(f.checker.calls.isEmpty())
    }

    @Test
    fun updating_a_missing_or_non_standard_profile_is_refused() {
        val legacy = legacyProfile()
        val f = Fixture(
            initial = RelayProfileCollection(listOf(legacy), selectedId = legacy.id),
        )
        assertEquals(StandardSetupResult.NotStandard, f.controller.updateStandard(legacy.id, endpoint, "default", token))
        assertEquals(StandardSetupResult.NotStandard, f.controller.updateStandard("nope", endpoint, "default", token))
        assertTrue(f.checker.calls.isEmpty())
        assertEquals(RelayProfileMode.Legacy, f.controller.collection.selected?.mode)
    }

    // ---- switch guard ----

    @Test
    fun a_busy_selected_standard_profile_blocks_update_select_and_delete() {
        val other = legacyProfile()
        val f = fixtureWithStandard(extra = listOf(other))
        f.controller.switchGuard = { true }
        val before = f.controller.collection

        assertTrue(f.controller.switchBlocked)
        assertEquals(StandardSetupResult.Blocked, f.controller.updateStandard("s1", endpoint, "default", "t"))
        assertFalse(f.controller.select(other.id))
        assertFalse(f.controller.delete("s1"))
        assertEquals(before, f.controller.collection)
        assertEquals(before, f.profiles.collection)
        assertTrue(f.checker.calls.isEmpty())
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
    }

    @Test
    fun selecting_the_already_selected_profile_is_allowed_while_busy() {
        val f = fixtureWithStandard()
        f.controller.switchGuard = { true }
        assertTrue(f.controller.select("s1"))
    }

    @Test
    fun the_guard_is_consulted_only_for_a_change_that_involves_a_standard_profile() {
        val legacy = legacyProfile()
        val otherLegacy = legacyProfile().copy(id = "legacy-2")
        val second = standardProfile("s2", hermesProfile = "work")
        val f = Fixture(
            initial = RelayProfileCollection(listOf(legacy, otherLegacy, second), selectedId = legacy.id),
            credentials = InMemoryRelayCredentialStore(standardCredentials = mapOf("s2" to "old")),
        )
        var consulted = 0
        f.controller.switchGuard = { consulted++; true }

        // Selected Legacy to another non-Standard profile: nothing consults the guard.
        assertFalse(f.controller.switchBlocked)
        assertTrue(f.controller.select("legacy-2"))
        assertEquals(0, consulted)

        // Switching INTO Standard while the current conversation is busy is refused.
        assertTrue(f.controller.switchBlockedFor("s2"))
        assertFalse(f.controller.select("s2"))
        assertEquals("legacy-2", f.controller.collection.selectedId)

        // Once idle the switch happens; then a busy Standard blocks switching away.
        f.controller.switchGuard = { false }
        assertTrue(f.controller.select("s2"))
        f.controller.switchGuard = { true }
        assertTrue(f.controller.switchBlocked)
        assertFalse(f.controller.select(legacy.id))
        assertEquals("s2", f.controller.collection.selectedId)

        // A non-selected profile is not blocked from deletion.
        assertTrue(f.controller.delete(legacy.id))
    }

    @Test
    fun a_busy_selected_standard_profile_does_not_block_editing_a_different_standard_profile() {
        val second = standardProfile("s2", hermesProfile = "work")
        val f = fixtureWithStandard(extra = listOf(second))
        f.controller.switchGuard = { true }

        val result = f.controller.updateStandard("s2", endpoint, "work", "fresh")

        assertTrue(result is StandardSetupResult.Saved)
        assertEquals("fresh", f.credentials.readStandardCredential("s2"))
    }

    @Test
    fun an_idle_standard_profile_can_be_switched_away_from_and_deleted() {
        val other = legacyProfile()
        val f = fixtureWithStandard(extra = listOf(other))
        f.controller.switchGuard = { false }
        assertTrue(f.controller.select(other.id))
        assertEquals(other.id, f.controller.collection.selectedId)
        assertTrue(f.controller.delete("s1"))
    }

    // ---- delete ----

    @Test
    fun delete_removes_every_credential_slot_and_the_history_of_the_profile() {
        val profile = standardProfile("s1")
        val f = Fixture(
            initial = RelayProfileCollection(listOf(profile), selectedId = "s1"),
            credentials = InMemoryRelayCredentialStore(
                initial = mapOf("s1" to "legacy"),
                homeCredentials = mapOf("s1" to "H".repeat(43)),
                homeAdminCredentials = mapOf("s1" to "admin"),
                standardCredentials = mapOf("s1" to "std"),
            ),
        )
        f.history.save(profile.historyKey, AndroidLocalHistory().draftOnly("x"))

        assertTrue(f.controller.delete("s1"))

        assertNull(f.credentials.readStandardCredential("s1"))
        assertNull(f.credentials.readHomeCredential("s1"))
        assertNull(f.credentials.readHomeAdminCredential("s1"))
        assertNull(f.credentials.readRollbackCredential("s1"))
        assertFalse(f.history.contains(profile.historyKey))
        assertTrue(f.controller.collection.profiles.isEmpty())
    }

    @Test
    fun history_survives_deleting_one_of_two_profiles_sharing_a_history_key() {
        val a = standardProfile("s1")
        val b = standardProfile("s2")
        assertEquals(a.historyKey, b.historyKey)
        val f = Fixture(initial = RelayProfileCollection(listOf(a, b), selectedId = null))
        f.history.save(a.historyKey, AndroidLocalHistory().draftOnly("x"))

        assertTrue(f.controller.delete("s1"))
        assertTrue(f.history.contains(a.historyKey))

        assertTrue(f.controller.delete("s2"))
        assertFalse(f.history.contains(a.historyKey))
    }

    @Test
    fun deleting_a_home_profile_never_deletes_a_standard_profiles_history() {
        val standard = standardProfile("s1")
        val home = RelayProfile("h1", "wss://hermes.example/api/ws", "c", "d", "n", mode = RelayProfileMode.HomeBridge)
        val f = Fixture(initial = RelayProfileCollection(listOf(standard, home), selectedId = "h1"))
        f.history.save(standard.historyKey, AndroidLocalHistory().draftOnly("std"))
        f.history.save("h1", AndroidLocalHistory().draftOnly("home"))

        assertTrue(f.controller.delete("h1"))

        assertTrue(f.history.contains(standard.historyKey))
        assertFalse(f.history.contains("h1"))
    }

    // ---- modes and Home ----

    @Test
    fun the_home_setup_form_creates_a_home_bridge_profile() {
        val f = Fixture()
        val errors = f.controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "c",
            deviceId = "d",
            displayName = "Amanda",
            token = "bearer",
        )
        assertTrue(errors.isEmpty())
        assertEquals(RelayProfileMode.HomeBridge, f.controller.collection.selected?.mode)
    }

    @Test
    fun a_legacy_profile_becomes_home_bridge_only_by_migration_and_is_never_standard() {
        val legacy = legacyProfile()
        val f = Fixture(initial = RelayProfileCollection(listOf(legacy), selectedId = legacy.id))
        assertEquals(RelayProfileMode.Legacy, f.controller.collection.selected?.mode)

        val result = f.controller.migrateToHome(
            legacy.id,
            RelayHomePairing("wss://home.example", "A".repeat(43), "opaque-1"),
        )

        assertTrue(result is RelayHomeMigrationResult.Migrated)
        assertEquals(RelayProfileMode.HomeBridge, f.controller.collection.selected?.mode)
    }

    @Test
    fun a_standard_profile_never_gains_a_home_link() {
        val f = fixtureWithStandard(guardCredentials = true)
        val before = f.controller.collection

        val migrated = f.controller.migrateToHome(
            "s1",
            RelayHomePairing("wss://home.example", "A".repeat(43), "opaque-1"),
        )
        assertEquals(
            RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.ProfileUnavailable),
            migrated,
        )
        assertFalse(f.controller.enrollHomeDevice("s1", "dev", "A".repeat(43), 1, null))
        assertFalse(
            f.controller.updateHomeAdministration(
                "s1",
                RelayHomeAdministration(RelayHomeAdministrationPhase.Ready),
            ),
        )
        assertFalse(f.controller.revokeHomeDevice("s1", RelayHomeAdministrationPhase.Revoked))

        assertEquals(before, f.controller.collection)
        assertNull(f.credentials.readHomeCredential("s1"))
        assertEquals("old-token", f.credentials.readStandardCredential("s1"))
    }

    @Test
    fun home_client_profiles_are_home_bridge_profiles() {
        val f = Fixture()
        val record = HomeClientPairingRecord(
            pairingId = "pair-1",
            homeUrl = "https://home.example",
            deviceId = "dev-1",
            generation = 1,
            credentialExpiresAt = 1.0,
            grants = listOf(
                HomeClientGrant(
                    grantId = "g1",
                    label = "Phone",
                    status = HomeClientGrantStatus.Active,
                    available = true,
                ),
            ),
        )
        val added = f.controller.addHomeClientProfiles(record)
        assertEquals(1, added?.size)
        assertEquals(RelayProfileMode.HomeBridge, f.controller.collection.profiles.single().mode)
    }
}
