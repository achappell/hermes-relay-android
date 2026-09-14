package com.achappell.hermesrelay

/** The stable state shown by the conversation doorway when no live turn owns it. */
internal sealed interface AndroidDoorwayState {
    data object NoProfile : AndroidDoorwayState

    data class Unavailable(
        val reason: AndroidDoorwayUnavailableReason,
    ) : AndroidDoorwayState

    data object Ready : AndroidDoorwayState
}

internal enum class AndroidDoorwayUnavailableReason {
    Authorization,
    Connection,
    Microphone,
    UnconfirmedTurn,
}

/**
 * Selects the one stable doorway state that is allowed to speak for idle and
 * recovery conditions. A live capture or accepted turn owns the state label
 * instead; returning null prevents a stale `Ready` card from appearing over it.
 */
internal fun resolveAndroidDoorwayState(
    snapshot: AndroidClientSnapshot,
    isConnected: Boolean,
    hasAcceptedTurn: Boolean,
    isCapturing: Boolean,
    hasUnconfirmedTurn: Boolean,
    captureBlock: AndroidCaptureBlock?,
): AndroidDoorwayState? {
    if (snapshot.selectedProfile == null) {
        return AndroidDoorwayState.NoProfile
    }

    if (hasAcceptedTurn || isCapturing) {
        return null
    }

    if (snapshot.authorizationState != AndroidAuthorizationState.Verified) {
        return AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.Authorization,
        )
    }

    if (!isConnected) {
        return AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.Connection,
        )
    }

    if (hasUnconfirmedTurn) {
        return AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.UnconfirmedTurn,
        )
    }

    return when (captureBlock) {
        AndroidCaptureBlock.PermissionRequired,
        AndroidCaptureBlock.RecognizerUnavailable,
        AndroidCaptureBlock.SessionReplaced,
        -> AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.Microphone,
        )

        AndroidCaptureBlock.ProfileUnavailable -> AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.Authorization,
        )

        AndroidCaptureBlock.NotConnected -> AndroidDoorwayState.Unavailable(
            AndroidDoorwayUnavailableReason.Connection,
        )

        null -> AndroidDoorwayState.Ready
    }
}
