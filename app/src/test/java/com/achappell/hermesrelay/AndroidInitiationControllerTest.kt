package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidInitiationControllerTest {
    private val profile = AndroidProfile("amanda", "Amanda")
    private val acceptedBinding = AndroidTurnBinding(
        profileId = profile.id,
        sessionId = "session-1",
        turnId = "turn-1",
    )

    @Test
    fun verified_typed_initiation_binds_selected_profile_and_submits_once() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(),
            result = AndroidInitiationResult.Accepted(acceptedBinding),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.Typed("Check the weather"))

        assertEquals(AndroidInitiationState.Accepted(acceptedBinding), result)
        assertEquals(1, port.requests.size)
        assertEquals(
            AndroidTurnRequest(profile, AndroidTurnInput.Typed("Check the weather")),
            port.requests.single(),
        )
    }

    @Test
    fun verified_tap_to_speak_initiation_uses_the_same_selected_profile() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(),
            result = AndroidInitiationResult.Accepted(acceptedBinding),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.TapToSpeak)

        assertEquals(AndroidInitiationState.Accepted(acceptedBinding), result)
        assertEquals(
            AndroidTurnRequest(profile, AndroidTurnInput.TapToSpeak),
            port.requests.single(),
        )
    }

    @Test
    fun missing_profile_fails_closed_without_calling_the_port() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(selectedProfile = null),
            result = AndroidInitiationResult.Accepted(acceptedBinding),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.TapToSpeak)

        assertEquals(
            AndroidInitiationState.Rejected(AndroidInitiationFailure.ProfileUnavailable),
            result,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun unverified_profile_fails_closed_without_selecting_a_fallback() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(authorizationState = AndroidAuthorizationState.Unavailable),
            result = AndroidInitiationResult.Accepted(acceptedBinding),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.Typed("Do not send this"))

        assertEquals(
            AndroidInitiationState.Rejected(AndroidInitiationFailure.AuthorizationRequired),
            result,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun empty_typed_prompt_fails_before_submission() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(),
            result = AndroidInitiationResult.Accepted(acceptedBinding),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.Typed("  \n"))

        assertEquals(
            AndroidInitiationState.Rejected(AndroidInitiationFailure.EmptyTypedPrompt),
            result,
        )
        assertEquals(0, port.requests.size)
    }

    @Test
    fun session_rejection_is_reported_without_an_automatic_retry() {
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(),
            result = AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable),
        )

        val result = AndroidInitiationController(port)
            .initiate(AndroidTurnInput.Typed("One attempt"))

        assertEquals(
            AndroidInitiationState.Rejected(AndroidInitiationFailure.SessionUnavailable),
            result,
        )
        assertEquals(1, port.requests.size)
    }

    @Test
    fun uncertain_delivery_is_retained_as_a_distinct_state_for_explicit_recovery() {
        val request = AndroidTurnRequest(profile, AndroidTurnInput.Typed("May have arrived"))
        val port = FakeAndroidClientPort(
            snapshot = authorizedSnapshot(),
            result = AndroidInitiationResult.Uncertain(
                request = request,
                reason = AndroidHomeUnavailableReason.TransportTimeout,
            ),
        )

        val result = AndroidInitiationController(port).initiate(request.input)

        assertEquals(
            AndroidInitiationState.Uncertain(
                request = request,
                reason = AndroidHomeUnavailableReason.TransportTimeout,
            ),
            result,
        )
        assertEquals(listOf(request), port.requests)
    }

    private fun authorizedSnapshot(
        selectedProfile: AndroidProfile? = profile,
        authorizationState: AndroidAuthorizationState = AndroidAuthorizationState.Verified,
    ) = AndroidClientSnapshot(
        titleRes = BootstrapState.titleRes,
        descriptionRes = BootstrapState.descriptionRes,
        boundaryRes = BootstrapState.boundaryRes,
        selectedProfile = selectedProfile,
        authorizationState = authorizationState,
    )

    private class FakeAndroidClientPort(
        private val snapshot: AndroidClientSnapshot,
        private val result: AndroidInitiationResult,
    ) : AndroidClientPort {
        val requests = mutableListOf<AndroidTurnRequest>()

        override fun snapshot() = snapshot

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return result
        }
    }
}
