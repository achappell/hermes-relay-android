package com.achappell.hermesrelay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(Palette.Light.PRIMARY),
    onPrimary = Color(Palette.Light.ON_PRIMARY),
    primaryContainer = Color(Palette.Light.PRIMARY_CONTAINER),
    onPrimaryContainer = Color(Palette.Light.ON_PRIMARY_CONTAINER),
    secondaryContainer = Color(Palette.Light.SECONDARY_CONTAINER),
    onSecondaryContainer = Color(Palette.Light.ON_SECONDARY_CONTAINER),
    background = Color(Palette.Light.BACKGROUND),
    onBackground = Color(Palette.Light.ON_BACKGROUND),
    surface = Color(Palette.Light.SURFACE),
    onSurface = Color(Palette.Light.ON_SURFACE),
    surfaceVariant = Color(Palette.Light.SURFACE_VARIANT),
    onSurfaceVariant = Color(Palette.Light.ON_SURFACE_VARIANT),
    error = Color(Palette.Light.ERROR),
    onError = Color(Palette.Light.ON_ERROR),
    errorContainer = Color(Palette.Light.ERROR_CONTAINER),
    onErrorContainer = Color(Palette.Light.ON_ERROR_CONTAINER),
    outline = Color(Palette.Light.OUTLINE),
)

private val DarkColors = darkColorScheme(
    primary = Color(Palette.Dark.PRIMARY),
    onPrimary = Color(Palette.Dark.ON_PRIMARY),
    primaryContainer = Color(Palette.Dark.PRIMARY_CONTAINER),
    onPrimaryContainer = Color(Palette.Dark.ON_PRIMARY_CONTAINER),
    secondaryContainer = Color(Palette.Dark.SECONDARY_CONTAINER),
    onSecondaryContainer = Color(Palette.Dark.ON_SECONDARY_CONTAINER),
    background = Color(Palette.Dark.BACKGROUND),
    onBackground = Color(Palette.Dark.ON_BACKGROUND),
    surface = Color(Palette.Dark.SURFACE),
    onSurface = Color(Palette.Dark.ON_SURFACE),
    surfaceVariant = Color(Palette.Dark.SURFACE_VARIANT),
    onSurfaceVariant = Color(Palette.Dark.ON_SURFACE_VARIANT),
    error = Color(Palette.Dark.ERROR),
    onError = Color(Palette.Dark.ON_ERROR),
    errorContainer = Color(Palette.Dark.ERROR_CONTAINER),
    onErrorContainer = Color(Palette.Dark.ON_ERROR_CONTAINER),
    outline = Color(Palette.Dark.OUTLINE),
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
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
