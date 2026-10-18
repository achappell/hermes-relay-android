package com.achappell.hermesrelay

import androidx.annotation.StringRes

/**
 * `ANDROID-UX-11`: the grouped Settings sections, in reading and TalkBack order.
 * Each section is a card with a heading; [tag] is its stable test tag.
 */
internal enum class SettingsSection(val tag: String, @StringRes val titleRes: Int) {
    Profiles("android_settings_section_profiles", R.string.android_settings_profiles),
    HomePairing("android_settings_section_home_pairing", R.string.android_settings_home_pairing),
    Voice("android_settings_section_voice", R.string.android_settings_voice),
    Advanced("android_settings_section_advanced", R.string.android_settings_advanced),
    Troubleshooting("android_diagnostics", R.string.android_diagnostics_title),
}

/** Requested-room selection for enrollment; replaces the comma-separated Rooms field. */
internal object RoomSelection {
    /** Adds [roomId] when absent, removes it when present; blank ids are ignored. */
    fun toggle(selected: List<String>, roomId: String): List<String> {
        val id = roomId.trim()
        if (id.isEmpty()) return selected
        return if (id in selected) selected - id else selected + id
    }
}
