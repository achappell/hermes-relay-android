package com.achappell.hermesrelay

/** Presentation guidance only; the real snapshot still owns authorization admission. */
internal enum class AndroidAvailabilityBlock {
    Authorization,
    Connection,
}

internal fun resolveAndroidAvailabilityBlock(
    snapshot: AndroidClientSnapshot,
    connection: AndroidConnectionState,
): AndroidAvailabilityBlock? {
    val failureReason = (connection as? AndroidConnectionState.Failed)?.reasonCode
    if (snapshot.unavailableReason.isAuthorizationFailure() ||
        failureReason.isAuthorizationFailure() ||
        snapshot.authorizationState == AndroidAuthorizationState.NotConfigured ||
        (snapshot.authorizationState == AndroidAuthorizationState.Unavailable &&
            snapshot.unavailableReason == null)
    ) {
        return AndroidAvailabilityBlock.Authorization
    }
    if (connection != AndroidConnectionState.Connected) {
        return AndroidAvailabilityBlock.Connection
    }
    return if (snapshot.authorizationState != AndroidAuthorizationState.Verified) {
        AndroidAvailabilityBlock.Authorization
    } else {
        null
    }
}

private fun AndroidHomeUnavailableReason?.isAuthorizationFailure(): Boolean = when (this) {
    AndroidHomeUnavailableReason.MissingBinding,
    AndroidHomeUnavailableReason.InvalidBinding,
    AndroidHomeUnavailableReason.InvalidCredential,
    AndroidHomeUnavailableReason.SecureStorageUnavailable,
    AndroidHomeUnavailableReason.AuthorizationUnavailable,
    AndroidHomeUnavailableReason.Unauthorized,
    -> true
    else -> false
}
