package com.achappell.hermesrelay

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Granting `RECORD_AUDIO` is visible to the permission helper and enables the
 * platform recogniser path. Kept in its own class: the rule grants for the
 * whole class, and revoking would kill the instrumentation process, so the
 * grant outlives the test (`MicrophoneCaptureTest` tolerates either state).
 */
@RunWith(AndroidJUnit4::class)
class MicrophonePermissionGrantedTest {
    @get:Rule
    val grantRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun granting_the_permission_is_seen_by_the_platform_source_and_the_recognizer() {
        val source = PlatformRuntimePermissionSource(context, Manifest.permission.RECORD_AUDIO)
        assertEquals(RuntimePermissionState.Granted, source.state())

        val input = PlatformSpeechInput(context)
        assumeTrue(
            "no speech recognizer is installed on this device",
            input.authorization() != AndroidSpeechAuthorization.Unavailable,
        )
        assertEquals(AndroidSpeechAuthorization.Granted, input.authorization())
    }
}
