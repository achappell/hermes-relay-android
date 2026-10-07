package com.achappell.hermesrelay

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Where a runtime permission stands, in the four states the UI must tell apart.
 *
 * Android cannot distinguish "never asked" from "permanently denied" on its
 * own: `shouldShowRequestPermissionRationale` is false for both. The
 * distinction needs a recorded fact — whether this app has ever asked.
 */
internal enum class RuntimePermissionState {
    /** The app has not asked yet. Ask only after a user action. */
    NotAsked,

    Granted,

    /** Denied once; the platform will still show its dialog. Explain first. */
    DeniedWithRationale,

    /** The platform will no longer show its dialog. Only Settings can grant. */
    PermanentlyDenied,
}

internal fun resolveRuntimePermissionState(
    isGranted: Boolean,
    hasAsked: Boolean,
    shouldShowRationale: Boolean,
): RuntimePermissionState = when {
    isGranted -> RuntimePermissionState.Granted
    shouldShowRationale -> RuntimePermissionState.DeniedWithRationale
    hasAsked -> RuntimePermissionState.PermanentlyDenied
    else -> RuntimePermissionState.NotAsked
}

/** The platform facts [resolveRuntimePermissionState] needs, faked in tests. */
internal interface RuntimePermissionSource {
    fun isGranted(): Boolean

    fun hasAsked(): Boolean

    fun shouldShowRationale(): Boolean

    /** Records that a request is about to be launched. */
    fun markAsked()
}

internal fun RuntimePermissionSource.state(): RuntimePermissionState =
    resolveRuntimePermissionState(
        isGranted = isGranted(),
        hasAsked = hasAsked(),
        shouldShowRationale = shouldShowRationale(),
    )

/** The Android application-details screen for [packageName]. */
internal fun applicationDetailsSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal class PlatformRuntimePermissionSource(
    private val context: Context,
    private val permission: String,
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
) : RuntimePermissionSource {
    override fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    override fun hasAsked(): Boolean = preferences.getBoolean(askedKey(), false)

    override fun shouldShowRationale(): Boolean {
        val activity = context.findActivity() ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    override fun markAsked() {
        preferences.edit().putBoolean(askedKey(), true).apply()
    }

    private fun askedKey() = "asked:$permission"

    private companion object {
        const val PREFERENCES_NAME = "runtime_permission_requests"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
