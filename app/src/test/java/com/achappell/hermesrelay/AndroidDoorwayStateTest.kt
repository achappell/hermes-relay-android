package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidDoorwayStateTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val authorizedSnapshot = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
        selectedProfile = profile,
        authorizationState = AndroidAuthorizationState.Verified,
    )

    @Test
    fun no_profile_is_a_configuration_state() {
        assertEquals(
            AndroidDoorwayState.NoProfile,
            resolveAndroidDoorwayState(
                snapshot = authorizedSnapshot.copy(selectedProfile = null),
                isConnected = false,
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
                snapshot = authorizedSnapshot,
                isConnected = false,
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
                snapshot = authorizedSnapshot.copy(
                    authorizationState = AndroidAuthorizationState.Unavailable,
                ),
                isConnected = true,
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
                snapshot = authorizedSnapshot,
                isConnected = true,
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
                snapshot = authorizedSnapshot,
                isConnected = true,
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
                snapshot = authorizedSnapshot,
                isConnected = true,
                hasAcceptedTurn = true,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = null,
            ),
        )
        assertNull(
            resolveAndroidDoorwayState(
                snapshot = authorizedSnapshot,
                isConnected = true,
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
                snapshot = authorizedSnapshot,
                isConnected = true,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = true,
                captureBlock = null,
            ),
        )
    }
}
