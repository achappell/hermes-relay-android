package com.achappell.hermesrelay

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
    /**
     * Admits a stream without claiming audible playback. [onPlaybackStarted] is
     * delivered once, after the first successful play, before the terminal callback.
     * Callbacks run off the UI thread and outside native/state locks.
     */
    fun start(format: AndroidAudioFormat, onPlaybackStarted: () -> Unit = {}): Boolean

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

    /** Admission ends prior live output, but preserves holds already set before this turn's audio. */
    fun prepareTurn() = cancel()

    /**
     * Holds the reply's output without discarding anything (`ANDROID-HOME-08`).
     * The sink's stall and drain deadlines do not run while held. A [cancel]
     * ends the turn's hold; [start] preserves pre-audio controls for that turn.
     */
    fun pause() {}

    /** Continues output held by [pause]. */
    fun resume() {}

    /** Sets the owned turn's gain, including before its first track exists. Cancel ends it. */
    fun setDucked(ducked: Boolean) {}

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

    fun preparePlayback(queuedFrames: Int) {}

    fun write(buffer: ByteArray, offset: Int, size: Int): Int

    fun playbackHeadFrames(): Long

    fun underrunCount(): Int

    fun stop()

    fun release()

    /** Holds the track; [play] continues it. */
    fun pause() {}

    fun setVolume(volume: Float): Int = AudioTrack.SUCCESS
}

internal fun interface AudioTrackDriverFactory {
    fun create(format: AndroidAudioFormat, bufferSize: Int): AudioTrackDriver
}

/** The two buffer-sizing calls `AudioTrack` offers, behind a seam the JVM can fake. */
internal interface PlaybackBufferControl {
    /** `AudioTrack.setStartThresholdInFrames`, available from API 31. */
    fun setStartThresholdInFrames(frames: Int): Int

    /** `AudioTrack.setBufferSizeInFrames`, the pre-API-31 way to start on a full queue. */
    fun setBufferSizeInFrames(frames: Int): Int
}

/**
 * Starts playback only once the queued audio fills the buffer.
 *
 * The API branch is decided by the injected [platform], never read from the
 * device here, so a test names the API level it asserts.
 */
internal fun preparePlaybackBuffer(
    platform: AndroidPlatform,
    control: PlaybackBufferControl,
    queuedFrames: Int,
) {
    val frames = queuedFrames.coerceAtLeast(1)
    if (platform.supportsPlaybackStartThreshold) {
        check(control.setStartThresholdInFrames(frames) > 0)
    } else {
        check(control.setBufferSizeInFrames(frames) > 0)
    }
}

private class PlatformAudioTrackDriver(
    private val track: AudioTrack,
    private val platform: AndroidPlatform,
    private val routes: PlatformAudioRouteEvents?,
) : AudioTrackDriver {
    init { routes?.attach(track) }
    private val bufferControl = object : PlaybackBufferControl {
        // The injected platform, built from SDK_INT by AndroidPlatform.current,
        // is the guard; preparePlaybackBuffer never calls this below API 31.
        @SuppressLint("NewApi")
        override fun setStartThresholdInFrames(frames: Int): Int =
            track.setStartThresholdInFrames(frames)

        override fun setBufferSizeInFrames(frames: Int): Int =
            track.setBufferSizeInFrames(frames)
    }

    override fun state(): Int = track.state

    override fun preparePlayback(queuedFrames: Int) =
        preparePlaybackBuffer(platform, bufferControl, queuedFrames)

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

    override fun release() {
        routes?.detach(track)
        track.release()
    }

    override fun pause() = track.pause()

    override fun setVolume(volume: Float): Int = track.setVolume(volume)
}

/** The real output factory; [platform] selects the API-specific buffer policy. */
internal fun platformAudioTrackDriverFactory(
    platform: AndroidPlatform,
    routes: PlatformAudioRouteEvents? = null,
) = AudioTrackDriverFactory { format, bufferSize ->
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
    PlatformAudioTrackDriver(track, platform, routes)
}

