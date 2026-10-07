package com.achappell.hermesrelay

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for `ANDROID-VOICE-01`: the real recognizer hears a real utterance.
 *
 * Skipped unless `-e liveSpeech true` is passed, because it needs sound in front
 * of the microphone (a person, or a host speaking a neutral phrase near the
 * device) and a recognizer with a speech pack. Content-free by construction: the
 * transcript is never logged. Only the failure kind, timings, word count and
 * whether it contains `liveSpeechPhrase` are written, under tag `HermesVoiceLive`.
 *
 * `-e liveSpeechNetwork true` allows network recognition for the run.
 */
@RunWith(AndroidJUnit4::class)
class LiveSpeechRecognizerTest {
    @get:Rule
    val grantRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun the_recognizer_hears_one_spoken_phrase_and_reports_a_final_transcript() {
        assumeTrue("pass -e liveSpeech true to run", arguments.getString("liveSpeech") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = PlatformSpeechInput(context, AndroidPlatform.current(context))
        input.networkRecognitionAllowed = arguments.getString("liveSpeechNetwork") == "true"
        // `adb shell` splits on spaces, so the phrase is passed with underscores.
        val expected = arguments.getString("liveSpeechPhrase").orEmpty().replace('_', ' ').lowercase()
        val events = LinkedBlockingQueue<AndroidSpeechEvent>()
        val startedAt = SystemClock.elapsedRealtime()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            input.start { events.add(it) }
        }
        Log.i(TAG, "api=${android.os.Build.VERSION.SDK_INT} network=${input.networkRecognitionAllowed}")

        var outcome: AndroidSpeechEvent? = null
        val deadline = startedAt + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val event = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val at = SystemClock.elapsedRealtime() - startedAt
            when (event) {
                AndroidSpeechEvent.Started -> Log.i(TAG, "ready t=${at}ms")
                is AndroidSpeechEvent.Partial -> Log.i(TAG, "partial t=${at}ms")
                is AndroidSpeechEvent.Final -> {
                    val words = event.text.trim().split(Regex("\\s+")).size
                    val matches = expected.isNotEmpty() && event.text.lowercase().contains(expected)
                    Log.i(TAG, "final t=${at}ms words=$words matchesExpected=$matches")
                    outcome = event
                }
                is AndroidSpeechEvent.Failed -> {
                    Log.i(TAG, "failed t=${at}ms kind=${event.reason}")
                    outcome = event
                }
                AndroidSpeechEvent.Cancelled -> Log.i(TAG, "cancelled t=${at}ms")
            }
            if (outcome != null) break
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { input.cancel() }

        assertTrue("no final transcript within ${TIMEOUT_MS}ms: $outcome", outcome is AndroidSpeechEvent.Final)
        if (expected.isNotEmpty()) {
            assertTrue(
                "the final transcript did not contain the spoken phrase",
                (outcome as AndroidSpeechEvent.Final).text.lowercase().contains(expected),
            )
        }
    }

    private companion object {
        const val TAG = "HermesVoiceLive"
        const val TIMEOUT_MS = 25_000L
    }
}
