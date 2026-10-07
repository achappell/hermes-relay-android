package com.achappell.hermesrelay

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for `ANDROID-VOICE-03`: with the real recognizer and the real
 * main-looper timers, a spoken phrase followed by silence is sent without
 * touching the screen, and the observed endpoint latency is recorded.
 *
 * Skipped unless `-e liveSpeech true`. Content-free: logs state names, timings
 * and a word count under tag `HermesVoiceLive`, never the transcript.
 */
@RunWith(AndroidJUnit4::class)
class LiveEndpointingTest {
    @get:Rule
    val grantRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private class RecordingPort : AndroidClientPort {
        private val profile = AndroidProfile("device-gate", "Gate")
        val requests = CopyOnWriteArrayList<AndroidTurnRequest>()

        override fun snapshot() = AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile,
            authorizationState = AndroidAuthorizationState.Verified,
        )

        override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult {
            requests += request
            return AndroidInitiationResult.Accepted(
                AndroidTurnBinding(request.profile.id, "session-1", "turn-1"),
            )
        }

        override fun observeTurn(
            binding: AndroidTurnBinding,
            onEvent: (AndroidNormalizedEvent) -> Unit,
        ): AndroidTurnObservation = AndroidTurnObservation { }

        override fun reconnect() = AndroidReconnectOutcome.Connected("session-1")
    }

    @Test
    fun a_phrase_followed_by_silence_is_sent_once_without_touching_the_screen() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e liveSpeech true to run", arguments.getString("liveSpeech") == "true")
        val expected = arguments.getString("liveSpeechPhrase").orEmpty().replace('_', ' ').lowercase()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speech = PlatformSpeechInput(context, AndroidPlatform.current(context))
        val port = RecordingPort()
        val startedAt = SystemClock.elapsedRealtime()
        var lastPartialAt = -1L
        var submittedAt = -1L
        var partials = 0
        var failed: AndroidSpeechFailure? = null
        val controller = AndroidCaptureController(
            speech = speech,
            timers = MainLooperVoiceTimers,
            initiation = AndroidInitiationController(port),
            isConnected = { true },
            isAuthorized = { true },
            onStateChange = { state ->
                val at = SystemClock.elapsedRealtime() - startedAt
                when (state) {
                    is AndroidCaptureState.Transcribing -> {
                        partials += 1
                        lastPartialAt = at
                    }
                    is AndroidCaptureState.Submitted -> {
                        submittedAt = at
                        Log.i(TAG, "submitted t=${at}ms")
                    }
                    is AndroidCaptureState.Failed -> {
                        failed = state.reason
                        Log.i(TAG, "failed t=${at}ms kind=${state.reason}")
                    }
                    else -> Log.i(TAG, "${state::class.simpleName} t=${at}ms")
                }
            },
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.beginCapture() }

        val deadline = startedAt + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && submittedAt < 0 && failed == null) {
            SystemClock.sleep(100)
        }
        SystemClock.sleep(1_500) // a second submit would show up here
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.cancelCapture() }

        Log.i(
            TAG,
            "partials=$partials lastPartial=${lastPartialAt}ms submitted=${submittedAt}ms " +
                "lastPartialToSubmit=${if (submittedAt >= 0) submittedAt - lastPartialAt else -1}ms " +
                "requests=${port.requests.size}",
        )
        assertTrue("no turn was sent by silence alone (failed=$failed)", submittedAt >= 0)
        assertEquals("the turn was sent more than once", 1, port.requests.size)
        if (expected.isNotEmpty()) {
            val sent = (port.requests.single().input as AndroidTurnInput.Typed).text.lowercase()
            Log.i(TAG, "matchesExpected=${sent.contains(expected)} words=${sent.trim().split(Regex("\\s+")).size}")
            assertTrue("the sent turn did not contain the whole spoken phrase", sent.contains(expected))
        }
    }

    private companion object {
        const val TAG = "HermesVoiceLive"
        const val TIMEOUT_MS = 30_000L
    }
}
