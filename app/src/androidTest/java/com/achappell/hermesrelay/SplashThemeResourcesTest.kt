package com.achappell.hermesrelay

import android.content.res.Configuration
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplashThemeResourcesTest {
    @Test
    fun splash_and_post_splash_window_backgrounds_follow_ui_mode() {
        assertEquals(
            Color.parseColor("#F7F9FC"),
            resolvedColor(
                Configuration.UI_MODE_NIGHT_NO,
                R.style.Theme_HermesRelay_Splash,
                androidx.core.splashscreen.R.attr.windowSplashScreenBackground,
            ),
        )
        assertEquals(
            Color.parseColor("#0B101B"),
            resolvedColor(
                Configuration.UI_MODE_NIGHT_YES,
                R.style.Theme_HermesRelay_Splash,
                androidx.core.splashscreen.R.attr.windowSplashScreenBackground,
            ),
        )
        assertEquals(
            Color.parseColor("#F7F9FC"),
            resolvedColor(
                Configuration.UI_MODE_NIGHT_NO,
                R.style.Theme_HermesRelay,
                android.R.attr.windowBackground,
            ),
        )
        assertEquals(
            Color.parseColor("#0B101B"),
            resolvedColor(
                Configuration.UI_MODE_NIGHT_YES,
                R.style.Theme_HermesRelay,
                android.R.attr.windowBackground,
            ),
        )
    }

    @Test
    fun splash_theme_uses_the_launcher_adaptive_icon_and_restores_app_theme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.style.Theme_HermesRelay_Splash, applicationInfo.theme)

        val theme = context.resources.newTheme().apply {
            applyStyle(R.style.Theme_HermesRelay_Splash, true)
        }
        val attrs = theme.obtainStyledAttributes(
            intArrayOf(
                androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon,
                androidx.core.splashscreen.R.attr.postSplashScreenTheme,
            ),
        )
        try {
            assertEquals(R.mipmap.ic_launcher, attrs.getResourceId(0, 0))
            assertEquals(R.style.Theme_HermesRelay, attrs.getResourceId(1, 0))
        } finally {
            attrs.recycle()
        }
    }

    private fun resolvedColor(nightMode: Int, style: Int, attribute: Int): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val themedContext = context.createConfigurationContext(configuration)
        val theme = themedContext.resources.newTheme().apply { applyStyle(style, true) }
        val attrs = theme.obtainStyledAttributes(intArrayOf(attribute))
        val colorResource = try {
            attrs.getResourceId(0, 0)
        } finally {
            attrs.recycle()
        }
        return themedContext.getColor(colorResource)
    }
}
