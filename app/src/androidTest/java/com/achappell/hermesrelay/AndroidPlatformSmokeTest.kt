package com.achappell.hermesrelay

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Thin real-wiring smoke for `ANDROID-TEST-01`: proves the injected platform
 * value matches the device it runs on, and states the API level in the log so
 * a result is never attributed to the wrong image.
 *
 * Run on two images (the minSdk 26 image and the newest installed image):
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.achappell.hermesrelay.AndroidPlatformSmokeTest`
 *
 * This does not exercise routes, microphone, speaker or foreground-service
 * behaviour; those need a physical device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPlatformSmokeTest {
    @Test
    fun the_runtime_platform_matches_the_device_it_runs_on() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = AndroidPlatform.current(context)

        Log.i("HermesPlatformSmoke", "apiLevel=${platform.apiLevel}")
        assertEquals(Build.VERSION.SDK_INT, platform.apiLevel)
        assertEquals(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            platform.supportsPlaybackStartThreshold,
        )
        assertTrue(platform.foregroundServiceSupported)
    }
}
