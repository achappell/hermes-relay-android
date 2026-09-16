package com.achappell.hermesrelay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-speaker precondition for the opt-in live Home gate. */
@RunWith(AndroidJUnit4::class)
@LiveRelay
class AudioOutputPreflightTest {

    @Test
    fun verifyPhysicalAudioOutput() {
        val passed = runCatching { probeAudioOutput() }.getOrDefault(false)
        // The Android result printer does not forward test stdout through
        // `am instrument`. An explicit status bundle is the only
        // application-owned token consumed by the host wrapper; it carries no
        // device, route, or audio content.
        val status = Bundle().apply {
            putString(
                "AUDIO_PREFLIGHT",
                if (passed) "PASS" else "FAIL:AUDIO_OUTPUT",
            )
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status)
    }

    private fun probeAudioOutput(): Boolean {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE, channelMask, encoding)
        if (minimum <= 0) return false
        val bufferSize = (minimum + 1) and 1.inv()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(PROBE_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return false

        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) return false
            track.play()
            val silentBuffer = ByteArray(bufferSize)
            val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MILLIS
            var offset = 0
            while (offset < silentBuffer.size && SystemClock.elapsedRealtime() < deadline) {
                val written = track.write(
                    silentBuffer,
                    offset,
                    silentBuffer.size - offset,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
                if (written < 0 || written % 2 != 0) return false
                if (written == 0) {
                    SystemClock.sleep(PROBE_POLL_MILLIS)
                } else {
                    offset += written
                }
            }
            if (offset != silentBuffer.size) return false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (track.playbackHeadPosition > 0) return true
                SystemClock.sleep(PROBE_POLL_MILLIS)
            }
            false
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private companion object {
        const val PROBE_SAMPLE_RATE = 16_000
        const val PROBE_TIMEOUT_MILLIS = 2_000L
        const val PROBE_POLL_MILLIS = 10L
    }
}
