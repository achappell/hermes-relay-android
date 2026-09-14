package com.achappell.hermesrelay

/** The user-facing reason the typed composer cannot submit right now. */
internal enum class AndroidComposerBlock {
    NoProfile,
    Authorization,
    Disconnected,
    ActiveTurn,
    EmptyPrompt,
}

/**
 * Resolves the composer boundary without hiding it in button opacity.
 *
 * A disconnected composer remains useful: its draft can be edited and kept
 * locally, but sending waits for the Session. A missing Profile and an
 * unverified Profile are separate failures because they have different fixes.
 */
internal fun resolveAndroidComposerBlock(
    hasProfile: Boolean,
    isAuthorized: Boolean,
    isConnected: Boolean,
    hasAcceptedTurn: Boolean,
    prompt: String,
): AndroidComposerBlock? = when {
    !hasProfile -> AndroidComposerBlock.NoProfile
    !isAuthorized -> AndroidComposerBlock.Authorization
    hasAcceptedTurn -> AndroidComposerBlock.ActiveTurn
    !isConnected -> AndroidComposerBlock.Disconnected
    prompt.isBlank() -> AndroidComposerBlock.EmptyPrompt
    else -> null
}
