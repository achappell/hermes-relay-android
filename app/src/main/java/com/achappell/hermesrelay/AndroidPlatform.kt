package com.achappell.hermesrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * What the running device lets the Client do, as a plain value.
 *
 * Behaviour that varies with the API level, notification permission or
 * foreground-service support takes this as a constructor argument with no
 * default, so a test must state the policy it asserts instead of silently
 * inheriting whatever the developer machine or CI image reports. iOS shipped
 * two lifecycle tests that were green on macOS and red on the Simulator for
 * exactly that reason (`ANDROID-TEST-01`).
 *
 * Only [current] reads the device. Everything derived from the value is pure.
 */
internal data class AndroidPlatform(
    val apiLevel: Int,
    /**
     * Whether the Client may post notifications. Below API 33 there is no
     * runtime permission, so this is true there.
     */
    val notificationsPermitted: Boolean,
    /** Whether this device can run the Client as a foreground service. */
    val foregroundServiceSupported: Boolean,
) {
    /**
     * `AudioTrack.setStartThresholdInFrames` exists from API 31. Earlier
     * releases must size the buffer to the queued audio instead.
     */
    val supportsPlaybackStartThreshold: Boolean
        get() = apiLevel >= Build.VERSION_CODES.S

    companion object {
        /** Reads the running device. Call once at the runtime root and inject the result. */
        fun current(context: Context): AndroidPlatform = from(
            apiLevel = Build.VERSION.SDK_INT,
            notificationPermissionGranted = notificationPermissionGranted(context),
        )

        /**
         * Builds the value from raw facts so both sides of every API-level
         * decision are testable on the JVM.
         */
        fun from(apiLevel: Int, notificationPermissionGranted: Boolean): AndroidPlatform =
            AndroidPlatform(
                apiLevel = apiLevel,
                notificationsPermitted = apiLevel < Build.VERSION_CODES.TIRAMISU ||
                    notificationPermissionGranted,
                // Foreground services exist on every API level this app installs on
                // (minSdk 26, Android 8.0).
                foregroundServiceSupported = apiLevel >= Build.VERSION_CODES.O,
            )

        private fun notificationPermissionGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
