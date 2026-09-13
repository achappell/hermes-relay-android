package com.achappell.hermesrelay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Night Console mapped onto Material's scheme slots.
 *
 * The mapping is deliberate rather than mechanical. `primary` carries the
 * identity role, so Profile selection and interactive focus are the same colour
 * the design names; `error` carries unavailable, so stock Material components
 * inherit the right failure colour without every call site overriding it. The
 * roles Material has no slot for — live and attention — are not forced into a
 * near-enough slot here. They live in `HermesStateColors`.
 */
private val DarkColors = darkColorScheme(
    primary = Color(Palette.Dark.IDENTITY),
    onPrimary = Color(Palette.Dark.ON_IDENTITY),
    primaryContainer = Color(Palette.Dark.RAISED_PANEL),
    onPrimaryContainer = Color(Palette.Dark.PRIMARY_INK),
    secondaryContainer = Color(Palette.Dark.RAISED_PANEL),
    onSecondaryContainer = Color(Palette.Dark.PRIMARY_INK),
    background = Color(Palette.Dark.BASE),
    onBackground = Color(Palette.Dark.PRIMARY_INK),
    surface = Color(Palette.Dark.BASE),
    onSurface = Color(Palette.Dark.PRIMARY_INK),
    surfaceVariant = Color(Palette.Dark.PANEL),
    onSurfaceVariant = Color(Palette.Dark.SECONDARY_INK),
    error = Color(Palette.Dark.UNAVAILABLE),
    onError = Color(Palette.Dark.ON_UNAVAILABLE),
    errorContainer = Color(Palette.Dark.ERROR_PANEL),
    onErrorContainer = Color(Palette.Dark.UNAVAILABLE),
    outline = Color(Palette.Dark.OUTLINE),
)

private val LightColors = lightColorScheme(
    primary = Color(Palette.Light.IDENTITY),
    onPrimary = Color(Palette.Light.ON_IDENTITY),
    primaryContainer = Color(Palette.Light.RAISED_PANEL),
    onPrimaryContainer = Color(Palette.Light.PRIMARY_INK),
    secondaryContainer = Color(Palette.Light.RAISED_PANEL),
    onSecondaryContainer = Color(Palette.Light.PRIMARY_INK),
    background = Color(Palette.Light.BASE),
    onBackground = Color(Palette.Light.PRIMARY_INK),
    surface = Color(Palette.Light.BASE),
    onSurface = Color(Palette.Light.PRIMARY_INK),
    surfaceVariant = Color(Palette.Light.CONSOLE_SURFACE),
    onSurfaceVariant = Color(Palette.Light.SECONDARY_INK),
    error = Color(Palette.Light.UNAVAILABLE),
    onError = Color(Palette.Light.ON_UNAVAILABLE),
    errorContainer = Color(Palette.Light.ERROR_PANEL),
    onErrorContainer = Color(Palette.Light.UNAVAILABLE),
    outline = Color(Palette.Light.OUTLINE),
)

/**
 * The Hermes Relay theme.
 *
 * Dynamic colour is deliberately not used: the palette's contrast is asserted
 * by test, and a wallpaper-derived scheme would silently replace verified
 * values with unverified ones.
 */
@Composable
internal fun HermesRelayTheme(
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalHermesStateColors provides if (dark) DarkStateColors else LightStateColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
