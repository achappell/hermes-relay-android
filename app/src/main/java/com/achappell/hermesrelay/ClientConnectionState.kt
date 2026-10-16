package com.achappell.hermesrelay

/** UI projection of the existing recovery rules, not a second reconnect policy. */
internal fun canAttemptClientConnection(
    snapshot: AndroidClientSnapshot,
    recoveryState: AndroidRecoveryState,
    availabilityBlock: AndroidAvailabilityBlock?,
): Boolean {
    val reason = (recoveryState.connection as? AndroidConnectionState.Failed)?.reasonCode
        ?: snapshot.unavailableReason
    return snapshot.selectedProfile != null &&
        availabilityBlock != AndroidAvailabilityBlock.Authorization && when (reason) {
            AndroidHomeUnavailableReason.MissingBinding,
            AndroidHomeUnavailableReason.InvalidBinding,
            AndroidHomeUnavailableReason.InvalidCredential,
            AndroidHomeUnavailableReason.SecureStorageUnavailable,
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
            AndroidHomeUnavailableReason.Unauthorized,
            AndroidHomeUnavailableReason.StaleConversation,
            AndroidHomeUnavailableReason.ConversationMismatch,
            AndroidHomeUnavailableReason.RequestRejected,
            AndroidHomeUnavailableReason.ProtocolError,
            AndroidHomeUnavailableReason.CapabilityUnavailable,
            AndroidHomeUnavailableReason.ClaimLimit,
            -> false
            else -> true
        }
}
