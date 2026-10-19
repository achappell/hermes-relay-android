package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSectionsTest {
    @Test
    fun sections_follow_the_grouped_settings_order() {
        assertEquals(
            listOf("Profiles", "HomePairing", "Voice", "Advanced", "Troubleshooting"),
            SettingsSection.entries.map { it.name },
        )
    }

    @Test
    fun section_tags_are_unique_and_keep_the_diagnostics_tag() {
        val tags = SettingsSection.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertEquals("android_diagnostics", SettingsSection.Troubleshooting.tag)
    }

    @Test
    fun toggling_adds_then_removes_a_room() {
        val added = RoomSelection.toggle(emptyList(), " kitchen ")
        assertEquals(listOf("kitchen"), added)
        assertEquals(emptyList<String>(), RoomSelection.toggle(added, "kitchen"))
    }

    @Test
    fun toggling_preserves_selection_order_and_ignores_blank_ids() {
        val rooms = RoomSelection.toggle(RoomSelection.toggle(emptyList(), "office"), "kitchen")
        assertEquals(listOf("office", "kitchen"), rooms)
        assertEquals(rooms, RoomSelection.toggle(rooms, "   "))
    }
}
