package com.achappell.hermesrelay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The response audio format Home announces in `audio.frame` kind `start`. */
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

    /** Releases any platform worker owned by the sink. */
    fun close() {
        cancel()
    }
}

internal enum class AudioSinkFailureKind {
    StartFailure,
    WriteFailure,
    InvalidFrameAlignment,
    PlaybackStalled,
    Underrun,
    DrainTimeout,
    OutputUnavailable,
}

/** Content-free playback facts exposed to deterministic tests and live proof. */
internal data class AndroidAudioSinkTelemetry(
    val started: Boolean,
    val acceptedBytes: Int,
    val acceptedFrames: Int,
    val drained: Boolean,
    val failed: Boolean,
    val underrunCount: Int,
    val failureKind: AudioSinkFailureKind?,
)

/** Narrow platform seam; implementations must not retain PCM for inspection. */
internal interface AudioTrackDriver {
    fun state(): Int

    fun play()

    fun write(buffer: ByteArray, offset: Int, size: Int): Int

    fun playbackHeadFrames(): Long

    fun underrunCount(): Int

    fun stop()

    fun release()
}

internal fun interface AudioTrackDriverFactory {
    fun create(format: AndroidAudioFormat, bufferSize: Int): AudioTrackDriver
}

private class PlatformAudioTrackDriver(
    private val track: AudioTrack,
) : AudioTrackDriver {
    override fun state(): Int = track.state

    override fun play() = track.play()

    override fun write(buffer: ByteArray, offset: Int, size: Int): Int = track.write(
        buffer,
        offset,
        size,
        AudioTrack.WRITE_NON_BLOCKING,
    )

    override fun playbackHeadFrames(): Long = track.playbackHeadPosition.toLong()

    override fun underrunCount(): Int = track.underrunCount

    override fun stop() = track.stop()

    override fun release() = track.release()
}

private val platformAudioTrackDriverFactory = AudioTrackDriverFactory { format, bufferSize ->
    val channelMask = if (format.channels == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }
    val track = AudioTrack.Builder()
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
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    PlatformAudioTrackDriver(track)
}

