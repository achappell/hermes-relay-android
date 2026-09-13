package com.achappell.hermesrelay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The four Night Console state roles.
 *
 * Material's `ColorScheme` has no slot that means "live", "attention", or
 * "unavailable" — its roles describe prominence, not truth. Mapping state onto
 * `primary`/`error` is how the surface ended up unable to distinguish a healthy
 * live signal from pending work from a failed identity.
 *
 * These four are therefore carried alongside the scheme rather than inside it,
 * published through [LocalHermesStateColors]. Every value is asserted for
 * contrast in `PaletteContrastTest`, in both appearances, because all four are
 * rendered as text and not only as decoration.
 */
@Immutable
internal data class HermesStateColors(
    /** Active and healthy: capture is open, playback is running. */
    val live: Color,
    /** The current phase, and work that is pending rather than wrong. */
    val attention: Color,
    /** Profile identity and interactive focus. */
    val identity: Color,
    /** Transport, permission, and identity failure. */
    val unavailable: Color,
    /** Ink for text placed on top of a filled [identity] surface. */
    val onIdentity: Color,
    /** Ink for text placed on top of a filled [unavailable] surface. */
    val onUnavailable: Color,
    /** The console grouping surface: header and bottom control bar. */
    val consoleSurface: Color,
    /** Cards: transcript, recovery, and setup. */
    val panel: Color,
    /** The selected Profile, or context that outranks a plain panel. */
    val raisedPanel: Color,
)

internal val DarkStateColors = HermesStateColors(
    live = Color(Palette.Dark.LIVE),
    attention = Color(Palette.Dark.ATTENTION),
    identity = Color(Palette.Dark.IDENTITY),
    unavailable = Color(Palette.Dark.UNAVAILABLE),
    onIdentity = Color(Palette.Dark.ON_IDENTITY),
    onUnavailable = Color(Palette.Dark.ON_UNAVAILABLE),
    consoleSurface = Color(Palette.Dark.CONSOLE_SURFACE),
    panel = Color(Palette.Dark.PANEL),
    raisedPanel = Color(Palette.Dark.RAISED_PANEL),
)

internal val LightStateColors = HermesStateColors(
    live = Color(Palette.Light.LIVE),
    attention = Color(Palette.Light.ATTENTION),
    identity = Color(Palette.Light.IDENTITY),
    unavailable = Color(Palette.Light.UNAVAILABLE),
    onIdentity = Color(Palette.Light.ON_IDENTITY),
    onUnavailable = Color(Palette.Light.ON_UNAVAILABLE),
    consoleSurface = Color(Palette.Light.CONSOLE_SURFACE),
    panel = Color(Palette.Light.PANEL),
    raisedPanel = Color(Palette.Light.RAISED_PANEL),
)

/**
 * Defaults to the dark appearance because Night Console is dark-first. A
 * composable reading this outside [HermesRelayTheme] still gets coherent
 * values rather than a crash or an invisible one.
 */
internal val LocalHermesStateColors = staticCompositionLocalOf { DarkStateColors }
