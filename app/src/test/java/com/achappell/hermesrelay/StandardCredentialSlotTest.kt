package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardCredentialSlotTest {
    private val homeDevice = "D".repeat(43)
    private val homeAdmin = "admin-secret"
    private val rollback = "legacy-bearer"
    private val standard = "standard-token"

    private fun filled(): InMemoryRelayCredentialStore {
        val store = InMemoryRelayCredentialStore()
        assertTrue(store.putStandardCredential("p", standard))
        assertTrue(store.putHomeCredential("p", homeDevice))
        assertTrue(store.putHomeAdminCredential("p", homeAdmin))
        assertTrue(store.putRollbackCredential("p", rollback))
        return store
    }

    private fun assertReads(
        store: InMemoryRelayCredentialStore,
        standard: String?,
        homeDevice: String?,
        homeAdmin: String?,
        rollback: String?,
    ) {
        assertEquals(standard, store.readStandardCredential("p"))
        assertEquals(homeDevice, store.readHomeCredential("p"))
        assertEquals(homeAdmin, store.readHomeAdminCredential("p"))
        assertEquals(rollback, store.readRollbackCredential("p"))
        assertEquals(rollback, store.read("p"))
    }

    @Test
    fun the_four_slots_never_read_each_others_values() {
        assertReads(filled(), standard, homeDevice, homeAdmin, rollback)
    }

    @Test
    fun writing_only_the_standard_slot_leaves_every_other_slot_empty() {
        val store = InMemoryRelayCredentialStore()
        store.putStandardCredential("p", standard)
        assertReads(store, standard, null, null, null)
        assertFalse(store.hasToken("p"))
        assertFalse(store.hasStoredRollbackCredential("p"))
    }

    @Test
    fun the_legacy_slots_alone_do_not_populate_the_standard_slot() {
        val store = InMemoryRelayCredentialStore(initial = mapOf("p" to rollback))
        store.putRollbackCredential("p", rollback)
        store.putHomeAdminCredential("p", homeAdmin)
        assertNull(store.readStandardCredential("p"))
        assertFalse(store.hasReadableStandardCredential("p"))
    }

    @Test
    fun deleting_the_standard_slot_leaves_the_others_intact() {
        val store = filled()
        assertTrue(store.deleteStandardCredential("p"))
        assertReads(store, null, homeDevice, homeAdmin, rollback)
    }

    @Test
    fun deleting_each_other_slot_leaves_the_standard_slot_intact() {
        val store = filled()
        store.deleteHomeCredential("p")
        assertReads(store, standard, null, homeAdmin, rollback)
        store.deleteHomeAdminCredential("p")
        assertReads(store, standard, null, null, rollback)
        store.delete("other")
        assertReads(store, standard, null, null, rollback)
    }

    @Test
    fun deleting_the_profile_clears_every_slot() {
        val store = filled()
        store.delete("p")
        assertReads(store, null, null, null, null)
        assertFalse(store.hasReadableStandardCredential("p"))
    }

    @Test
    fun slots_are_keyed_per_profile() {
        val store = InMemoryRelayCredentialStore()
        store.putStandardCredential("a", "token-a")
        store.putStandardCredential("b", "token-b")
        store.deleteStandardCredential("a")
        assertNull(store.readStandardCredential("a"))
        assertEquals("token-b", store.readStandardCredential("b"))
    }

    @Test
    fun blank_or_whitespace_containing_tokens_are_refused_and_tokens_are_trimmed() {
        val store = InMemoryRelayCredentialStore()
        assertFalse(store.putStandardCredential("p", ""))
        assertFalse(store.putStandardCredential("p", "   "))
        assertFalse(store.putStandardCredential("p", "two words"))
        assertFalse(store.putStandardCredential("p", "line\nbreak"))
        assertNull(store.readStandardCredential("p"))

        assertTrue(store.putStandardCredential("p", "  padded  "))
        assertEquals("padded", store.readStandardCredential("p"))
    }

    @Test
    fun a_refused_write_keeps_the_previous_standard_token() {
        val store = InMemoryRelayCredentialStore(standardCredentials = mapOf("p" to "first"))
        assertFalse(store.putStandardCredential("p", "bad token"))
        assertEquals("first", store.readStandardCredential("p"))
    }

    @Test
    fun the_interface_defaults_fail_closed() {
        val bare = object : RelayCredentialStore {
            override fun put(profileId: String, token: String) = true

            override fun hasToken(profileId: String) = true

            override fun read(profileId: String): String? = "rollback-value"

            override fun delete(profileId: String) = Unit
        }
        assertFalse(bare.putStandardCredential("p", "t"))
        assertNull(bare.readStandardCredential("p"))
        assertFalse(bare.hasReadableStandardCredential("p"))
        assertFalse(bare.deleteStandardCredential("p"))
    }
}
