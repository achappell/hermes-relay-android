package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMotionTest {
    @Test
    fun any_zero_system_animation_scale_selects_static_motion() {
        assertEquals(
            AndroidMotionMode.Static,
            resolveAndroidMotionMode(
                animatorDurationScale = 0f,
                transitionAnimationScale = 1f,
                windowAnimationScale = 1f,
            ),
        )
        assertEquals(
            AndroidMotionMode.Static,
            resolveAndroidMotionMode(
                animatorDurationScale = 1f,
                transitionAnimationScale = 0f,
                windowAnimationScale = 1f,
            ),
        )
        assertEquals(
            AndroidMotionMode.Static,
            resolveAndroidMotionMode(
                animatorDurationScale = 1f,
                transitionAnimationScale = 1f,
                windowAnimationScale = 0f,
            ),
        )
    }

    @Test
    fun positive_system_animation_scales_allow_animated_motion() {
        assertEquals(
            AndroidMotionMode.Animated,
            resolveAndroidMotionMode(
                animatorDurationScale = 1f,
                transitionAnimationScale = 1f,
                windowAnimationScale = 1f,
            ),
        )
    }
}
