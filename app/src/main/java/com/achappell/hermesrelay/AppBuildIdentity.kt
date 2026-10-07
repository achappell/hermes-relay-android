package com.achappell.hermesrelay

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/**
 * Which build is installed (`ANDROID-REL-01`): version name and code from the
 * installed package, plus the build type and short git revision baked in at
 * build time. Two local builds of the same version differ by revision; the
 * version code contract (`scripts/check-apk-metadata.sh`) is untouched.
 *
 * Carries no serial, account or network identity.
 */
internal data class AppBuildIdentity(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val revision: String,
) {
    /** `Version 0.3.1 (301) · release · 3e10ae2` */
    fun label(): String = "Version $versionName ($versionCode) · $buildType · $revision"

    companion object {
        const val UNKNOWN = "unknown"
        private val SHORT_REVISION = Regex("[0-9a-f]{4,40}")

        /** Normalises raw values so a label can never carry anything but build facts. */
        fun of(
            versionName: String?,
            versionCode: Long,
            debug: Boolean,
            revision: String?,
        ): AppBuildIdentity = AppBuildIdentity(
            versionName = versionName?.trim()?.take(32)?.ifBlank { null } ?: UNKNOWN,
            // A negative or wrapped code is never shown as such.
            versionCode = versionCode.coerceAtLeast(0L),
            buildType = if (debug) "debug" else "release",
            revision = revision?.trim()?.takeIf { SHORT_REVISION.matches(it) } ?: UNKNOWN,
        )

        /** Read from the installed package, not a hard-coded string, so it cannot drift from the APK. */
        fun current(context: Context): AppBuildIdentity {
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            return of(
                versionName = info?.versionName,
                versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
                debug = BuildConfig.DEBUG,
                revision = BuildConfig.GIT_REVISION,
            )
        }
    }
}
