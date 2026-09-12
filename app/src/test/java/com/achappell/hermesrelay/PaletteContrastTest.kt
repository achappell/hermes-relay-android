package com.achappell.hermesrelay

import com.achappell.hermesrelay.ui.theme.Contrast
import com.achappell.hermesrelay.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UX-DR21` names WCAG 2.2 AA contrast targets. `5-A-2` implemented the reading
 * order but explicitly left contrast unmeasured. These tests measure it.
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
}
