package com.achappell.hermesrelay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StandardRestartStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val profile = RelayProfile(
        id = "profile-private-id",
        endpoint = "https://private-hermes.example/api",
        clientId = "private-client-reference",
        deviceId = "private-device-reference",
        displayName = "Private display text",
        mode = RelayConnectionMode.Standard,
        hermesProfile = "private-hermes-profile",
    )

    private fun directory() = File(folder.root, "standard-restart")
    private fun store() = FileStandardRestartStore(directory())
    private fun marker() = directory().walkTopDown().filter { it.isFile }.single()

    @Test
    fun pending_marker_is_read_by_a_fresh_store_and_repeated_marks_are_safe() {
        assertFalse(store().hasUnresolvedTurn(profile))
        store().markUnresolvedTurn(profile)
        assertTrue(store().hasUnresolvedTurn(profile))
        store().markUnresolvedTurn(profile)
        assertTrue(store().hasUnresolvedTurn(profile))
        assertEquals(1, directory().walkTopDown().count { it.isFile })
    }

    @Test
    fun clear_survives_a_fresh_instance_and_absence_is_safe() {
        store().clearUnresolvedTurn(profile)
        store().deleteProfile(profile.id)
        store().markUnresolvedTurn(profile)
        store().clearUnresolvedTurn(profile)
        assertFalse(store().hasUnresolvedTurn(profile))
        store().clearUnresolvedTurn(profile)
    }

    @Test
    fun profile_mode_endpoint_and_hermes_profile_are_isolated() {
        val stores = listOf<StandardRestartStore>(store(), InMemoryStandardRestartStore())
        for (storage in stores) {
            storage.markUnresolvedTurn(profile)
            val otherIdentities = listOf(
                profile.copy(id = "other-profile"),
                profile.copy(mode = RelayConnectionMode.Legacy),
                profile.copy(mode = RelayConnectionMode.HomeBridge),
                profile.copy(endpoint = "https://other-hermes.example/api"),
                profile.copy(hermesProfile = "other-hermes-profile"),
            )
            for (other in otherIdentities) {
                assertFalse(storage.hasUnresolvedTurn(other))
                storage.clearUnresolvedTurn(other)
                assertTrue(storage.hasUnresolvedTurn(profile))
            }
        }
    }

    @Test
    fun deleting_a_profile_clears_all_its_identities_but_preserves_other_profiles() {
        val stores = listOf<StandardRestartStore>(store(), InMemoryStandardRestartStore())
        for (storage in stores) {
            val changedEndpoint = profile.copy(endpoint = "https://other-hermes.example/api")
            val changedHermesProfile = profile.copy(hermesProfile = "second")
            val changedMode = profile.copy(mode = RelayConnectionMode.Legacy)
            val anotherProfile = profile.copy(id = "another-profile")
            val identities = listOf(profile, changedEndpoint, changedHermesProfile, changedMode)
            (identities + anotherProfile).forEach(storage::markUnresolvedTurn)
            storage.deleteProfile(profile.id)
            identities.forEach { assertFalse(storage.hasUnresolvedTurn(it)) }
            assertTrue(storage.hasUnresolvedTurn(anotherProfile))
            storage.deleteProfile(profile.id)
            assertTrue(storage.hasUnresolvedTurn(anotherProfile))
        }
        assertTrue(store().hasUnresolvedTurn(profile.copy(id = "another-profile")))
        assertFalse(store().hasUnresolvedTurn(profile))
    }

    @Test
    fun clearing_one_identity_preserves_other_pending_identities() {
        val stores = listOf<StandardRestartStore>(store(), InMemoryStandardRestartStore())
        for (storage in stores) {
            val other = profile.copy(hermesProfile = "second")
            storage.markUnresolvedTurn(profile)
            storage.markUnresolvedTurn(other)
            storage.clearUnresolvedTurn(profile)
            assertFalse(storage.hasUnresolvedTurn(profile))
            assertTrue(storage.hasUnresolvedTurn(other))
        }
        assertTrue(store().hasUnresolvedTurn(profile.copy(hermesProfile = "second")))
    }

    @Test
    fun corrupt_empty_truncated_and_unknown_markers_never_look_resolved() {
        store().markUnresolvedTurn(profile)
        val marker = marker()
        for (corrupt in listOf("", "unresolved-v", "unresolved-v2\n", "unresolved-v1\nextra")) {
            marker.writeText(corrupt)
            assertThrows(IOException::class.java) { store().hasUnresolvedTurn(profile) }
        }
        // Deliberate successful recovery may clear even an invalid marker.
        store().clearUnresolvedTurn(profile)
        assertFalse(store().hasUnresolvedTurn(profile))
    }

    @Test
    fun unusable_storage_root_throws_for_reads_writes_clears_and_deletion() {
        // A regular file in place of the directory fails on every host, including root.
        directory().writeText("not a directory")
        assertThrows(IOException::class.java) { store().hasUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().markUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().clearUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().deleteProfile(profile.id) }
    }

    @Test
    fun unusable_profile_directory_is_not_treated_as_absence() {
        store().markUnresolvedTurn(profile)
        val marker = marker()
        val profileDirectory = marker.parentFile!!
        assertTrue(marker.delete())
        assertTrue(profileDirectory.delete())
        profileDirectory.writeText("not a directory")
        assertThrows(IOException::class.java) { store().hasUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().markUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().clearUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().deleteProfile(profile.id) }
    }

    @Test
    fun failed_marker_replacement_and_deletion_propagate() {
        store().markUnresolvedTurn(profile)
        val marker = marker()
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        File(marker, "obstruction").writeText("blocked")
        assertThrows(IOException::class.java) { store().hasUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().markUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().clearUnresolvedTurn(profile) }
        assertThrows(IOException::class.java) { store().deleteProfile(profile.id) }
        assertThrows(IOException::class.java) { store().hasUnresolvedTurn(profile) }
    }

    @Test
    fun disk_representation_is_bounded_opaque_and_contains_only_a_fixed_marker() {
        store().markUnresolvedTurn(profile)
        val first = marker()
        assertTrue(first.name.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.parentFile!!.name.matches(Regex("[0-9a-f]{64}")))
        assertEquals(directory(), first.parentFile!!.parentFile)
        val bytes = first.readBytes()
        assertArrayEquals("unresolved-v1\n".toByteArray(Charsets.US_ASCII), bytes)
        val different = profile.copy(
            id = "../".repeat(200),
            endpoint = "https://another-private.example/api",
            hermesProfile = "another-private-profile",
        )
        store().markUnresolvedTurn(different)
        val files = directory().walkTopDown().filter { it.isFile }.toList()
        assertEquals(2, files.size)
        files.forEach {
            assertArrayEquals(bytes, it.readBytes())
            assertTrue(it.name.matches(Regex("[0-9a-f]{64}")))
            assertTrue(it.parentFile!!.name.matches(Regex("[0-9a-f]{64}")))
        }
        assertTrue(store().hasUnresolvedTurn(different))
    }
}