/** `AudioTrack`-backed playback for streamed 16-bit PCM. */
internal class AudioTrackAudioSink(
    /** No default: the output policy is chosen at the runtime root. */
    private val driverFactory: AudioTrackDriverFactory,
    private val clock: MonotonicClock = MonotonicClock.Real,
    private val sleeper: Sleeper = Sleeper.Real,
    private val writeStallDeadlineMillis: Long = DEFAULT_WRITE_STALL_DEADLINE_MILLIS,
    private val drainStallDeadlineMillis: Long = DEFAULT_DRAIN_STALL_DEADLINE_MILLIS,
    private val drainTimeoutMillis: Long = DEFAULT_DRAIN_TIMEOUT_MILLIS,
    controlExecutor: Executor? = null,
    private val startupLeadMillis: Long = 1_000,
    private val maximumHoldMillis: Long = 1_500,
    startupScheduler: ScheduledExecutorService? = null,
    /**
     * Live-gate evidence requires gapless playback, so it fails on the first
     * underrun. Ordinary playback only counts them: AudioTrack resumes once
     * more audio is written, and a brief gap must not end the reply's audio or
     * make a completed turn look failed.
     */
    private val failOnUnderrun: Boolean = false,
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
    private val controls = controlExecutor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hermes-audio-control").apply { isDaemon = true }
    }
    private val ownsControls = controlExecutor == null
    // Production shares the native control worker; direct-executor tests may inject a manual timer.
    private val startupTimer = startupScheduler ?: (controls as? ScheduledExecutorService)
        ?: Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "hermes-audio-startup").apply { isDaemon = true }
        }
    private val ownsStartupTimer = startupScheduler == null && startupTimer !== controls
    private var startupDeadline: ScheduledFuture<*>? = null
    // Explicit resume also releases PCM submitted while held but not yet accepted by the driver.
    private var startupReleased = false
    private var playbackStartedCallback: (() -> Unit)? = null
    // Only notification ordering is serialized here; never hold native/state locks in client code.
    private val notificationLock = Any()

    init {
        require(startupLeadMillis >= 0)
        require(maximumHoldMillis >= 0)
    }
    private val ducked = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val driver = AtomicReference<AudioTrackDriver?>(null)
    private val active = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val acceptedBytes = AtomicLong(0)
    private val acceptedFrames = AtomicLong(0)
    private val drained = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val volumeLock = Any()
    private var appliedVolume = 1f
    private var driverPaused = false
    private val deadlineLock = Any()
    // A monotonic elapsed clock excluding every hold, even between worker polls.
    // Keep the offset across streams so a cancelled worker cannot observe time going backwards.
    private var heldNanos = 0L
    private var heldSince = 0L
    private val underrunCount = AtomicInteger(0)
    private val underrunBaseline = AtomicInteger(0)
    private val failureKind = AtomicReference<AudioSinkFailureKind?>(null)
    @Volatile
    private var frameBytes = bytesPerFrame(1)
    @Volatile
    private var startupFrames = 0L
    @Volatile
    private var drainPaddingBytes = 0

    internal fun snapshotTelemetry(): AndroidAudioSinkTelemetry = AndroidAudioSinkTelemetry(
        started = started.get(),
        acceptedBytes = acceptedBytes.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        acceptedFrames = acceptedFrames.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        drained = drained.get(),
        failed = failed.get(),
        underrunCount = underrunCount.get().coerceAtLeast(0),
        failureKind = failureKind.get(),
    )

    override fun start(format: AndroidAudioFormat, onPlaybackStarted: () -> Unit): Boolean {
        var expectedGeneration = 0L
        val previous = synchronized(deadlineLock) {
            expectedGeneration = generation.incrementAndGet()
            active.set(false)
            cancelStartupDeadline()
            startupReleased = false
            playbackStartedCallback = onPlaybackStarted
            driver.getAndSet(null)
        }
        previous?.let { releaseTrack(it, stop = true) }
        started.set(false)
        acceptedBytes.set(0)
        acceptedFrames.set(0)
        drained.set(false)
        failed.set(false)
        underrunCount.set(0)
        underrunBaseline.set(0)
        failureKind.set(null)
        frameBytes = bytesPerFrame(format.channels)
        startupFrames = format.sampleRate.toLong() * startupLeadMillis / 1_000
        drainPaddingBytes = format.sampleRate / 10 * frameBytes
        if (!format.isSupported) {
            fail(AudioSinkFailureKind.StartFailure, expectedGeneration)
            return false
        }

        var unpublished: AudioTrackDriver? = null
        return runCatching {
            val minBuffer = minBufferSizeProvider(format)
            if (minBuffer <= 0) {
                fail(AudioSinkFailureKind.OutputUnavailable, expectedGeneration)
                return@runCatching false
            }
            val bufferBytes = maxOf(minBuffer * BUFFER_FACTOR, (startupFrames * frameBytes).toInt())
            val created = driverFactory.create(format, bufferBytes)
            unpublished = created
            check(created.state() == AudioTrack.STATE_INITIALIZED)
            val baselineUnderruns = created.underrunCount().coerceAtLeast(0)
            underrunBaseline.set(baselineUnderruns)
            underrunCount.set(0)
            synchronized(volumeLock) {
                val volume = if (ducked.get()) 0.2f else 1f
                check(created.setVolume(volume) == AudioTrack.SUCCESS)
                val published = synchronized(deadlineLock) {
                    if (generation.get() != expectedGeneration) false else {
                        appliedVolume = volume
                        driverPaused = false
                        driver.set(created)
                        active.set(true)
                        true
                    }
                }
                unpublished = null
                if (!published) {
                    releaseTrack(created, stop = false)
                    return@runCatching false
                }
            }
            true
        }.getOrElse {
            fail(AudioSinkFailureKind.StartFailure, expectedGeneration)
            unpublished?.let { releaseTrack(it, stop = false) }
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
            fail(AudioSinkFailureKind.StartFailure, expectedGeneration)
            if (completeStream(expectedGeneration, succeeded = false) != null) onFailure(SAFE_FAILURE_MESSAGE)
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
                synchronized(notificationLock) {
                    notifyPlaybackStarted(expectedGeneration)
                    val success = completeStream(expectedGeneration, outcome.isSuccess) ?: return@execute
                    if (success) drainedOnce() else failedOnce()
                }
            }
        } catch (_: RuntimeException) {
            if (completeStream(expectedGeneration, succeeded = false) != null) failedOnce()
        }
    }

    private fun completeStream(expected: Long, succeeded: Boolean): Boolean? {
        var success = false
        val current = synchronized(deadlineLock) {
            if (generation.get() != expected) return null
            success = succeeded && !failed.get()
            if (!success && failureKind.get() == null) fail(AudioSinkFailureKind.DrainTimeout, expected)
            active.set(false)
            cancelStartupDeadline()
            startupReleased = false
            playbackStartedCallback = null
            endHold()
            ducked.set(false)
            drained.set(success)
            driver.getAndSet(null)
        }
        current?.let { releaseTrack(it, stop = success) }
        return success
    }

    override fun cancel() = cancelCurrent(onlyIfActive = false)

    override fun prepareTurn() = cancelCurrent(onlyIfActive = true)

    private fun cancelCurrent(onlyIfActive: Boolean) {
        val current = synchronized(deadlineLock) {
            if (onlyIfActive && !active.get()) return
            generation.incrementAndGet()
            active.set(false)
            cancelStartupDeadline()
            startupReleased = false
            playbackStartedCallback = null
            endHold()
            ducked.set(false)
            driver.getAndSet(null)
        }
        current?.let { releaseTrack(it, stop = true) }
    }


    override fun setDucked(ducked: Boolean) {
        synchronized(deadlineLock) { this.ducked.set(ducked) }
        queueControl { current ->
            applyVolume(current)
        }
    }

    override fun pause() {
        synchronized(deadlineLock) {
            if (paused.get()) return
            heldSince = clock.nanoTime()
            paused.set(true)
        }
        queueControl { current ->
            if (started.get() && paused.get() && !driverPaused) {
                current.pause()
                driverPaused = true
            }
        }
    }

    override fun resume() {
        val expected = synchronized(deadlineLock) {
            if (!endHold()) return
            if (active.get()) startupReleased = true
            generation.get()
        }
        queueControl(expected) { current ->
            if (started.get() && !paused.get() && driverPaused) {
                current.play()
                driverPaused = false
            }
        }
        queueStartupPlayback(expected)
    }

    /** UI controls publish intent immediately; native work never waits on the UI thread. */
    private fun queueControl(expected: Long = generation.get(), action: (AudioTrackDriver) -> Unit) {
        if (!active.get()) return
        runCatching {
            controls.execute {
                synchronized(volumeLock) {
                    if (generation.get() != expected || !active.get() || failed.get()) return@execute
                    val current = driver.get() ?: return@execute
                    runCatching { action(current) }.onFailure {
                        fail(AudioSinkFailureKind.OutputUnavailable, expected)
                    }
                }
            }
        }.onFailure { fail(AudioSinkFailureKind.OutputUnavailable, expected) }
    }

    private fun endHold(): Boolean = synchronized(deadlineLock) {
        if (!paused.get()) return@synchronized false
        heldNanos += clock.nanoTime() - heldSince
        paused.set(false)
        true
    }

    private fun playbackTimeNanos(): Long = synchronized(deadlineLock) {
        (if (paused.get()) heldSince else clock.nanoTime()) - heldNanos
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
        if (ownsControls) (controls as ExecutorService).shutdown()
        if (ownsStartupTimer) startupTimer.shutdownNow()
    }

    private fun writeQueued(
        bytes: ByteArray,
        expectedGeneration: Long,
        responseAudio: Boolean = true,
    ) {
        if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
        val activeDriver = driver.get() ?: run {
            fail(AudioSinkFailureKind.WriteFailure, expectedGeneration)
            return
        }
        var offset = 0
        var lastProgress = playbackTimeNanos()
        while (offset < bytes.size) {
            if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
            if (paused.get()) {
                sleepForPoll(expectedGeneration)
                continue
            }
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
                    // A platform buffer may fill below the requested threshold.
                    if (!started.get() && acceptedFrames.get() > 0 &&
                        !startPlayback(activeDriver, expectedGeneration)) return
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
                    val releaseStartup = synchronized(deadlineLock) {
                        // Native underrun queries can return after cancellation/replacement.
                        // Counter publication and first-buffer timer admission belong to one generation.
                        if (generation.get() != expectedGeneration || !active.get() || failed.get()) return
                        val frames = if (responseAudio) {
                            acceptedBytes.addAndGet(written.toLong())
                            acceptedFrames.addAndGet((written / frameBytes).toLong())
                        } else {
                            acceptedFrames.get()
                        }
                        if (responseAudio && frames == written / frameBytes.toLong() &&
                            frames < startupFrames && !startupReleased
                        ) {
                            armStartupDeadline(expectedGeneration)
                        }
                        frames >= startupFrames || startupReleased
                    }
                    if (releaseStartup && !startPlayback(activeDriver, expectedGeneration)) return
                    lastProgress = playbackTimeNanos()
                }
            }
        }
    }

    private fun startPlayback(activeDriver: AudioTrackDriver, expectedGeneration: Long): Boolean {
        var beganPlayback = false
        val success = synchronized(volumeLock) {
            if (generation.get() != expectedGeneration || !active.get() || failed.get()) return false
            if (started.get() || paused.get()) return@synchronized true
            runCatching {
                activeDriver.preparePlayback(acceptedFrames.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                applyVolume(activeDriver)
                if (generation.get() != expectedGeneration || !active.get() || driver.get() !== activeDriver) return false
                if (paused.get()) return@synchronized true
                activeDriver.play()
                synchronized(deadlineLock) {
                    if (generation.get() != expectedGeneration || driver.get() !== activeDriver) return false
                    started.set(true)
                    beganPlayback = true
                    cancelStartupDeadline()
                }
                if (paused.get()) {
                    activeDriver.pause()
                    synchronized(deadlineLock) {
                        if (generation.get() == expectedGeneration && driver.get() === activeDriver) driverPaused = true
                    }
                }
                true
            }.getOrElse {
                fail(AudioSinkFailureKind.StartFailure, expectedGeneration)
                false
            }
        }
        if (success && beganPlayback) notifyPlaybackStarted(expectedGeneration)
        return success
    }

    private fun notifyPlaybackStarted(expected: Long) = synchronized(notificationLock) {
        val callback = synchronized(deadlineLock) {
            if (generation.get() != expected || !active.get() || failed.get() || !started.get()) null
            else playbackStartedCallback.also { playbackStartedCallback = null }
        }
        callback?.invoke()
    }

    /** Cap is monotonic elapsed time from first accepted PCM, including a pause. */
    private fun armStartupDeadline(expected: Long) = synchronized(deadlineLock) {
        if (generation.get() != expected || !active.get() || started.get() || failed.get()) return@synchronized
        startupDeadline = startupTimer.schedule(
            { queueStartupPlayback(expected) }, maximumHoldMillis, TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelStartupDeadline() {
        startupDeadline?.cancel(false)
        startupDeadline = null
    }

    private fun queueStartupPlayback(expected: Long) {
        runCatching {
            controls.execute {
                if (generation.get() != expected || !active.get() || acceptedFrames.get() == 0L) return@execute
                val current = driver.get() ?: return@execute
                startPlayback(current, expected)
            }
        }.onFailure { fail(AudioSinkFailureKind.StartFailure, expected) }
    }

    private fun applyVolume(current: AudioTrackDriver) {
        val volume = if (ducked.get()) 0.2f else 1f
        if (volume == appliedVolume) return
        check(current.setVolume(volume) == AudioTrack.SUCCESS)
        appliedVolume = volume
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
        // Keep a short silent tail behind the final response frame so the
        // polling watcher can stop playback before an empty stream underruns.
        // This is not response PCM: exclude it from accepted counts and target.
        writeQueued(ByteArray(drainPaddingBytes), expectedGeneration, responseAudio = false)
        if (failed.get()) return
        // Short replies can end before the startup threshold is filled.
        if (!startPlayback(activeDriver, expectedGeneration)) return
        val startedAt = playbackTimeNanos()
        var lastPosition = runCatching { activeDriver.playbackHeadFrames() }
            .getOrElse {
                fail(AudioSinkFailureKind.PlaybackStalled, expectedGeneration)
                return
            }
        var lastProgress = startedAt
        while (true) {
            if (generation.get() != expectedGeneration || !active.get()) return
            if (failed.get()) return
            if (paused.get()) {
                // Freeze deadlines at the actual transition, not the last observed poll.
                sleepForPoll(expectedGeneration)
                continue
            }
            if (!started.get() && !startPlayback(activeDriver, expectedGeneration)) return
            if (!observeUnderruns(activeDriver, expectedGeneration)) return
            val position = runCatching { activeDriver.playbackHeadFrames() }
                .getOrElse {
                    fail(AudioSinkFailureKind.PlaybackStalled, expectedGeneration)
                    return
                }
            if (position >= targetFrames) return
            if (position > lastPosition) {
                lastPosition = position
                lastProgress = playbackTimeNanos()
            }
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
        if (failOnUnderrun && current > underrunBaseline.get()) {
            fail(AudioSinkFailureKind.Underrun, expectedGeneration)
            return false
        }
        return true
    }

    private fun fail(kind: AudioSinkFailureKind, expectedGeneration: Long? = null) {
        synchronized(deadlineLock) {
            if (expectedGeneration != null && generation.get() != expectedGeneration) return
            failed.set(true)
            cancelStartupDeadline()
            playbackStartedCallback = null
            failureKind.compareAndSet(null, kind)
        }
    }

    private fun releaseTrack(current: AudioTrackDriver, stop: Boolean) {
        controls.execute {
            synchronized(volumeLock) {
                if (stop) runCatching { current.stop() }
                runCatching { current.release() }
            }
        }
    }

    private fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(playbackTimeNanos() - startNanos)

    private fun sleepForPoll(expectedGeneration: Long? = null) {
        try {
            sleeper.sleep(POLL_MILLIS)
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
    private var playbackStartedCallback: (() -> Unit)? = null

    override fun start(format: AndroidAudioFormat, onPlaybackStarted: () -> Unit): Boolean {
        if (!acceptFormat || !format.isSupported) return false
        startedFormat = format
        cancelled = false
        playbackStartedCallback = onPlaybackStarted
        return true
    }

    override fun write(bytes: ByteArray) {
        bytesWritten += bytes.size
        chunks += 1
        playbackStartedCallback?.also { playbackStartedCallback = null }?.invoke()
    }

    override fun finish(onDrained: () -> Unit, onFailure: (String) -> Unit) {
        playbackStartedCallback = null
        if (startedFormat == null) {
            onFailure("Response audio playback was never started.")
        } else {
            onDrained()
        }
    }

    override fun cancel() {
        cancelled = true
        playbackStartedCallback = null
    }

    var pauseCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set

    override fun pause() {
        pauseCalls += 1
    }

    override fun resume() {
        resumeCalls += 1
    }
}
