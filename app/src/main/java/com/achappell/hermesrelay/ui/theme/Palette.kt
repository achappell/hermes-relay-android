package com.achappell.hermesrelay.ui.theme

/**
 * The Hermes Night Console palette for Android, as ARGB values.
 *
 * Night Console is the shared Hermes visual identity. The TUI, iOS, and Android
 * each adapt it rather than copy one another; what travels between surfaces is
 * the set of **semantic roles**, not the geometry. `5-A-3` adopts those roles
 * here.
 *
 * Colours live as plain numbers rather than Compose `Color` values so the
 * contrast obligation in `UX-DR21` can be *measured* by an ordinary unit test
 * instead of eyeballed. Every foreground/background pair the surface renders is
 * asserted against WCAG 2.2 AA in `PaletteContrastTest`.
 *
 * Change a value here and the contrast test will tell you whether the change is
 * still readable.
 */
internal object Palette {
    /**
     * The four state roles. These are what make the doorway able to say what it
     * means: a healthy live signal, pending work, Profile identity, and outright
     * failure are different colours because they are different facts. Material's
     * `ColorScheme` has no slot for any of them, so they are carried separately
     * and published through `LocalHermesStateColors`.
     */
    object Dark {
        // Night Console canonical values, shared with iOS.
        val BASE = 0xFF0B101Bu.toInt()
        val CONSOLE_SURFACE = 0xFF101725u.toInt()
        val PANEL = 0xFF0D1320u.toInt()
        val RAISED_PANEL = 0xFF182338u.toInt()

        val PRIMARY_INK = 0xFFEAF7FFu.toInt()
        val SECONDARY_INK = 0xFFB3C0D2u.toInt()

        val LIVE = 0xFF62E6C7u.toInt()
        val ATTENTION = 0xFFFFCF5Cu.toInt()
        val IDENTITY = 0xFF7C8CFFu.toInt()
        val UNAVAILABLE = 0xFFFF7D9Cu.toInt()

        // Ink placed on top of a filled state colour, not on a dark surface.
        val ON_IDENTITY = BASE
        val ON_UNAVAILABLE = BASE

        val OUTLINE = 0xFF3A4759u.toInt()
    }

    /**
     * The light adaptation.
     *
     * The surfaces invert, but the roles keep their meaning and their hue. The
     * four state colours are *not* reused directly: `#62E6C7` live and
     * `#FFCF5C` attention are bright by design and cannot reach 4.5:1 as text on
     * a light surface. Each has a darkened same-hue variant instead, derived to
     * clear the AA threshold with headroom on the lightest surface in this set.
     * Lowering the target was never an option; the colour moved instead.
     */
    object Light {
        val BASE = 0xFFF7F9FCu.toInt()
        val CONSOLE_SURFACE = 0xFFECF1F8u.toInt()
        val PANEL = 0xFFFFFFFFu.toInt()
        val RAISED_PANEL = 0xFFE3EAF5u.toInt()

        val PRIMARY_INK = 0xFF0B101Bu.toInt()
        val SECONDARY_INK = 0xFF44506Au.toInt()

        val LIVE = 0xFF2D6A5Cu.toInt()
        val ATTENTION = 0xFF735D29u.toInt()
        val IDENTITY = 0xFF4F5AA3u.toInt()
        val UNAVAILABLE = 0xFF94485Au.toInt()

        val ON_IDENTITY = 0xFFFFFFFFu.toInt()
        val ON_UNAVAILABLE = 0xFFFFFFFFu.toInt()

        val OUTLINE = 0xFF7B879Bu.toInt()
    }

    /**
     * Every text pair the surface actually renders, for the contrast test.
     *
     * Both appearances, every ink on every surface it can land on. The state
     * roles are included because they are rendered *as text* — a phase label, a
     * failure explanation — and not only as decoration.
     */
    val textPairs: List<Triple<String, Int, Int>> = buildList {
        fun appearance(
            name: String,
            surfaces: List<Pair<String, Int>>,
            inks: List<Pair<String, Int>>,
        ) {
            for ((surfaceName, surface) in surfaces) {
                for ((inkName, ink) in inks) {
                    add(Triple("$name $inkName/$surfaceName", ink, surface))
                }
            }
        }

        appearance(
            name = "dark",
            surfaces = listOf(
                "base" to Dark.BASE,
                "consoleSurface" to Dark.CONSOLE_SURFACE,
                "panel" to Dark.PANEL,
                "raisedPanel" to Dark.RAISED_PANEL,
            ),
            inks = listOf(
                "primaryInk" to Dark.PRIMARY_INK,
                "secondaryInk" to Dark.SECONDARY_INK,
                "live" to Dark.LIVE,
                "attention" to Dark.ATTENTION,
                "identity" to Dark.IDENTITY,
                "unavailable" to Dark.UNAVAILABLE,
            ),
        )

        appearance(
            name = "light",
            surfaces = listOf(
                "base" to Light.BASE,
                "consoleSurface" to Light.CONSOLE_SURFACE,
                "panel" to Light.PANEL,
                "raisedPanel" to Light.RAISED_PANEL,
            ),
            inks = listOf(
                "primaryInk" to Light.PRIMARY_INK,
                "secondaryInk" to Light.SECONDARY_INK,
                "live" to Light.LIVE,
                "attention" to Light.ATTENTION,
                "identity" to Light.IDENTITY,
                "unavailable" to Light.UNAVAILABLE,
            ),
        )

        // Ink on a filled state colour: a button or chip, rather than a surface.
        add(Triple("dark onIdentity/identity", Dark.ON_IDENTITY, Dark.IDENTITY))
        add(Triple("dark onUnavailable/unavailable", Dark.ON_UNAVAILABLE, Dark.UNAVAILABLE))
        add(Triple("light onIdentity/identity", Light.ON_IDENTITY, Light.IDENTITY))
        add(Triple("light onUnavailable/unavailable", Light.ON_UNAVAILABLE, Light.UNAVAILABLE))
    }
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
