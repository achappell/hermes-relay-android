package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePermissionTest {
    private class FakeSource(
        var granted: Boolean = false,
        var asked: Boolean = false,
        var rationale: Boolean = false,
    ) : RuntimePermissionSource {
        override fun isGranted() = granted

        override fun hasAsked() = asked

        override fun shouldShowRationale() = rationale

        override fun markAsked() {
            asked = true
        }
    }

    @Test
    fun a_permission_never_requested_is_not_asked() {
        assertEquals(
            RuntimePermissionState.NotAsked,
            resolveRuntimePermissionState(isGranted = false, hasAsked = false, shouldShowRationale = false),
        )
    }

    @Test
    fun a_granted_permission_is_granted_whatever_the_history() {
        for (asked in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    RuntimePermissionState.Granted,
                    resolveRuntimePermissionState(true, asked, rationale),
                )
            }
        }
    }

    @Test
    fun a_first_denial_leaves_the_rationale_available() {
        assertEquals(
            RuntimePermissionState.DeniedWithRationale,
            resolveRuntimePermissionState(isGranted = false, hasAsked = true, shouldShowRationale = true),
        )
    }

    @Test
    fun a_denial_the_platform_will_not_explain_is_permanent() {
        assertEquals(
            RuntimePermissionState.PermanentlyDenied,
            resolveRuntimePermissionState(isGranted = false, hasAsked = true, shouldShowRationale = false),
        )
    }

    @Test
    fun the_denial_sequence_walks_not_asked_then_rationale_then_permanent_then_granted() {
        val source = FakeSource()
        assertEquals(RuntimePermissionState.NotAsked, source.state())

        // First request, refused: the platform will still explain.
        source.markAsked()
        source.rationale = true
        assertEquals(RuntimePermissionState.DeniedWithRationale, source.state())

        // Second request, refused with "don't ask again": no rationale left.
        source.rationale = false
        assertEquals(RuntimePermissionState.PermanentlyDenied, source.state())

        // Granted in Settings, then re-read on resume.
        source.granted = true
        assertEquals(RuntimePermissionState.Granted, source.state())
    }
}
