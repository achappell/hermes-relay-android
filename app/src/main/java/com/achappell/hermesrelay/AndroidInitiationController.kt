package com.achappell.hermesrelay

internal sealed interface AndroidInitiationState {
    data object Idle : AndroidInitiationState

    data class Accepted(val binding: AndroidTurnBinding) : AndroidInitiationState

    data class Rejected(val reason: AndroidInitiationFailure) : AndroidInitiationState
}

/** Applies the pre-capture authorization gate for Android Client turns. */
internal class AndroidInitiationController(
    private val clientPort: AndroidClientPort,
) {
    fun initiate(input: AndroidTurnInput): AndroidInitiationState {
        val snapshot = clientPort.snapshot()
        val profile = snapshot.selectedProfile
            ?: return AndroidInitiationState.Rejected(AndroidInitiationFailure.ProfileUnavailable)

        if (snapshot.authorizationState != AndroidAuthorizationState.Verified) {
            return AndroidInitiationState.Rejected(AndroidInitiationFailure.AuthorizationRequired)
        }

        if (input is AndroidTurnInput.Typed && input.text.isBlank()) {
            return AndroidInitiationState.Rejected(AndroidInitiationFailure.EmptyTypedPrompt)
        }

        return when (val result = clientPort.beginTurn(AndroidTurnRequest(profile, input))) {
            is AndroidInitiationResult.Accepted -> AndroidInitiationState.Accepted(result.binding)
            is AndroidInitiationResult.Rejected -> AndroidInitiationState.Rejected(result.reason)
        }
    }
}
