package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidDoorwayStateTest {

    @Test
    fun no_profile_is_a_configuration_state() {
        assertEquals(
            AndroidDoorwayState.NoProfile,
            resolveAndroidDoorwayState(
                hasProfile = false,
                availabilityBlock = AndroidAvailabilityBlock.Authorization,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = AndroidCaptureBlock.ProfileUnavailable,
            ),
        )
    }

    @Test
    fun selected_profile_without_a_session_is_unavailable() {
        assertEquals(
            AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Connection),
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = AndroidAvailabilityBlock.Connection,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = AndroidCaptureBlock.NotConnected,
            ),
        )
    }

    @Test
    fun an_unverified_profile_is_unavailable_before_connection_is_considered() {
        assertEquals(
            AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Authorization),
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = AndroidAvailabilityBlock.Authorization,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = null,
            ),
        )
    }

    @Test
    fun connected_idle_profile_is_ready() {
        assertEquals(
            AndroidDoorwayState.Ready,
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = null,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = null,
            ),
        )
    }

    @Test
    fun microphone_gate_is_unavailable_but_does_not_hide_typed_path() {
        assertEquals(
            AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Microphone),
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = null,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = AndroidCaptureBlock.PermissionRequired,
            ),
        )
    }

    @Test
    fun live_capture_or_turn_owns_the_state_label() {
        assertNull(
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = AndroidAvailabilityBlock.Authorization,
                hasAcceptedTurn = true,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = null,
            ),
        )
        assertNull(
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = AndroidAvailabilityBlock.Connection,
                hasAcceptedTurn = false,
                isCapturing = true,
                hasUnconfirmedTurn = false,
                captureBlock = null,
            ),
        )
    }

    @Test
    fun a_reconnected_unconfirmed_turn_never_looks_ready() {
        assertEquals(
            AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.UnconfirmedTurn),
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = null,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = true,
                captureBlock = null,
            ),
        )
    }

    @Test
    fun availability_and_uncertainty_keep_precedence_over_microphone_blocks() {
        for (captureBlock in listOf(
            AndroidCaptureBlock.PermissionRequired,
            AndroidCaptureBlock.RecognizerUnavailable,
            AndroidCaptureBlock.SessionReplaced,
        )) {
            for ((availability, expected) in listOf(
                AndroidAvailabilityBlock.Authorization to AndroidDoorwayUnavailableReason.Authorization,
                AndroidAvailabilityBlock.Connection to AndroidDoorwayUnavailableReason.Connection,
                null to AndroidDoorwayUnavailableReason.UnconfirmedTurn,
            )) {
                assertEquals(
                    AndroidDoorwayState.Unavailable(expected),
                    resolveAndroidDoorwayState(
                        hasProfile = true,
                        availabilityBlock = availability,
                        hasAcceptedTurn = false,
                        isCapturing = false,
                        hasUnconfirmedTurn = true,
                        captureBlock = captureBlock,
                    ),
                )
            }
            assertEquals(
                AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Microphone),
                resolveAndroidDoorwayState(
                    hasProfile = true,
                    availabilityBlock = null,
                    hasAcceptedTurn = false,
                    isCapturing = false,
                    hasUnconfirmedTurn = false,
                    captureBlock = captureBlock,
                ),
            )
        }
    }

    @Test
    fun no_profile_keeps_precedence_over_active_capture_and_turn() {
        assertEquals(
            AndroidDoorwayState.NoProfile,
            resolveAndroidDoorwayState(
                hasProfile = false,
                availabilityBlock = AndroidAvailabilityBlock.Connection,
                hasAcceptedTurn = true,
                isCapturing = true,
                hasUnconfirmedTurn = true,
                captureBlock = AndroidCaptureBlock.PermissionRequired,
            ),
        )
    }
}
