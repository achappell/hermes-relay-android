package com.achappell.hermesrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * `SpeechRecognizer`-backed capture.
 *
 * `SpeechRecognizer` must be created and driven on the main thread, so every
 * entry point here assumes it. Partial results are surfaced for presentation
 * only; the submitted turn always uses the recognizer's final transcript.
 *
 * Which recognizer listens and in which language (`ANDROID-VOICE-01`):
 * - Recognizer: [chooseRecognizer]. By default `createOnDeviceSpeechRecognizer`
 *   (API 31+, on-device by construction); `createSpeechRecognizer` plus
 *   `EXTRA_PREFER_OFFLINE` only where no on-device recognizer exists, which is
 *   a request rather than a guarantee; and `createSpeechRecognizer` with the
 *   network allowed only after the user chose it ([networkRecognitionAllowed]).
 * - Language: the device locale (`Locale.getDefault()`), set explicitly through
 *   `EXTRA_LANGUAGE`. iOS hard-codes `en-US`; Android follows the device.
 * - Pre-flight: on API 33+ `checkRecognitionSupport` runs before the
 *   microphone opens, so a missing pack is reported as [AndroidSpeechFailure.LanguageUnavailable]
 *   immediately instead of after the recognizer fails (error 12/13).
 */
internal class PlatformSpeechInput(
    private val context: Context,
    private val platform: AndroidPlatform,
    private val locale: () -> Locale = { Locale.getDefault() },
) : AndroidSpeechInput {
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((AndroidSpeechEvent) -> Unit)? = null
    private var finished = false

    override var networkRecognitionAllowed: Boolean by mutableStateOf(false)

    override fun authorization(): AndroidSpeechAuthorization = when {
        !SpeechRecognizer.isRecognitionAvailable(context) ->
            AndroidSpeechAuthorization.Unavailable

        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> AndroidSpeechAuthorization.Granted

        else -> AndroidSpeechAuthorization.NotDetermined
    }

    // Guarded by the injected [platform] rather than Build.VERSION directly, so
    // both branches are testable; lint cannot see through that value.
    @SuppressLint("NewApi")
    override fun start(onEvent: (AndroidSpeechEvent) -> Unit) {
        when (authorization()) {
            AndroidSpeechAuthorization.Unavailable -> {
                onEvent(AndroidSpeechEvent.Failed(AndroidSpeechFailure.RecognizerUnavailable))
                return
            }

            AndroidSpeechAuthorization.Granted -> Unit

            else -> {
                onEvent(AndroidSpeechEvent.Failed(AndroidSpeechFailure.PermissionRequired))
                return
            }
        }

        cancel()
        listener = onEvent
        finished = false

        val networkAllowed = networkRecognitionAllowed
        val created = runCatching { createRecognizer(networkAllowed) }.getOrNull()
        if (created == null) {
            onEvent(AndroidSpeechEvent.Failed(AndroidSpeechFailure.RecognizerUnavailable))
            return
        }
        recognizer = created

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener?.invoke(AndroidSpeechEvent.Started)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                transcript(partialResults)?.let { text ->
                    listener?.invoke(AndroidSpeechEvent.Partial(text))
                }
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = transcript(results)
                if (text.isNullOrBlank()) {
                    listener?.invoke(AndroidSpeechEvent.Failed(AndroidSpeechFailure.NoSpeechHeard))
                } else {
                    listener?.invoke(AndroidSpeechEvent.Final(text))
                }
                release()
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                listener?.invoke(AndroidSpeechEvent.Failed(speechFailureFor(error)))
                release()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val languageTag = locale().toLanguageTag()
        if (platform.apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            preflight(created, languageTag, networkAllowed)
        } else {
            begin(
                created,
                languageTag,
                planRecognition(RecognitionRequest(languageTag, networkAllowed, support = null)),
            )
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        val notify = !finished && listener != null
        finished = true
        runCatching { recognizer?.cancel() }
        release()
        if (notify) {
            listener?.invoke(AndroidSpeechEvent.Cancelled)
        }
        listener = null
    }

    // Guarded by the injected [platform] rather than Build.VERSION directly, so
    // both branches are testable; lint cannot see through that value.
    @SuppressLint("NewApi")
    private fun createRecognizer(networkAllowed: Boolean): SpeechRecognizer? =
        when (
            chooseRecognizer(
                platform = platform,
                onDeviceRecognitionAvailable = onDeviceRecognitionAvailable(),
                networkAllowed = networkAllowed,
            )
        ) {
            RecognizerKind.OnDevice -> createOnDevice()
            RecognizerKind.SystemDefault -> SpeechRecognizer.createSpeechRecognizer(context)
        }

    // Guarded by the injected [platform] rather than Build.VERSION directly, so
    // both branches are testable; lint cannot see through that value.
    @SuppressLint("NewApi")
    private fun onDeviceRecognitionAvailable(): Boolean =
        platform.apiLevel >= Build.VERSION_CODES.S && onDeviceAvailableApi31()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun onDeviceAvailableApi31(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createOnDevice(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    /** Asks the recognizer what it can hear before the microphone opens. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun preflight(created: SpeechRecognizer, languageTag: String, networkAllowed: Boolean) {
        val probe = recognitionIntent(languageTag, preferOffline = !networkAllowed)
        val applies = { recognizer === created && !finished }
        val check = runCatching {
            created.checkRecognitionSupport(
                probe,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        if (!applies()) return
                        begin(
                            created,
                            languageTag,
                            planRecognition(
                                RecognitionRequest(
                                    languageTag = languageTag,
                                    networkAllowed = networkAllowed,
                                    support = RecognitionSupportSnapshot(
                                        installedOnDevice = recognitionSupport
                                            .installedOnDeviceLanguages.toSet(),
                                        onlineSupported = recognitionSupport.onlineLanguages.toSet(),
                                    ),
                                ),
                            ),
                        )
                    }

                    override fun onError(error: Int) {
                        if (!applies()) return
                        // The check itself failed; the recognizer's own error
                        // (12/13) remains the signal for a missing pack.
                        begin(
                            created,
                            languageTag,
                            planRecognition(
                                RecognitionRequest(languageTag, networkAllowed, support = null),
                            ),
                        )
                    }
                },
            )
        }
        if (check.isFailure) {
            begin(
                created,
                languageTag,
                planRecognition(RecognitionRequest(languageTag, networkAllowed, support = null)),
            )
        }
    }

    private fun begin(created: SpeechRecognizer, languageTag: String, plan: RecognitionPlan) {
        when (plan) {
            RecognitionPlan.LanguagePackMissing -> {
                finished = true
                listener?.invoke(AndroidSpeechEvent.Failed(AndroidSpeechFailure.LanguageUnavailable))
                release()
            }

            is RecognitionPlan.Listen -> {
                val intent = recognitionIntent(languageTag, plan.preferOffline)
                runCatching { created.startListening(intent) }.onFailure {
                    finished = true
                    listener?.invoke(
                        AndroidSpeechEvent.Failed(AndroidSpeechFailure.RecognizerUnavailable),
                    )
                    release()
                }
            }
        }
    }

    private fun recognitionIntent(languageTag: String, preferOffline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun transcript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
