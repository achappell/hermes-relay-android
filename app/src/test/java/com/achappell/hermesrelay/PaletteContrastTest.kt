package com.achappell.hermesrelay

import com.achappell.hermesrelay.ui.theme.Contrast
import com.achappell.hermesrelay.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UX-DR21` names WCAG 2.2 AA contrast targets. `5-A-2` implemented the reading
 * order but explicitly left contrast unmeasured. These tests measure it.
 *
 * `5-A-3` replaced every value in the palette with Night Console. The guarantee
 * had to survive that swap rather than lapse across it, so these tests cover
 * both appearances, every ink on every surface it can land on, and the four
 * state roles — which are rendered as text and not only as decoration.
 */
class PaletteContrastTest {
    @Test
    fun the_contrast_formula_matches_known_reference_values() {
        // Black on white is the documented maximum, 21:1.
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        // A colour against itself has no contrast at all.
        assertEquals(1.0, Contrast.ratio(0xFF54468B.toInt(), 0xFF54468B.toInt()), 0.001)
        // Mid grey on white is a widely published 4.54:1 reference point.
        assertEquals(4.54, Contrast.ratio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.02)
    }

    @Test
    fun every_text_pair_meets_wcag_aa_for_body_text() {
        val failures = Palette.textPairs.mapNotNull { (name, foreground, background) ->
            val ratio = Contrast.ratio(foreground, background)
            if (ratio < 4.5) "%s = %.2f:1".format(name, ratio) else null
        }

        assertTrue(
            "these pairs fall below WCAG AA 4.5:1 for body text: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun the_measurement_can_actually_fail() {
        // Guard against a vacuous suite: a deliberately poor pair must be caught.
        val ratio = Contrast.ratio(0xFFBBBBBB.toInt(), 0xFFFFFFFF.toInt())
        assertTrue("a known-bad pair passed, so the check proves nothing", ratio < 4.5)
    }

    @Test
    fun both_appearances_and_every_state_role_are_actually_covered() {
        // The previous guard proved the formula can fail. This one proves the
        // *set* is complete: a palette change that quietly dropped the light
        // adaptation, or a state role, would otherwise still pass a green suite.
        val names = Palette.textPairs.map { it.first }

        for (appearance in listOf("dark", "light")) {
            for (role in listOf("primaryInk", "secondaryInk", "live", "attention", "identity", "unavailable")) {
                for (surface in listOf("base", "consoleSurface", "panel", "raisedPanel")) {
                    val pair = "$appearance $role/$surface"
                    assertTrue("$pair is not measured by any test pair", names.contains(pair))
                }
            }
        }

        // Ink on a filled state colour is a different question from ink on a
        // surface, and is measured separately.
        for (appearance in listOf("dark", "light")) {
            assertTrue(names.contains("$appearance onIdentity/identity"))
            assertTrue(names.contains("$appearance onUnavailable/unavailable"))
        }
    }

    @Test
    fun container_slots_with_different_ink_do_not_share_a_value() {
        // Material resolves a container's content colour by matching the
        // container against each scheme slot, so two slots holding the same
        // value are indistinguishable to it. errorContainer once reused the
        // plain panel value, and every ordinary Card therefore resolved to
        // onErrorContainer and drew informational text in the unavailable
        // colour. Only a device pass caught it; this catches it next time.
        //
        // Slots may share a value when their ink agrees -- background/surface
        // legitimately do. The defect is a shared value with differing ink.
        for ((appearance, slots) in mapOf(
            "dark" to listOf(
                Triple("surfaceVariant", Palette.Dark.PANEL, Palette.Dark.SECONDARY_INK),
                Triple("errorContainer", Palette.Dark.ERROR_PANEL, Palette.Dark.UNAVAILABLE),
                Triple("primaryContainer", Palette.Dark.RAISED_PANEL, Palette.Dark.PRIMARY_INK),
                Triple("background", Palette.Dark.BASE, Palette.Dark.PRIMARY_INK),
            ),
            "light" to listOf(
                Triple("surfaceVariant", Palette.Light.CONSOLE_SURFACE, Palette.Light.SECONDARY_INK),
                Triple("errorContainer", Palette.Light.ERROR_PANEL, Palette.Light.UNAVAILABLE),
                Triple("primaryContainer", Palette.Light.RAISED_PANEL, Palette.Light.PRIMARY_INK),
                Triple("background", Palette.Light.BASE, Palette.Light.PRIMARY_INK),
            ),
        )) {
            for (a in slots) {
                for (b in slots) {
                    if (a.first >= b.first) continue
                    assertTrue(
                        "$appearance ${a.first} and ${b.first} share the container " +
                            "value #%06X but want different ink, so Material cannot ".format(
                                a.second and 0xFFFFFF,
                            ) + "tell them apart",
                        a.second != b.second || a.third == b.third,
                    )
                }
            }
        }
    }

    @Test
    fun the_light_state_roles_are_darkened_rather_than_exempted() {
        // Night Console's live and attention are bright by design and cannot
        // reach 4.5:1 as text on a light surface. The honest fix is a darker
        // same-hue variant, never a lowered target -- so the light values must
        // differ from the dark ones, and must clear the threshold on the
        // lightest surface in the set.
        assertTrue(
            "light live must not reuse the dark value",
            Palette.Light.LIVE != Palette.Dark.LIVE,
        )
        assertTrue(
            "light attention must not reuse the dark value",
            Palette.Light.ATTENTION != Palette.Dark.ATTENTION,
        )

        val lightest = Palette.Light.PANEL
        for ((name, role) in listOf(
            "live" to Palette.Light.LIVE,
            "attention" to Palette.Light.ATTENTION,
            "identity" to Palette.Light.IDENTITY,
            "unavailable" to Palette.Light.UNAVAILABLE,
        )) {
            val ratio = Contrast.ratio(role, lightest)
            assertTrue(
                "light %s on the lightest surface is only %.2f:1".format(name, ratio),
                ratio >= 4.5,
            )
        }
    }
}
