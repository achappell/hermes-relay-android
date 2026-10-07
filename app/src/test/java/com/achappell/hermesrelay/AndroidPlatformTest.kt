package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Both sides of every API-level decision the Client makes today, with the
 * platform stated explicitly in each test. None of these read the machine the
 * tests run on (`ANDROID-TEST-01`).
 *
 * There is no debug-versus-release branch in the app today (`BuildConfig` is
 * not generated and nothing reads the debuggable flag), so there is nothing to
 * pin on that axis.
 */
class AndroidPlatformTest {
    private fun platform(api: Int) = AndroidPlatform(
        apiLevel = api,
        notificationsPermitted = true,
        foregroundServiceSupported = true,
    )

    @Test
    fun the_start_threshold_is_unavailable_through_api_30() {
        assertFalse(platform(26).supportsPlaybackStartThreshold)
        assertFalse(platform(30).supportsPlaybackStartThreshold)
    }

    @Test
    fun the_start_threshold_is_available_from_api_31() {
        assertTrue(platform(31).supportsPlaybackStartThreshold)
        assertTrue(platform(37).supportsPlaybackStartThreshold)
    }

    @Test
    fun below_api_31_the_buffer_is_sized_to_the_queued_frames() {
        val control = RecordingBufferControl()

        preparePlaybackBuffer(platform(30), control, queuedFrames = 8_000)

        assertEquals(listOf("size:8000"), control.calls)
    }

    @Test
    fun from_api_31_the_start_threshold_is_set_to_the_queued_frames() {
        val control = RecordingBufferControl()

        preparePlaybackBuffer(platform(31), control, queuedFrames = 8_000)

        assertEquals(listOf("threshold:8000"), control.calls)
    }

    @Test
    fun an_empty_queue_is_clamped_to_one_frame_on_both_branches() {
        val old = RecordingBufferControl()
        val new = RecordingBufferControl()

        preparePlaybackBuffer(platform(26), old, queuedFrames = 0)
        preparePlaybackBuffer(platform(37), new, queuedFrames = 0)

        assertEquals(listOf("size:1"), old.calls)
        assertEquals(listOf("threshold:1"), new.calls)
    }

    @Test
    fun a_rejected_buffer_call_fails_playback_start_on_both_branches() {
        listOf(26, 37).forEach { api ->
            try {
                preparePlaybackBuffer(platform(api), RecordingBufferControl(result = 0), 100)
                fail("api $api accepted a rejected buffer call")
            } catch (_: IllegalStateException) {
                // expected: check() refuses a non-positive platform result
            }
        }
    }

    @Test
    fun notifications_need_no_runtime_permission_below_api_33() {
        val platform = AndroidPlatform.from(apiLevel = 32, notificationPermissionGranted = false)

        assertTrue(platform.notificationsPermitted)
    }

    @Test
    fun notifications_follow_the_runtime_permission_from_api_33() {
        assertFalse(
            AndroidPlatform.from(apiLevel = 33, notificationPermissionGranted = false)
                .notificationsPermitted,
        )
        assertTrue(
            AndroidPlatform.from(apiLevel = 33, notificationPermissionGranted = true)
                .notificationsPermitted,
        )
    }

    @Test
    fun foreground_services_are_supported_on_every_installable_api_level() {
        assertTrue(AndroidPlatform.from(26, false).foregroundServiceSupported)
        assertTrue(AndroidPlatform.from(37, true).foregroundServiceSupported)
    }

    @Test
    fun the_platform_built_from_facts_carries_the_api_level() {
        assertEquals(34, AndroidPlatform.from(34, true).apiLevel)
    }

    private class RecordingBufferControl(private val result: Int = 1) : PlaybackBufferControl {
        val calls = mutableListOf<String>()

        override fun setStartThresholdInFrames(frames: Int): Int {
            calls += "threshold:$frames"
            return result
        }

        override fun setBufferSizeInFrames(frames: Int): Int {
            calls += "size:$frames"
            return result
        }
    }
}
