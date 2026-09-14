package com.achappell.hermesrelay

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal enum class AndroidMotionMode {
    Animated,
    Static,
}

/** Zero animation scales are Android's system-level reduced-motion signal. */
internal fun resolveAndroidMotionMode(
    animatorDurationScale: Float,
    transitionAnimationScale: Float,
    windowAnimationScale: Float,
): AndroidMotionMode = if (
    animatorDurationScale <= 0f ||
    transitionAnimationScale <= 0f ||
    windowAnimationScale <= 0f
) {
    AndroidMotionMode.Static
} else {
    AndroidMotionMode.Animated
}

@Composable
internal fun rememberAndroidMotionMode(): AndroidMotionMode {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        resolveAndroidMotionMode(
            animatorDurationScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ),
            transitionAnimationScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ),
            windowAnimationScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                1f,
            ),
        )
    }
}
