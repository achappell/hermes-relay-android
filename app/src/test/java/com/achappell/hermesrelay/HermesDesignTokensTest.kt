package com.achappell.hermesrelay

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.achappell.hermesrelay.ui.theme.HermesIcons
import com.achappell.hermesrelay.ui.theme.HermesShapes
import com.achappell.hermesrelay.ui.theme.HermesSpacing
import com.achappell.hermesrelay.ui.theme.HermesStateWashes
import com.achappell.hermesrelay.ui.theme.HermesTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesDesignTokensTest {
    @Test
    fun spacing_uses_the_night_console_rhythm() {
        assertEquals(4f, HermesSpacing.xs.value, 0f)
        assertEquals(8f, HermesSpacing.sm.value, 0f)
        assertEquals(12f, HermesSpacing.md.value, 0f)
        assertEquals(16f, HermesSpacing.lg.value, 0f)
        assertEquals(24f, HermesSpacing.xl.value, 0f)
        assertEquals(32f, HermesSpacing.xxl.value, 0f)
    }

    @Test
    fun state_washes_use_spec_opacities() {
        assertEquals(0.16f, HermesStateWashes.stateAlpha, 0f)
        assertEquals(0.12f, HermesStateWashes.hudGradientAlpha, 0f)
    }

    @Test
    fun shape_and_outline_roles_are_shared_with_material() {
        assertEquals(RoundedCornerShape(16.dp), HermesShapes.card)
        assertEquals(RoundedCornerShape(24.dp), HermesShapes.bar)
        assertEquals(RoundedCornerShape(18.dp), HermesShapes.field)
        assertEquals(HermesShapes.field, HermesShapes.material.small)
        assertEquals(HermesShapes.card, HermesShapes.material.medium)
        assertEquals(HermesShapes.card, HermesShapes.material.large)
        assertEquals(0.5f, HermesShapes.cardOutlineWidth.value, 0f)
        assertEquals(0.22f, HermesShapes.cardOutlineAlpha, 0f)
    }

    @Test
    fun semantic_text_roles_use_sp_and_map_to_material() {
        val styles = listOf(
            HermesTypography.orbStatus,
            HermesTypography.header,
            HermesTypography.caption,
            HermesTypography.footnote,
            HermesTypography.callout,
            HermesTypography.transcriptTitle3,
            HermesTypography.roleLabel,
        )
        assertTrue(styles.all { it.fontSize.type == TextUnitType.Sp })
        assertEquals(FontWeight.SemiBold, HermesTypography.orbStatus.fontWeight)
        assertEquals(16f, HermesTypography.callout.fontSize.value, 0f)
        assertEquals(20f, HermesTypography.transcriptTitle3.fontSize.value, 0f)
        assertEquals(28f, HermesTypography.transcriptTitle3.lineHeight.value, 0f)
        assertEquals(FontWeight.Bold, HermesTypography.roleLabel.fontWeight)
        assertEquals(1f, HermesTypography.roleLabel.letterSpacing.value, 0f)
        assertEquals("STATUS", HermesTypography.roleLabelText("Status"))
        assertEquals(HermesTypography.orbStatus, HermesTypography.material.titleMedium)
        assertEquals(HermesTypography.header, HermesTypography.material.labelLarge)
        assertEquals(HermesTypography.caption, HermesTypography.material.bodySmall)
        assertEquals(HermesTypography.footnote, HermesTypography.material.labelSmall)
        assertEquals(HermesTypography.callout, HermesTypography.material.bodyLarge)
        assertEquals(HermesTypography.transcriptTitle3, HermesTypography.material.headlineSmall)
    }

    @Test
    fun vector_icons_cover_required_roles_and_share_a_24dp_viewport() {
        assertEquals(
            listOf(
                "microphone",
                "waveform",
                "thinkingDots",
                "buffering",
                "speaker",
                "check",
                "pause",
                "warning",
                "interruptHand",
                "listenEar",
                "stop",
                "settings",
                "history",
                "home",
                "diagnostics",
                "qr",
                "overflow",
            ),
            HermesIcons.all.map { it.name },
        )
        assertTrue(
            HermesIcons.all.all {
                it.defaultWidth == 24.dp &&
                    it.defaultHeight == 24.dp &&
                    it.viewportWidth == 24f &&
                    it.viewportHeight == 24f
            },
        )
    }

}
