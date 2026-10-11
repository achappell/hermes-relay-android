package com.achappell.hermesrelay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * [NetworkEvents] backed by the platform's default-network callback
 * (`ANDROID-HOME-04`). Needs only the normal `ACCESS_NETWORK_STATE` permission.
 *
 * Android replays the current network to a new callback, so a listener must
 * treat [NetworkListener.onAvailable] as idempotent.
 */
internal class ConnectivityNetworkEvents(
    context: Context,
    private val journal: DiagnosticsJournal = DiagnosticsJournal.None,
) : NetworkEvents {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun observe(listener: NetworkListener): AndroidTurnObservation {
        val connectivity = manager ?: return AndroidTurnObservation {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                listener.onAvailable()
            }
        }
        val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        if (registered.isFailure) {
            // Without the callback a reconnect still follows its backoff; only
            // the early retry on a returning network is lost.
            journal.record("network observe result=unavailable")
            return AndroidTurnObservation {}
        }
        return AndroidTurnObservation {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
