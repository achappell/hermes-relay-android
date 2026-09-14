package com.achappell.hermesrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional live gate for Story 5: a real handshake against an approved Home
 * bridge deployment.
 *
 * The public Home adapter is not live yet, so this remains an environment
 * gate for the later deployment boundary rather than evidence for this story.
 * It is excluded from the default run by the [LiveRelay] annotation, so
 * ordinary CI and offline runs are unaffected. To run it after that adapter
 * is deployed:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.achappell.hermesrelay.LiveRelayHandshakeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.homeRoute=wss://home-host \
 *   -Pandroid.testInstrumentationRunnerArguments.homeCredential="$DEVICE_CREDENTIAL" \
 *   -Pandroid.testInstrumentationRunnerArguments.conversationHandle="$HANDLE"
 * ```
 *
 * Overriding `notAnnotation` is required: an empty value does not clear the
 * default, and the run silently executes zero tests instead.
 *
 * The credential is never written to source, to a profile file, or to a log line.
 */
@RunWith(AndroidJUnit4::class)
@LiveRelay
class LiveRelayHandshakeTest {

    @Test
    fun the_relay_accepts_this_client_identity_and_returns_a_session() {
        val arguments = InstrumentationRegistry.getArguments()
        val route = arguments.getString("homeRoute")
        val credential = arguments.getString("homeCredential")
        val handle = arguments.getString("conversationHandle")
        require(!route.isNullOrBlank() && !credential.isNullOrBlank() && !handle.isNullOrBlank()) {
            "homeRoute, homeCredential, and conversationHandle instrumentation arguments are required"
        }

        val clientId = arguments.getString("relayClientId") ?: "amanda-laptop"
        val deviceId = arguments.getString("relayDeviceId") ?: "android"

        val profile = RelayProfile(
            id = "live-gate",
            endpoint = route,
            clientId = clientId,
            deviceId = deviceId,
            displayName = "Android live gate",
            homeBinding = RelayHomeBinding(route, handle),
        )
        val client = OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(profile.id to credential),
            ),
            helloTimeoutMillis = 15_000,
        )

        val outcome = client.reconnect()
        client.close()

        assertTrue(
            "expected Connected, got $outcome",
            outcome is AndroidReconnectOutcome.Connected,
        )
        assertTrue(
            (outcome as AndroidReconnectOutcome.Connected).connectionId.isNotBlank(),
        )
    }

    @Test
    fun a_typed_turn_reaches_hermes_and_returns_a_projected_response() {
        val arguments = InstrumentationRegistry.getArguments()
        val route = arguments.getString("homeRoute")
        val credential = arguments.getString("homeCredential")
        val handle = arguments.getString("conversationHandle")
        require(!route.isNullOrBlank() && !credential.isNullOrBlank() && !handle.isNullOrBlank()) {
            "homeRoute, homeCredential, and conversationHandle instrumentation arguments are required"
        }

        val profile = RelayProfile(
            id = "live-gate",
            endpoint = route,
            clientId = arguments.getString("relayClientId") ?: "amanda-laptop",
            deviceId = arguments.getString("relayDeviceId") ?: "android",
            displayName = "Android live gate",
            homeBinding = RelayHomeBinding(route, handle),
        )
        val sink = AudioTrackAudioSink()
        val client = OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(profile.id to credential),
            ),
            helloTimeoutMillis = 15_000,
            audioSink = sink,
        )

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)

        val result = client.beginTurn(
            AndroidTurnRequest(
                AndroidProfile(profile.id, profile.displayName, profile.deviceId),
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
        client.close()

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

    @Test
    fun an_interrupt_stops_a_real_turn_and_keeps_what_was_already_said() {
        val arguments = InstrumentationRegistry.getArguments()
        val route = arguments.getString("homeRoute")
        val credential = arguments.getString("homeCredential")
        val handle = arguments.getString("conversationHandle")
        require(!route.isNullOrBlank() && !credential.isNullOrBlank() && !handle.isNullOrBlank()) {
            "homeRoute, homeCredential, and conversationHandle instrumentation arguments are required"
        }

        val profile = RelayProfile(
            id = "live-gate",
            endpoint = route,
            clientId = arguments.getString("relayClientId") ?: "amanda-laptop",
            deviceId = arguments.getString("relayDeviceId") ?: "android",
            displayName = "Android live gate",
            homeBinding = RelayHomeBinding(route, handle),
        )
        val client = OkHttpRelaySessionClient(
            collection = {
                RelayProfileCollection(profiles = listOf(profile), selectedId = profile.id)
            },
            credentials = InMemoryRelayCredentialStore(
                homeCredentials = mapOf(profile.id to credential),
            ),
            helloTimeoutMillis = 15_000,
            audioSink = AudioTrackAudioSink(),
        )

        assertTrue(client.reconnect() is AndroidReconnectOutcome.Connected)
        assertTrue("the relay did not advertise interrupt", client.supportsInterrupt())

        val binding = (
            client.beginTurn(
                AndroidTurnRequest(
                    AndroidProfile(profile.id, profile.displayName, profile.deviceId),
                    AndroidTurnInput.Typed(
                        "Please count slowly from one to forty, one number per sentence.",
                    ),
                ),
            ) as AndroidInitiationResult.Accepted
            ).binding

        var state = AndroidTurnState.awaitingEvents(binding)
        val speaking = java.util.concurrent.CountDownLatch(1)
        val settled = java.util.concurrent.CountDownLatch(1)
        val observation = client.observeTurn(binding) { event ->
            state = AndroidTurnStateReducer.reduce(state, event)
            if (state.responseText.isNotBlank()) speaking.countDown()
            if (state.isTerminal) settled.countDown()
        }

        // Interrupt once Hermes has actually started answering.
        assertTrue(
            "the relay never began responding",
            speaking.await(90, java.util.concurrent.TimeUnit.SECONDS),
        )
        val partialAtInterrupt = state.responseText
        assertTrue(client.interruptTurn(binding))

        val stopped = settled.await(60, java.util.concurrent.TimeUnit.SECONDS)
        observation.cancel()
        client.close()

        assertTrue("the interrupted turn never settled", stopped)
        assertEquals(AndroidTurnPhase.Interrupted, state.phase)
        assertTrue(
            "the partial response was discarded",
            state.responseText.startsWith(partialAtInterrupt),
        )
    }
}
