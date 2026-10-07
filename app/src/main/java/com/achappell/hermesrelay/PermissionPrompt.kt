package com.achappell.hermesrelay

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The one action that can still help once the platform stops asking: the app
 * details screen. It is a user-initiated control, never opened automatically.
 */
@Composable
internal fun OpenSettingsButton(
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .a11yOrder(A11yOrder.ACTION),
        onClick = onClick,
    ) {
        Text(stringResource(R.string.android_permission_open_settings))
    }
}

internal fun RuntimePermissionState.microphoneMessageRes(): Int = when (this) {
    RuntimePermissionState.NotAsked,
    RuntimePermissionState.Granted,
    -> R.string.android_capture_block_permission

    RuntimePermissionState.DeniedWithRationale ->
        R.string.android_capture_block_permission_rationale

    RuntimePermissionState.PermanentlyDenied ->
        R.string.android_capture_block_permission_settings
}

internal fun RuntimePermissionState.cameraMessageRes(): Int = when (this) {
    RuntimePermissionState.NotAsked,
    RuntimePermissionState.Granted,
    -> R.string.android_home_pair_scan_hint

    RuntimePermissionState.DeniedWithRationale ->
        R.string.android_home_pair_scan_rationale

    RuntimePermissionState.PermanentlyDenied ->
        R.string.android_home_pair_scan_settings
}

/**
 * A runtime permission as one screen sees it: its current state, a request that
 * is only ever launched from a user action, and the Settings escape hatch.
 */
@Stable
internal class RuntimePermissionController(
    private val source: RuntimePermissionSource,
    private val launch: () -> Unit,
    private val startSettings: () -> Unit,
) {
    var state by mutableStateOf(source.state())
        private set

    /** Bumps whenever the state is re-read, so dependants recompute. */
    var revision by mutableIntStateOf(0)
        private set

    /** Re-reads the platform facts, e.g. after returning from Settings. */
    fun refresh() {
        state = source.state()
        revision += 1
    }

    /** Launch the platform dialog. Call only from a user action. */
    fun request() {
        source.markAsked()
        launch()
    }

    fun openSettings() = startSettings()
}

@Composable
internal fun rememberRuntimePermission(
    permission: String,
    source: RuntimePermissionSource? = null,
    openSettings: ((Intent) -> Unit)? = null,
    onResult: (Boolean) -> Unit = {},
): RuntimePermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resolvedSource = source ?: remember(context, permission) {
        PlatformRuntimePermissionSource(context, permission)
    }
    val latestOnResult = rememberUpdatedState(onResult)
    val latestOpenSettings = rememberUpdatedState(openSettings)
    var controller by remember { mutableStateOf<RuntimePermissionController?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller?.refresh()
        latestOnResult.value(granted)
    }
    val created = remember(resolvedSource, launcher) {
        RuntimePermissionController(
            source = resolvedSource,
            launch = { launcher.launch(permission) },
            startSettings = {
                val intent = applicationDetailsSettingsIntent(context.packageName)
                latestOpenSettings.value?.invoke(intent) ?: context.startActivity(intent)
            },
        ).also { controller = it }
    }
    DisposableEffect(lifecycleOwner, created) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) created.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return created
}