/** `AudioTrack`-backed playback for streamed 16-bit PCM. */
internal class AudioTrackAudioSink(
    private val driverFactory: AudioTrackDriverFactory = platformAudioTrackDriverFactory,
    private val writeStallDeadlineMillis: Long = DEFAULT_WRITE_STALL_DEADLINE_MILLIS,
    private val drainStallDeadlineMillis: Long = DEFAULT_DRAIN_STALL_DEADLINE_MILLIS,
    private val drainTimeoutMillis: Long = DEFAULT_DRAIN_TIMEOUT_MILLIS,
    private val minBufferSizeProvider: (AndroidAudioFormat) -> Int = { format ->
        AudioTrack.getMinBufferSize(
            format.sampleRate,
            if (format.channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            },
            AudioFormat.ENCODING_PCM_16BIT,
        )
    },
) : AndroidAudioSink {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hermes-audio-out").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0)
    private val driver = AtomicReference<AudioTrackDriver?>(null)
    private val active = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val acceptedBytes = AtomicLong(0)
    private val acceptedFrames = AtomicLong(0)
    private val drained = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val underrunCount = AtomicInteger(0)
    private val underrunBaseline = AtomicInteger(0)
    private val failureKind = AtomicReference<AudioSinkFailureKind?>(null)
    @Volatile
    private var frameBytes = bytesPerFrame(1)

    internal fun snapshotTelemetry(): AndroidAudioSinkTelemetry = AndroidAudioSinkTelemetry(
        started = started.get(),
        acceptedBytes = acceptedBytes.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        acceptedFrames = acceptedFrames.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        drained = drained.get(),
        failed = failed.get(),
        underrunCount = underrunCount.get().coerceAtLeast(0),
        failureKind = failureKind.get(),
    )

    override fun start(format: AndroidAudioFormat): Boolean {
        cancel()
        started.set(false)
        acceptedBytes.set(0)
        acceptedFrames.set(0)
        drained.set(false)
        failed.set(false)
        underrunCount.set(0)
        underrunBaseline.set(0)
        failureKind.set(null)
        frameBytes = bytesPerFrame(format.channels)
        if (!format.isSupported) {
            fail(AudioSinkFailureKind.StartFailure)
            return false
        }

        return runCatching {
            val minBuffer = minBufferSizeProvider(format)
            if (minBuffer <= 0) {
                fail(AudioSinkFailureKind.OutputUnavailable)
                return@runCatching false
            }
            val created = driverFactory.create(format, minBuffer * BUFFER_FACTOR)
            if (created.state() != AudioTrack.STATE_INITIALIZED) {
                runCatching { created.release() }
                fail(AudioSinkFailureKind.StartFailure)
                return@runCatching false
            }
            val baselineUnderruns = created.underrunCount().coerceAtLeast(0)
            underrunBaseline.set(baselineUnderruns)
            underrunCount.set(0)
            driver.set(created)
            created.play()
            active.set(true)
            started.set(true)
            true
        }.getOrElse {
            fail(AudioSinkFailureKind.StartFailure)
            releaseDriver(stop = false)
            false
        }
    }

    override fun write(bytes: ByteArray) {
        if (!active.get() || failed.get()) {
            fail(AudioSinkFailureKind.WriteFailure)
            return
        }
        if (bytes.isEmpty() || bytes.size % frameBytes != 0) {
            fail(AudioSinkFailureKind.InvalidFrameAlignment)
            return
        }
        val expectedGeneration = generation.get()
        try {
            worker.execute {
                writeQueued(bytes, expectedGeneration)
            }
        } catch (_: RuntimeException) {
            fail(AudioSinkFailureKind.WriteFailure)
        }
    }

    override fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit) {
        val expectedGeneration = generation.get()
        if (!active.get() || driver.get() == null) {
            fail(AudioSinkFailureKind.StartFailure)
            onFailure(SAFE_FAILURE_MESSAGE)
            return
        }
        val callbackSent = AtomicBoolean(false)
        fun drainedOnce() {
            if (callbackSent.compareAndSet(false, true)) onDrained()
        }
        fun failedOnce() {
            if (callbackSent.compareAndSet(false, true)) onFailure(SAFE_FAILURE_MESSAGE)
        }
        try {
            worker.execute {
                val outcome = runCatching {
                    drainQueued(expectedGeneration)
                }
                if (generation.get() != expectedGeneration) return@execute
                if (outcome.isSuccess && !failed.get()) {
                    releaseDriver(stop = true)
                    drained.set(true)
                    active.set(false)
                    drainedOnce()
                } else {
                    if (failureKind.get() == null) fail(AudioSinkFailureKind.DrainTimeout)
                    releaseDriver(stop = false)
                    active.set(false)
                    failedOnce()
                }
            }
        } catch (_: RuntimeException) {
            fail(AudioSinkFailureKind.DrainTimeout)
            releaseDriver(stop = false)
            active.set(false)
            failedOnce()
        }
    }

    override fun cancel() {
        generation.incrementAndGet()
        active.set(false)
        releaseDriver(stop = true)
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
    }

    private fun writeQueued(bytes: ByteArray, expectedGeneration: Long) {
        if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
        val activeDriver = driver.get() ?: run {
            fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
            return
        }
        var offset = 0
        var lastProgress = System.nanoTime()
        while (offset < bytes.size) {
            if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
            if (!observeUnderruns(activeDriver, expectedGeneration)) return
            val written = runCatching {
                activeDriver.write(bytes, offset, bytes.size - offset)
            }.getOrElse {
                fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
                return
            }
            if (generation.get() != expectedGeneration || !active.get()) return
            when {
                written < 0 || written > bytes.size - offset -> {
                    fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
                    return
                }

                written == 0 -> {
                    if (elapsedMillis(lastProgress) >= writeStallDeadlineMillis) {
                        fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
                        return
                    }
                    sleepForPoll(expectedGeneration)
                }

                written % frameBytes != 0 -> {
                    fail(AudioSinkFailureKind.InvalidFrameAlignment, expectedGeneration)
                    return
                }

                else -> {
                    if (!observeUnderruns(activeDriver, expectedGeneration)) return
                    offset += written
                    acceptedBytes.addAndGet(written.toLong())
                    acceptedFrames.addAndGet((written / frameBytes).toLong())
                    lastProgress = System.nanoTime()
                }
            }
        }
    }

    private fun drainQueued(expectedGeneration: Long) {
        if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
        val activeDriver = driver.get() ?: run {
            fail(AudioSinkFailureKind.StartFailure, expectedGeneration)
            return
        }
        val targetFrames = acceptedFrames.get()
        if (targetFrames <= 0) {
            fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
            return
        }
        val startedAt = System.nanoTime()
        var lastPosition = runCatching { activeDriver.playbackHeadFrames() }
            .getOrElse {
                fail(AudioSinkFailureKind.PlaybackStalled, expectedGeneration)
                return
            }
        var lastProgress = startedAt
        while (true) {
            if (generation.get() != expectedGeneration || !active.get()) return
            if (failed.get()) return
            if (!observeUnderruns(activeDriver, expectedGeneration)) return
            val position = runCatching { activeDriver.playbackHeadFrames() }
                .getOrElse {
                    fail(AudioSinkFailureKind.PlaybackStalled, expectedGeneration)
                    return
                }
            if (position >= targetFrames) return
            if (position > lastPosition) {
                lastPosition = position
                lastProgress = System.nanoTime()
            }
            val now = System.nanoTime()
            if (elapsedMillis(lastProgress) >= drainStallDeadlineMillis) {
                fail(AudioSinkFailureKind.PlaybackStalled, expectedGeneration)
                return
            }
            if (elapsedMillis(startedAt) >= drainTimeoutMillis) {
                fail(AudioSinkFailureKind.DrainTimeout, expectedGeneration)
                return
            }
            sleepForPoll(expectedGeneration)
        }
    }

    private fun observeUnderruns(
        activeDriver: AudioTrackDriver,
        expectedGeneration: Long,
    ): Boolean {
        if (generation.get() != expectedGeneration || !active.get()) return false
        val current = runCatching { activeDriver.underrunCount() }
            .getOrElse {
                fail(AudioSinkFailureKind.Underrun, expectedGeneration)
                return false
            }
            .coerceAtLeast(0)
        val delta = (current - underrunBaseline.get()).coerceAtLeast(0)
        underrunCount.set(delta)
        if (current > underrunBaseline.get()) {
            fail(AudioSinkFailureKind.Underrun, expectedGeneration)
            return false
        }
        return true
    }

    private fun fail(kind: AudioSinkFailureKind, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && generation.get() != expectedGeneration) return
        failed.set(true)
        failureKind.compareAndSet(null, kind)
    }

    private fun releaseDriver(stop: Boolean) {
        val current = driver.getAndSet(null) ?: return
        if (stop) runCatching { current.stop() }
        runCatching { current.release() }
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    private fun sleepForPoll(expectedGeneration: Long? = null) {
        try {
            Thread.sleep(POLL_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(AudioSinkFailureKind.DrainTimeout, expectedGeneration)
        }
    }

    private companion object {
        const val BUFFER_FACTOR = 4
        const val POLL_MILLIS = 5L
        const val DEFAULT_WRITE_STALL_DEADLINE_MILLIS = 3_000L
        const val DEFAULT_DRAIN_STALL_DEADLINE_MILLIS = 500L
        const val DEFAULT_DRAIN_TIMEOUT_MILLIS = 30_000L
        const val SAFE_FAILURE_MESSAGE = "Response audio playback failed."
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
        cancelled = false
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
