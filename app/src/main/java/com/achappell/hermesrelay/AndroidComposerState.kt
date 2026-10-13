package com.achappell.hermesrelay

/** The user-facing reason the typed composer cannot submit right now. */
internal enum class AndroidComposerBlock {
    NoProfile,
    Authorization,
    Disconnected,
    ActiveTurn,
    UnconfirmedTurn,
    EmptyPrompt,
}

/**
 * Resolves the composer boundary without hiding it in button opacity.
 *
 * A disconnected composer remains useful: its draft can be edited and kept
 * locally, but sending waits for the Session. Authorization repair is distinct
 * from ordinary connection recovery; neither presentation grants admission.
 */
internal fun resolveAndroidComposerBlock(
    hasProfile: Boolean,
    availabilityBlock: AndroidAvailabilityBlock?,
    hasAcceptedTurn: Boolean,
    prompt: String,
    hasUnconfirmedTurn: Boolean = false,
): AndroidComposerBlock? = when {
    !hasProfile -> AndroidComposerBlock.NoProfile
    availabilityBlock == AndroidAvailabilityBlock.Authorization -> AndroidComposerBlock.Authorization
    hasAcceptedTurn -> AndroidComposerBlock.ActiveTurn
    hasUnconfirmedTurn -> AndroidComposerBlock.UnconfirmedTurn
    availabilityBlock == AndroidAvailabilityBlock.Connection -> AndroidComposerBlock.Disconnected
    prompt.isBlank() -> AndroidComposerBlock.EmptyPrompt
    else -> null
}
