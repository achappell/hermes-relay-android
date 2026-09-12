package com.achappell.hermesrelay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Live Hermes relay transport for the Android Client.
 *
 * A-4 implements the configuration and handshake half of [AndroidClientPort]:
 * [snapshot] and [reconnect] are real. Turn submission and normalized event
 * delivery stay on the bootstrap behavior until their own story.
 */
internal class OkHttpRelaySessionClient(
    private val collection: () -> RelayProfileCollection,
    private val credentials: RelayCredentialStore,
    private val httpClient: OkHttpClient = defaultClient(),
    private val helloTimeoutMillis: Long = DEFAULT_HELLO_TIMEOUT_MILLIS,
) : AndroidClientPort {

    private val activeSocket = AtomicReference<WebSocket?>(null)

    override fun snapshot(): AndroidClientSnapshot {
        val profile = collection().selected
        val authorization = when {
            profile == null -> AndroidAuthorizationState.NotConfigured
            !credentials.hasToken(profile.id) -> AndroidAuthorizationState.NotConfigured
            else -> AndroidAuthorizationState.Verified
        }

        return AndroidClientSnapshot(
            titleRes = BootstrapState.titleRes,
            descriptionRes = BootstrapState.descriptionRes,
            boundaryRes = BootstrapState.boundaryRes,
            selectedProfile = profile?.let { AndroidProfile(it.clientId, it.displayName) },
            authorizationState = authorization,
        )
    }

    override fun beginTurn(request: AndroidTurnRequest): AndroidInitiationResult =
        AndroidInitiationResult.Rejected(AndroidInitiationFailure.SessionUnavailable)

    override fun reconnect(): AndroidReconnectOutcome {
        val profile = collection().selected
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "No Hermes relay profile is selected.",
            )
        val token = credentials.read(profile.id)
            ?: return AndroidReconnectOutcome.Unrecoverable(
                "No credential is stored for this Hermes relay profile.",
            )

        // A reconnect always abandons the prior socket. The Session identity
        // that replaces it comes from the server's hello_ack, never from the
        // Session this call is superseding.
        activeSocket.getAndSet(null)?.cancel()

        val sessionId = UUID.randomUUID().toString()
        val outcome = AtomicReference<AndroidReconnectOutcome?>(null)
        val settled = CountDownLatch(1)

        val request = Request.Builder()
            .url(profile.endpoint)
            .header("Authorization", "Bearer $token")
            .build()

        val socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(helloFrame(profile, sessionId))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (outcome.get() != null) return
                    outcome.set(readHelloAck(text, sessionId))
                    if (outcome.get() != null) settled.countDown()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (outcome.compareAndSet(null, classify(t, response))) {
                        settled.countDown()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (outcome.compareAndSet(
                            null,
                            AndroidReconnectOutcome.Retryable(
                                reason.ifBlank { "The Hermes relay closed the connection." },
                            ),
                        )
                    ) {
                        settled.countDown()
                    }
                }
            },
        )

        val answered = settled.await(helloTimeoutMillis, TimeUnit.MILLISECONDS)
        val result = when {
            !answered -> AndroidReconnectOutcome.Retryable(
                "The Hermes relay did not acknowledge the session in time.",
            )

            else -> outcome.get() ?: AndroidReconnectOutcome.Retryable(
                "The Hermes relay did not acknowledge the session.",
            )
        }

        if (result is AndroidReconnectOutcome.Connected) {
            activeSocket.set(socket)
        } else {
            socket.cancel()
        }
        return result
    }

    /** Abandon the active socket without reporting a transport failure. */
    fun disconnect() {
        activeSocket.getAndSet(null)?.close(NORMAL_CLOSURE, null)
    }

    private fun helloFrame(profile: RelayProfile, sessionId: String): String =
        JSONObject()
            .put("type", "hello")
            .put("protocol_version", PROTOCOL_VERSION)
            .put("client_id", profile.clientId)
            .put("device_id", profile.deviceId)
            .put("session_id", sessionId)
            .put("display_name", profile.displayName)
            .toString()

    private fun readHelloAck(text: String, sessionId: String): AndroidReconnectOutcome? {
        val frame = runCatching { JSONObject(text) }.getOrNull()
            ?: return AndroidReconnectOutcome.Retryable(
                "The Hermes relay sent a frame the Client could not read.",
            )

        return when (frame.optString("type")) {
            "hello_ack" -> {
                val version = frame.optInt("protocol_version", PROTOCOL_VERSION)
                if (version != PROTOCOL_VERSION) {
                    AndroidReconnectOutcome.Unrecoverable(
                        "The Hermes relay speaks protocol version $version; " +
                            "this Client speaks $PROTOCOL_VERSION.",
                    )
                } else {
                    AndroidReconnectOutcome.Connected(
                        frame.optString("session_id").takeIf { it.isNotBlank() } ?: sessionId,
                    )
                }
            }

            "error" -> AndroidReconnectOutcome.Unrecoverable(
                frame.optString("message").ifBlank { "The Hermes relay rejected the session." },
            )

            // Anything else is a frame that arrived before the acknowledgement;
            // keep waiting rather than guessing at its meaning.
            else -> null
        }
    }

    private fun classify(t: Throwable, response: Response?): AndroidReconnectOutcome {
        response?.code?.let { code ->
            if (code == 401 || code == 403) {
                return AndroidReconnectOutcome.Unrecoverable(
                    "The Hermes relay rejected this credential.",
                )
            }
        }

        return when (t) {
            // Off the tailnet. Retrying inside a bounded ladder cannot fix it,
            // and reporting it as a credential failure would be a lie.
            is UnknownHostException -> AndroidReconnectOutcome.Unrecoverable(
                "The Hermes relay could not be found. Check that this device is " +
                    "connected to the Tailscale network.",
            )

            is SSLException -> AndroidReconnectOutcome.Unrecoverable(
                "The Hermes relay's certificate could not be verified.",
            )

            is ConnectException,
            is SocketTimeoutException,
            is SocketException,
            is EOFException,
            -> AndroidReconnectOutcome.Retryable(
                t.message ?: "The Hermes relay is unreachable.",
            )

            else -> AndroidReconnectOutcome.Retryable(
                t.message ?: "The Hermes relay connection failed.",
            )
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_HELLO_TIMEOUT_MILLIS = 10_000L
        private const val NORMAL_CLOSURE = 1000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
