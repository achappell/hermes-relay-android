package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAvailabilityTest {
    private val snapshot = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
        selectedProfile = AndroidProfile("profile", "Profile"),
        authorizationState = AndroidAuthorizationState.Verifying,
    )
    private val authorizationReasons = setOf(
        AndroidHomeUnavailableReason.MissingBinding,
        AndroidHomeUnavailableReason.InvalidBinding,
        AndroidHomeUnavailableReason.InvalidCredential,
        AndroidHomeUnavailableReason.SecureStorageUnavailable,
        AndroidHomeUnavailableReason.AuthorizationUnavailable,
        AndroidHomeUnavailableReason.Unauthorized,
    )
    private val offlineStates = listOf(
        AndroidConnectionState.Disconnected,
        AndroidConnectionState.Reconnecting(attempt = 1, of = 3),
        AndroidConnectionState.Failed("Profile authorization failed"),
    )

    @Test
    fun offline_verification_needs_connection_recovery_without_parsing_failure_text() {
        for (connection in offlineStates) {
            assertEquals(
                AndroidAvailabilityBlock.Connection,
                resolveAndroidAvailabilityBlock(snapshot, connection),
            )
        }
    }

    @Test
    fun explicit_authorization_failures_win_from_either_typed_source() {
        for (reason in authorizationReasons) {
            for (connection in offlineStates + AndroidConnectionState.Connected) {
                assertEquals(
                    reason.name,
                    AndroidAvailabilityBlock.Authorization,
                    resolveAndroidAvailabilityBlock(snapshot.copy(unavailableReason = reason), connection),
                )
            }
            assertEquals(
                reason.name,
                AndroidAvailabilityBlock.Authorization,
                resolveAndroidAvailabilityBlock(
                    snapshot.copy(authorizationState = AndroidAuthorizationState.Verified),
                    AndroidConnectionState.Failed("Connection interrupted", reasonCode = reason),
                ),
            )
        }
    }

    @Test
    fun non_authorization_failures_remain_connection_guidance_in_both_typed_sources() {
        for (reason in AndroidHomeUnavailableReason.values().filterNot { it in authorizationReasons }) {
            val failed = AndroidConnectionState.Failed("Failure", reasonCode = reason)
            assertEquals(
                reason.name,
                AndroidAvailabilityBlock.Connection,
                resolveAndroidAvailabilityBlock(snapshot, failed),
            )
            for (connection in offlineStates + failed) {
                assertEquals(
                    reason.name,
                    AndroidAvailabilityBlock.Connection,
                    resolveAndroidAvailabilityBlock(
                        snapshot.copy(
                            authorizationState = AndroidAuthorizationState.Unavailable,
                            unavailableReason = reason,
                        ),
                        connection,
                    ),
                )
            }
        }
    }

    @Test
    fun missing_configuration_and_untyped_unavailable_remain_fail_closed() {
        for (authorization in listOf(
            AndroidAuthorizationState.NotConfigured,
            AndroidAuthorizationState.Unavailable,
        )) {
            for (connection in offlineStates + AndroidConnectionState.Connected) {
                assertEquals(
                    AndroidAvailabilityBlock.Authorization,
                    resolveAndroidAvailabilityBlock(snapshot.copy(authorizationState = authorization), connection),
                )
            }
        }
        assertEquals(
            AndroidAvailabilityBlock.Authorization,
            resolveAndroidAvailabilityBlock(
                snapshot.copy(authorizationState = AndroidAuthorizationState.NotConfigured),
                AndroidConnectionState.Failed("Session refused", AndroidHomeUnavailableReason.RequestRejected),
            ),
        )
    }

    @Test
    fun stale_non_authorization_failure_cannot_hide_newly_unavailable_credentials() {
        for (reason in AndroidHomeUnavailableReason.values().filterNot { it in authorizationReasons }) {
            assertEquals(
                reason.name,
                AndroidAvailabilityBlock.Authorization,
                resolveAndroidAvailabilityBlock(
                    snapshot.copy(authorizationState = AndroidAuthorizationState.Unavailable),
                    AndroidConnectionState.Failed("Earlier connection failure", reasonCode = reason),
                ),
            )
        }
    }

    @Test
    fun connected_but_unverified_is_never_presented_as_ready() {
        assertEquals(
            AndroidAvailabilityBlock.Authorization,
            resolveAndroidAvailabilityBlock(snapshot, AndroidConnectionState.Connected),
        )
        assertEquals(
            AndroidAvailabilityBlock.Authorization,
            resolveAndroidAvailabilityBlock(
                snapshot.copy(
                    authorizationState = AndroidAuthorizationState.Unavailable,
                    unavailableReason = AndroidHomeUnavailableReason.RequestRejected,
                ),
                AndroidConnectionState.Connected,
            ),
        )
        assertNull(
            resolveAndroidAvailabilityBlock(
                snapshot.copy(authorizationState = AndroidAuthorizationState.Verified),
                AndroidConnectionState.Connected,
            ),
        )
    }

    @Test
    fun offline_verifying_surface_guidance_overrides_capture_profile_unavailable() {
        val availability = resolveAndroidAvailabilityBlock(snapshot, AndroidConnectionState.Disconnected)
        assertEquals(
            AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Connection),
            resolveAndroidDoorwayState(
                hasProfile = true,
                availabilityBlock = availability,
                hasAcceptedTurn = false,
                isCapturing = false,
                hasUnconfirmedTurn = false,
                captureBlock = AndroidCaptureBlock.ProfileUnavailable,
            ),
        )
        assertEquals(
            AndroidComposerBlock.Disconnected,
            resolveAndroidComposerBlock(
                hasProfile = true,
                availabilityBlock = availability,
                hasAcceptedTurn = false,
                prompt = "Retained draft",
            ),
        )
    }

    @Test
    fun missing_or_rejected_authorization_keeps_both_surfaces_actionable() {
        for (reason in listOf(
            AndroidHomeUnavailableReason.MissingBinding,
            AndroidHomeUnavailableReason.Unauthorized,
        )) {
            val availability = resolveAndroidAvailabilityBlock(
                snapshot.copy(unavailableReason = reason),
                AndroidConnectionState.Disconnected,
            )
            assertEquals(
                AndroidDoorwayState.Unavailable(AndroidDoorwayUnavailableReason.Authorization),
                resolveAndroidDoorwayState(
                    hasProfile = true,
                    availabilityBlock = availability,
                    hasAcceptedTurn = false,
                    isCapturing = false,
                    hasUnconfirmedTurn = false,
                    captureBlock = AndroidCaptureBlock.ProfileUnavailable,
                ),
            )
            assertEquals(
                AndroidComposerBlock.Authorization,
                resolveAndroidComposerBlock(
                    hasProfile = true,
                    availabilityBlock = availability,
                    hasAcceptedTurn = false,
                    prompt = "Retained draft",
                ),
            )
        }
    }
}
