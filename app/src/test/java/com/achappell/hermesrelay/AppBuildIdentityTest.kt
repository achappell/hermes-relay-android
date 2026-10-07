package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `ANDROID-REL-01`: the label formatter and its normalisation. */
class AppBuildIdentityTest {
    @Test
    fun a_release_build_reads_version_code_type_and_revision() {
        val identity = AppBuildIdentity.of("0.3.1", 301, debug = false, revision = "3e10ae2")

        assertEquals("Version 0.3.1 (301) · release · 3e10ae2", identity.label())
    }

    @Test
    fun a_debug_build_says_debug() {
        val identity = AppBuildIdentity.of("0.3.1", 301, debug = true, revision = "3e10ae2")

        assertEquals("Version 0.3.1 (301) · debug · 3e10ae2", identity.label())
    }

    @Test
    fun two_builds_of_one_version_differ_only_in_the_revision() {
        val first = AppBuildIdentity.of("0.3.1", 301, debug = true, revision = "3e10ae2").label()
        val second = AppBuildIdentity.of("0.3.1", 301, debug = true, revision = "b12cea5").label()

        assertEquals(first.replace("3e10ae2", "b12cea5"), second)
    }

    @Test
    fun a_missing_revision_reads_unknown() {
        listOf(null, "", "  ").forEach { revision ->
            val label = AppBuildIdentity.of("0.3.1", 301, debug = false, revision = revision).label()

            assertEquals("Version 0.3.1 (301) · release · unknown", label)
        }
    }

    @Test
    fun a_revision_that_is_not_a_short_hash_is_never_shown() {
        val label = AppBuildIdentity.of("0.3.1", 301, debug = false, revision = "main; rm -rf").label()

        assertFalse(label.contains("rm"))
        assertEquals("Version 0.3.1 (301) · release · unknown", label)
    }

    @Test
    fun a_long_version_code_is_kept_whole_and_a_negative_one_is_never_shown() {
        val big = AppBuildIdentity.of("1.2.3", 5_000_000_000L, debug = false, revision = "abcdef0")
        val negative = AppBuildIdentity.of("1.2.3", -1, debug = false, revision = "abcdef0")

        assertEquals("Version 1.2.3 (5000000000) · release · abcdef0", big.label())
        assertEquals("Version 1.2.3 (0) · release · abcdef0", negative.label())
    }

    @Test
    fun a_missing_or_overlong_version_name_is_bounded() {
        assertEquals("unknown", AppBuildIdentity.of(null, 1, false, "abcdef0").versionName)
        assertEquals(32, AppBuildIdentity.of("x".repeat(80), 1, false, "abcdef0").versionName.length)
    }
}
