package com.achappell.hermesrelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * `SpeechRecognizer`-backed capture.
 *
 * `SpeechRecognizer` must be created and driven on the main thread, so every
 * entry point here assumes it. Partial results are surfaced for presentation
 * only; the submitted turn always uses the recognizer's final transcript.
 */
internal class PlatformSpeechInput(
    private val context: Context,
) : AndroidSpeechInput {
    private var recognizer: SpeechRecognizer? = null
    private var listener: ((AndroidSpeechEvent) -> Unit)? = null
    private var finished = false

    override fun authorization(): AndroidSpeechAuthorization = when {
        !SpeechRecognizer.isRecognitionAvailable(context) ->
            AndroidSpeechAuthorization.Unavailable

        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> AndroidSpeechAuthorization.Granted

        else -> AndroidSpeechAuthorization.NotDetermined
    }

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

        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
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
                listener?.invoke(AndroidSpeechEvent.Failed(failureFor(error)))
                release()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        runCatching { created.startListening(intent) }.onFailure {
            finished = true
            onEvent(AndroidSpeechEvent.Failed(AndroidSpeechFailure.RecognizerUnavailable))
            release()
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

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun transcript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun failureFor(error: Int): AndroidSpeechFailure = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            AndroidSpeechFailure.PermissionRequired

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> AndroidSpeechFailure.NoSpeechHeard

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> AndroidSpeechFailure.NetworkUnavailable

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> AndroidSpeechFailure.RecognizerBusy

        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER,
        -> AndroidSpeechFailure.RecognizerUnavailable

        else -> AndroidSpeechFailure.Unknown
    }
}
