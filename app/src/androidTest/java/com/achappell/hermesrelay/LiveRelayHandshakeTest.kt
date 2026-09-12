package com.achappell.hermesrelay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
