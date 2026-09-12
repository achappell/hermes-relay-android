package com.achappell.hermesrelay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The response audio format the relay announces in `audio_start`. */
internal data class AndroidAudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val sampleWidth: Int,
    val encoding: String,
) {
    val isSupported: Boolean
        get() = encoding.equals("pcm_s16le", ignoreCase = true) &&
            sampleWidth == 2 &&
            channels in 1..2 &&
            sampleRate in 8_000..192_000
}

/**
 * Plays streamed response audio.
 *
 * The sink exists so the Client can stop claiming it is speaking when it is
 * not. `Speaking` is reported only once playback actually starts, and the turn
 * reaches `Complete` only after [finish] reports the buffer has drained.
 *
 * Audio bytes pass through here and are never retained: no chunk reaches UI
 * state, Local History, or a log.
 */
/**
 * Bytes in one 16-bit PCM frame.
 *
 * `AudioTrack.playbackHeadPosition` counts frames, not samples, so the drain
 * guard compares against a frame count. Assuming two bytes per frame
 * double-counted every stereo stream: the target could never be reached and
 * the guard ran to its full timeout while the turn sat in Speaking.
 */
internal fun bytesPerFrame(channels: Int): Int = 2 * channels

internal interface AndroidAudioSink {
    /** Begins playback. Returns false when the format cannot be played. */
    fun start(format: AndroidAudioFormat): Boolean

    fun write(bytes: ByteArray)

    /**
     * Signals that no more audio is coming.
     *
     * [onDrained] runs once the queued audio has actually finished playing;
     * [onFailure] runs if playback could not complete.
     */
    fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit)

    /** Abandons playback immediately, discarding anything still queued. */
    fun cancel()
}

/** `AudioTrack`-backed playback for streamed 16-bit PCM. */
internal class AudioTrackAudioSink : AndroidAudioSink {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hermes-audio-out").apply { isDaemon = true }
    }
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var sampleRate = 0
    private var bytesPerFrame = bytesPerFrame(1)
    private val failed = AtomicBoolean(false)

    override fun start(format: AndroidAudioFormat): Boolean {
        if (!format.isSupported) return false

        return runCatching {
            cancel()
            val channelMask = if (format.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                format.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false

            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * BUFFER_FACTOR)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }

            created.play()
            track = created
            sampleRate = format.sampleRate
            bytesPerFrame = bytesPerFrame(format.channels)
            framesWritten = 0
            failed.set(false)
            true
        }.getOrElse { false }
    }

    override fun write(bytes: ByteArray) {
        val active = track ?: return
        worker.execute {
            runCatching {
                var offset = 0
                var stalled = 0
                // A blocking write would wait forever on a device that stops
                // consuming, stranding the turn in Speaking because finish()
                // is queued behind it. Bound the wait and fail instead.
                while (offset < bytes.size) {
                    val written = active.write(
                        bytes,
                        offset,
                        bytes.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                    when {
                        written < 0 -> {
                            failed.set(true)
                            return@runCatching
                        }

                        written == 0 -> {
                            stalled += 1
                            if (stalled > WRITE_STALL_ITERATIONS) {
                                failed.set(true)
                                return@runCatching
                            }
                            Thread.sleep(WRITE_STALL_POLL_MILLIS)
                        }

                        else -> {
                            stalled = 0
                            offset += written
                            framesWritten += written / bytesPerFrame
                        }
                    }
                }
            }.onFailure { failed.set(true) }
        }
    }

    override fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit) {
        val active = track
        if (active == null) {
            onFailure("Response audio playback was never started.")
            return
        }

        worker.execute {
            val outcome = runCatching {
                // Let the device finish what is already queued, then wait for
                // the playhead to reach it. Reporting Complete before this
                // would claim a response finished speaking while it still is.
                active.stop()
                var guard = 0
                while (
                    active.playbackHeadPosition < framesWritten &&
                    guard < DRAIN_GUARD_ITERATIONS &&
                    !failed.get()
                ) {
                    Thread.sleep(DRAIN_POLL_MILLIS)
                    guard += 1
                }
                active.playbackHeadPosition
            }
            releaseTrack()

            when {
                outcome.isFailure || failed.get() ->
                    onFailure("Response audio playback failed.")
                else -> onDrained()
            }
        }
    }

    override fun cancel() {
        runCatching {
            track?.let { active ->
                active.pause()
                active.flush()
            }
        }
        releaseTrack()
    }

    private fun releaseTrack() {
        runCatching { track?.release() }
        track = null
        framesWritten = 0
    }

    private companion object {
        const val BUFFER_FACTOR = 4
        const val BYTES_PER_SAMPLE = 2
        const val DRAIN_POLL_MILLIS = 20L
        const val DRAIN_GUARD_ITERATIONS = 1_500
        const val WRITE_STALL_POLL_MILLIS = 5L
        const val WRITE_STALL_ITERATIONS = 600
    }
}

/**
 * Records what it was asked to play without producing sound.
 *
 * Used by deterministic tests and by any build with no output device; it never
 * claims success it did not have.
 */
internal class RecordingAudioSink(
    private val acceptFormat: Boolean = true,
) : AndroidAudioSink {
    var startedFormat: AndroidAudioFormat? = null
        private set
    var bytesWritten: Int = 0
        private set
    var chunks: Int = 0
        private set
    var cancelled: Boolean = false
        private set

    override fun start(format: AndroidAudioFormat): Boolean {
        if (!acceptFormat || !format.isSupported) return false
        startedFormat = format
        return true
    }

    override fun write(bytes: ByteArray) {
        bytesWritten += bytes.size
        chunks += 1
    }

    override fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit) {
        if (startedFormat == null) {
            onFailure("Response audio playback was never started.")
        } else {
            onDrained()
        }
    }

    override fun cancel() {
        cancelled = true
    }
}
