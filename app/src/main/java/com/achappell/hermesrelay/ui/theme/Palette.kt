package com.achappell.hermesrelay.ui.theme

/**
 * The Hermes Relay palette, as ARGB values.
 *
 * Colours live here as plain numbers rather than Compose `Color` values so the
 * contrast obligation in `UX-DR21` can be *measured* by an ordinary unit test
 * instead of eyeballed. Every foreground/background pair used for text is
 * asserted against WCAG 2.2 AA in `PaletteContrastTest`.
 *
 * Change a value here and the contrast test will tell you whether the change is
 * still readable.
 */
internal object Palette {
    object Light {
        val PRIMARY = 0xFF54468Bu.toInt()
        val ON_PRIMARY = 0xFFFFFFFFu.toInt()
        val PRIMARY_CONTAINER = 0xFFE4DEFFu.toInt()
        val ON_PRIMARY_CONTAINER = 0xFF105043u.toInt()

        val SECONDARY_CONTAINER = 0xFFE3E0F9u.toInt()
        val ON_SECONDARY_CONTAINER = 0xFF1B1A2Cu.toInt()

        val BACKGROUND = 0xFFFCF8FFu.toInt()
        val ON_BACKGROUND = 0xFF1B1B21u.toInt()
        val SURFACE = 0xFFFCF8FFu.toInt()
        val ON_SURFACE = 0xFF1B1B21u.toInt()
        val SURFACE_VARIANT = 0xFFE4E1EFu.toInt()
        val ON_SURFACE_VARIANT = 0xFF464659u.toInt()

        val ERROR = 0xFFBA1A1Au.toInt()
        val ON_ERROR = 0xFFFFFFFFu.toInt()
        val ERROR_CONTAINER = 0xFFFFDAD6u.toInt()
        val ON_ERROR_CONTAINER = 0xFF93000Au.toInt()

        val OUTLINE = 0xFF777687u.toInt()
    }

    object Dark {
        val PRIMARY = 0xFFC0C1FFu.toInt()
        val ON_PRIMARY = 0xFF251659u.toInt()
        val PRIMARY_CONTAINER = 0xFF3C2F72u.toInt()
        val ON_PRIMARY_CONTAINER = 0xFFE4DEFFu.toInt()

        val SECONDARY_CONTAINER = 0xFF454357u.toInt()
        val ON_SECONDARY_CONTAINER = 0xFFE3E0F9u.toInt()

        val BACKGROUND = 0xFF131318u.toInt()
        val ON_BACKGROUND = 0xFFE4E1E9u.toInt()
        val SURFACE = 0xFF131318u.toInt()
        val ON_SURFACE = 0xFFE4E1E9u.toInt()
        val SURFACE_VARIANT = 0xFF464659u.toInt()
        val ON_SURFACE_VARIANT = 0xFFC7C5D5u.toInt()

        val ERROR = 0xFFFFB4ABu.toInt()
        val ON_ERROR = 0xFF690005u.toInt()
        val ERROR_CONTAINER = 0xFF93000Au.toInt()
        val ON_ERROR_CONTAINER = 0xFFFFDAD6u.toInt()

        val OUTLINE = 0xFF918FA0u.toInt()
    }

    /** Every text pair the surface actually renders, for the contrast test. */
    val textPairs: List<Triple<String, Int, Int>> = listOf(
        Triple("light onBackground/background", Light.ON_BACKGROUND, Light.BACKGROUND),
        Triple("light onSurface/surface", Light.ON_SURFACE, Light.SURFACE),
        Triple("light onSurfaceVariant/surfaceVariant", Light.ON_SURFACE_VARIANT, Light.SURFACE_VARIANT),
        Triple("light onPrimary/primary", Light.ON_PRIMARY, Light.PRIMARY),
        Triple("light onSecondaryContainer/secondaryContainer", Light.ON_SECONDARY_CONTAINER, Light.SECONDARY_CONTAINER),
        Triple("light onErrorContainer/errorContainer", Light.ON_ERROR_CONTAINER, Light.ERROR_CONTAINER),
        Triple("light onError/error", Light.ON_ERROR, Light.ERROR),
        Triple("light error/background", Light.ERROR, Light.BACKGROUND),
        Triple("dark onBackground/background", Dark.ON_BACKGROUND, Dark.BACKGROUND),
        Triple("dark onSurface/surface", Dark.ON_SURFACE, Dark.SURFACE),
        Triple("dark onSurfaceVariant/surfaceVariant", Dark.ON_SURFACE_VARIANT, Dark.SURFACE_VARIANT),
        Triple("dark onPrimary/primary", Dark.ON_PRIMARY, Dark.PRIMARY),
        Triple("dark onSecondaryContainer/secondaryContainer", Dark.ON_SECONDARY_CONTAINER, Dark.SECONDARY_CONTAINER),
        Triple("dark onErrorContainer/errorContainer", Dark.ON_ERROR_CONTAINER, Dark.ERROR_CONTAINER),
        Triple("dark onError/error", Dark.ON_ERROR, Dark.ERROR),
        Triple("dark error/background", Dark.ERROR, Dark.BACKGROUND),
    )
}

/** WCAG 2.2 relative luminance and contrast ratio. */
internal object Contrast {
    fun ratio(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Int): Double {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(value: Int): Double {
        val sRgb = value / 255.0
        return if (sRgb <= 0.03928) {
            sRgb / 12.92
        } else {
            Math.pow((sRgb + 0.055) / 1.055, 2.4)
        }
    }
}
