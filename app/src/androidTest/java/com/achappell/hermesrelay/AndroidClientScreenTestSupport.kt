package com.achappell.hermesrelay

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.concurrent.Executors

/**
 * Hosts [AndroidClientScreen] on a runtime scoped to the test composition.
 *
 * Production builds one process-scoped [HomeRuntime] in `HermesRelayApplication`;
 * these tests supply a fake port per test, so each composition gets its own
 * runtime and ends it when the composition leaves, as the screen did before the
 * runtime moved out of Compose (`ANDROID-HOME-07`).
 */
@Composable
internal fun AndroidClientScreen(
    clientPort: AndroidClientPort,
    configuration: RelayConfigurationController? = null,
    homeAdministration: HomeDeviceAdministrationController? = null,
    speechInput: AndroidSpeechInput? = null,
    historyStore: AndroidHistoryStore? = null,
    homePairing: HomeClientPairingCoordinator? = null,
    pendingPairingLink: String? = null,
    onPendingPairingLinkConsumed: () -> Unit = {},
    microphonePermissionSource: RuntimePermissionSource? = null,
    openApplicationSettings: ((android.content.Intent) -> Unit)? = null,
) {
    val runtime = remember(clientPort, speechInput, historyStore) {
        val mainHandler = Handler(Looper.getMainLooper())
        HomeRuntime(
            clientPort = clientPort,
            speechInput = speechInput,
            historyStore = historyStore,
            postToMain = { mainHandler.post(it) },
            workExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "hermes-android-work").apply { isDaemon = true }
            },
        )
    }
    DisposableEffect(runtime) {
        runtime.activityCreated()
        onDispose { runtime.activityDestroyed(isFinishing = true, isChangingConfigurations = false) }
    }
    AndroidClientScreen(
        runtime = runtime,
        configuration = configuration,
        homeAdministration = homeAdministration,
        homePairing = homePairing,
        pendingPairingLink = pendingPairingLink,
        onPendingPairingLinkConsumed = onPendingPairingLinkConsumed,
        microphonePermissionSource = microphonePermissionSource,
        openApplicationSettings = openApplicationSettings,
    )
}
