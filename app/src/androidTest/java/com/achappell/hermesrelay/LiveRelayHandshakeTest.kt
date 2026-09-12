package com.achappell.hermesrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live gate for A-4: a real handshake against the household Hermes relay.
 *
 * Excluded from the default run by the [LiveRelay] annotation, so ordinary CI
 * and offline runs are unaffected. To run it:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.achappell.hermesrelay.LiveRelayHandshakeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.relayEndpoint=wss://host/voice-session \
 *   -Pandroid.testInstrumentationRunnerArguments.relayToken="$TOKEN"
 * ```
 *
 * Overriding `notAnnotation` is required: an empty value does not clear the
 * default, and the run silently executes zero tests instead.
 *
 * The token is never written to source, to a profile file, or to a log line.
 */
@RunWith(AndroidJUnit4::class)
@LiveRelay
class LiveRelayHandshakeTest {

    @Test
    fun the_relay_accepts_this_client_identity_and_returns_a_session() {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString("relayEndpoint")
        val token = arguments.getString("relayToken")
        require(!endpoint.isNullOrBlank() && !token.isNullOrBlank()) {
            "relayEndpoint and relayToken instrumentation arguments are required"
        }

        val clientId = arguments.getString("relayClientId") ?: "amanda-laptop"
        val deviceId = arguments.getString("relayDeviceId") ?: "android"

        val profile = RelayProfile(
            id = "live-gate",
            endpoint = endpoint!!,
            clientId = clientId,
            deviceId = deviceId,
            displayName = "Android live gate",
        )
        val client = OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(mapOf(profile.id to token!!)),
            helloTimeoutMillis = 15_000,
        )

        val outcome = client.reconnect()
        client.disconnect()

        assertTrue(
            "expected Connected, got $outcome",
            outcome is AndroidReconnectOutcome.Connected,
        )
        assertTrue(
            (outcome as AndroidReconnectOutcome.Connected).sessionId.isNotBlank(),
        )
    }

    @Test
    fun a_typed_turn_reaches_hermes_and_returns_a_projected_response() {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString("relayEndpoint")
        val token = arguments.getString("relayToken")
        require(!endpoint.isNullOrBlank() && !token.isNullOrBlank()) {
            "relayEndpoint and relayToken instrumentation arguments are required"
        }

        val profile = RelayProfile(
            id = "live-gate",
            endpoint = endpoint!!,
            clientId = arguments.getString("relayClientId") ?: "amanda-laptop",
            deviceId = arguments.getString("relayDeviceId") ?: "android",
            displayName = "Android live gate",
        )
        val sink = AudioTrackAudioSink()
        val client = OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(mapOf(profile.id to token!!)),
            helloTimeoutMillis = 15_000,
            audioSink = sink,
        )

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(profile.clientId, profile.displayName),
                AndroidTurnInput.Typed(
                    arguments.getString("relayPrompt")
                        ?: "Reply with exactly the word: acknowledged",
                ),
            ),
        )
        assertTrue("turn was not accepted: $result", result is AndroidInitiationResult.Accepted)
        val binding = (result as AndroidInitiationResult.Accepted).binding

        var state = AndroidTurnState.awaitingEvents(binding)
        val finished = java.util.concurrent.CountDownLatch(1)
        val observation = client.observeTurn(binding) { event ->
            state = AndroidTurnStateReducer.reduce(state, event)
            if (state.isTerminal) finished.countDown()
        }

        val completed = finished.await(120, java.util.concurrent.TimeUnit.SECONDS)
        observation.cancel()
        client.disconnect()

        assertTrue("the turn never reached a terminal state", completed)
        assertTrue(
            "Hermes returned no response text (phase=" + state.phase + ")",
            state.responseText.isNotBlank(),
        )
        // The relay advertises pcm_s16le and streams it, so a completed turn
        // must mean the audio actually played through to the end.
        assertEquals(AndroidTurnPhase.Complete, state.phase)
        assertEquals(AndroidAudioDelivery.Delivered, state.audio)
    }
}
