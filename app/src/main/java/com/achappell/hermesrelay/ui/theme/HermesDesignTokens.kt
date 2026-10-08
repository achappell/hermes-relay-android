package com.achappell.hermesrelay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** The Night Console 4/8/12/16/24/32 dp spacing rhythm. */
internal object HermesSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Shared shape roles; borders stay explicit because not every surface is outlined. */
internal object HermesShapes {
    val card = RoundedCornerShape(16.dp)
    val bar = RoundedCornerShape(24.dp)
    val field = RoundedCornerShape(18.dp)
    val cardOutlineWidth = 0.5.dp
    const val cardOutlineAlpha = 0.22f

    val material = Shapes(
        small = field,
        medium = card,
        large = card,
    )
}

/** Semantic styles mapped onto Material roles without changing the font family. */
internal object HermesTypography {
    private val defaults = Typography()

    val orbStatus: TextStyle = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val header: TextStyle = defaults.labelLarge
    val caption: TextStyle = defaults.bodySmall
    val footnote: TextStyle = defaults.labelSmall
    val callout: TextStyle = defaults.bodyLarge.copy(fontSize = 16.sp)
    val transcriptTitle3: TextStyle = defaults.headlineSmall.copy(
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )
    val roleLabel: TextStyle = defaults.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )

    val material: Typography = defaults.copy(
        titleMedium = orbStatus,
        labelLarge = header,
        bodySmall = caption,
        labelSmall = footnote,
        bodyLarge = callout,
        headlineSmall = transcriptTitle3,
    )

    /** Role labels are fixed vocabulary, not translated prose. */
    fun roleLabelText(value: String): String = value.uppercase(Locale.ROOT)
}

/** State-color fills and HUD tints, composited over an opaque surface. */
internal object HermesStateWashes {
    const val stateAlpha = 0.16f
    const val hudGradientAlpha = 0.12f

    fun overlay(tint: Color, surface: Color): Color = Color(
        blendArgb(tint.toArgb(), surface.toArgb(), stateAlpha),
    )

    fun hudGradientTint(tint: Color, base: Color): Color = Color(
        blendArgb(tint.toArgb(), base.toArgb(), hudGradientAlpha),
    )

    /** Palette inputs are opaque ARGB values, so this is a single straight-alpha blend. */
    fun blendArgb(tint: Int, surface: Int, alpha: Float): Int {
        val inverse = 1f - alpha
        fun channel(shift: Int): Int = (
            ((tint shr shift) and 0xFF) * alpha +
                ((surface shr shift) and 0xFF) * inverse
            ).roundToInt()

        return (0xFF shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }
}
