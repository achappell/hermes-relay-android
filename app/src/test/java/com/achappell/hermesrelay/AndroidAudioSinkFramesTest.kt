package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frame accounting for the drain guard.
 *
 * `AudioTrack.playbackHeadPosition` counts frames. The guard waits for it to
 * reach the frames written, so an inflated target strands the turn in Speaking
 * until the guard times out rather than when the audio actually ends.
 */
class AndroidAudioSinkFramesTest {

    @Test
    fun a_mono_frame_is_one_sample() {
        assertEquals(2, bytesPerFrame(1))
    }

    @Test
    fun a_stereo_frame_is_two_samples() {
        assertEquals(4, bytesPerFrame(2))
    }

    @Test
    fun stereo_audio_reports_the_frame_count_the_playhead_will_reach() {
        // One second of 48 kHz stereo: 48000 frames, four bytes each.
        val bytes = 48_000 * 4

        val frames = bytes / bytesPerFrame(2)

        // Dividing by a fixed two would claim 96,000 frames — twice what the
        // playhead can ever report, so the drain guard would never be
        // satisfied.
        assertEquals(48_000, frames)
    }

    @Test
    fun mono_audio_is_unchanged_by_the_channel_aware_arithmetic() {
        val bytes = 48_000 * 2

        assertEquals(48_000, bytes / bytesPerFrame(1))
    }
}
