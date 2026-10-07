package com.achappell.hermesrelay

import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Every decision about *which* recognizer listens, *whether* it may use the
 * network and *how* its errors read to the user. Pure functions of plain
 * values so each branch is a JVM test; `PlatformSpeechInput` only applies them.
 *
 * Privacy contract: audio stays on the device unless the user has chosen
 * network recognition ([RecognitionRequest.networkAllowed]). A missing speech
 * pack is therefore reported, never papered over by a silent online fallback.
 */
internal enum class RecognizerKind {
    /**
     * `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+): a recognizer
     * that is on-device by construction, not by request.
     */
    OnDevice,

    /**
     * `SpeechRecognizer.createSpeechRecognizer`: the system default, which may
     * be network-backed. Used when the user allowed the network, or when the
     * device has no on-device recognizer and `EXTRA_PREFER_OFFLINE` is the best
     * available request.
     */
    SystemDefault,
}

internal fun chooseRecognizer(
    platform: AndroidPlatform,
    onDeviceRecognitionAvailable: Boolean,
    networkAllowed: Boolean,
): RecognizerKind = when {
    networkAllowed -> RecognizerKind.SystemDefault
    platform.apiLevel >= Build.VERSION_CODES.S && onDeviceRecognitionAvailable ->
        RecognizerKind.OnDevice

    else -> RecognizerKind.SystemDefault
}

/**
 * What `SpeechRecognizer.checkRecognitionSupport` (API 33) reported, as BCP-47
 * language tags.
 */
internal data class RecognitionSupportSnapshot(
    val installedOnDevice: Set<String>,
    val onlineSupported: Set<String>,
)

internal data class RecognitionRequest(
    /** BCP-47 tag of the language the recognizer is asked to hear. */
    val languageTag: String,
    val networkAllowed: Boolean,
    /** Null when the pre-flight is unavailable (below API 33) or failed. */
    val support: RecognitionSupportSnapshot?,
)

internal sealed interface RecognitionPlan {
    /** Start listening; [preferOffline] becomes `EXTRA_PREFER_OFFLINE`. */
    data class Listen(val preferOffline: Boolean) : RecognitionPlan

    /** Do not start: the on-device pack is missing and the network is not allowed. */
    data object LanguagePackMissing : RecognitionPlan
}

internal fun planRecognition(request: RecognitionRequest): RecognitionPlan {
    val support = request.support
        ?: return RecognitionPlan.Listen(preferOffline = !request.networkAllowed)

    if (support.installedOnDevice.any { sameLanguage(it, request.languageTag) }) {
        return RecognitionPlan.Listen(preferOffline = true)
    }
    return if (request.networkAllowed) {
        RecognitionPlan.Listen(preferOffline = false)
    } else {
        RecognitionPlan.LanguagePackMissing
    }
}

/** Compares the language and, when both name one, the region: `en-US` matches `en-US`, not `en-GB`. */
internal fun sameLanguage(a: String, b: String): Boolean {
    val left = a.replace('_', '-').split('-')
    val right = b.replace('_', '-').split('-')
    if (!left[0].equals(right[0], ignoreCase = true)) return false
    val leftRegion = left.getOrNull(1)
    val rightRegion = right.getOrNull(1)
    return leftRegion == null || rightRegion == null || leftRegion.equals(rightRegion, ignoreCase = true)
}

/**
 * Maps a `SpeechRecognizer.ERROR_*` code. Every defined constant is named
 * explicitly; only a code Android has not defined falls to [AndroidSpeechFailure.Unknown].
 */
internal fun speechFailureFor(error: Int): AndroidSpeechFailure = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AndroidSpeechFailure.PermissionRequired

    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> AndroidSpeechFailure.NoSpeechHeard

    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    -> AndroidSpeechFailure.NetworkUnavailable

    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
    -> AndroidSpeechFailure.RecognizerBusy

    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
    -> AndroidSpeechFailure.LanguageUnavailable

    SpeechRecognizer.ERROR_AUDIO -> AndroidSpeechFailure.AudioUnavailable

    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
    -> AndroidSpeechFailure.RecognizerUnavailable

    else -> AndroidSpeechFailure.Unknown
}
