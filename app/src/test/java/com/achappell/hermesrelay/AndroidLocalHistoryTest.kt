package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalHistoryTest {
    private var now = 1_000L
    private fun recorder(store: AndroidHistoryStore, profileId: String? = "profile-1") =
        AndroidHistoryRecorder(store) { now++ }.apply { open(profileId) }

    @Test
    fun a_conversation_is_recorded_in_order_and_survives_a_reload() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store)

        recorder.recordUserTurn("check the weather")
        recorder.recordResponse("Rain later")
        recorder.recordUserTurn("thanks")

        val reloaded = AndroidLocalHistory.fromJson(store.load("profile-1").toJson())

        assertEquals(3, reloaded.entries.size)
        assertEquals(AndroidTranscriptRole.User, reloaded.entries[0].role)
        assertEquals("check the weather", reloaded.entries[0].text)
        assertEquals(AndroidTranscriptRole.Assistant, reloaded.entries[1].role)
        assertEquals("Rain later", reloaded.entries[1].text)
        assertEquals("thanks", reloaded.entries[2].text)
    }

    @Test
    fun each_profile_keeps_its_own_conversation() {
        val store = InMemoryAndroidHistoryStore()

        val first = recorder(store, "profile-1")
        first.recordUserTurn("amanda's question")

        val second = recorder(store, "profile-2")
        second.recordUserTurn("another profile's question")

        assertEquals(1, store.load("profile-1").entries.size)
        assertEquals("amanda's question", store.load("profile-1").entries.single().text)
        assertEquals(
            "another profile's question",
            store.load("profile-2").entries.single().text,
        )
    }

    @Test
    fun switching_profiles_shows_the_other_profiles_conversation() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store, "profile-1")
        recorder.recordUserTurn("amanda's question")

        recorder.open("profile-2")
        assertTrue("another profile's history leaked", recorder.history.entries.isEmpty())

        recorder.open("profile-1")
        assertEquals("amanda's question", recorder.history.entries.single().text)
    }

    @Test
    fun nothing_is_recorded_without_a_selected_profile() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store, profileId = null)

        recorder.recordUserTurn("orphan question")

        assertTrue(recorder.history.entries.isEmpty())
        assertTrue(!store.contains("profile-1"))
    }

    @Test
    fun blank_and_duplicate_entries_are_not_recorded() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store)

        recorder.recordUserTurn("   ")
        recorder.recordResponse("Rain later")
        // A re-render of the same answer is not a second utterance.
        recorder.recordResponse("Rain later")
        recorder.recordResponse("  Rain later  ")

        assertEquals(1, recorder.history.entries.size)
    }

    @Test
    fun retention_is_bounded_so_history_cannot_grow_without_limit() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store)

        repeat(AndroidLocalHistory.ENTRY_LIMIT + 25) { index ->
            recorder.recordUserTurn("question $index")
        }

        val entries = recorder.history.entries
        assertEquals(AndroidLocalHistory.ENTRY_LIMIT, entries.size)
        // The oldest fell away; the newest is kept.
        assertEquals(
            "question ${AndroidLocalHistory.ENTRY_LIMIT + 24}",
            entries.last().text,
        )
        assertEquals("question 25", entries.first().text)
    }

    @Test
    fun clearing_removes_the_conversation_but_keeps_the_draft() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store)
        recorder.recordUserTurn("check the weather")
        recorder.recordDraft("half typed")

        recorder.clear()

        assertTrue(recorder.history.entries.isEmpty())
        assertEquals("half typed", recorder.history.draft)
        assertTrue(store.load("profile-1").entries.isEmpty())
    }

    @Test
    fun deleting_a_profile_deletes_its_conversation() {
        val history = InMemoryAndroidHistoryStore()
        val credentials = InMemoryRelayCredentialStore()
        val controller = RelayConfigurationController(
            profiles = InMemoryRelayProfileStore(),
            credentials = credentials,
            history = history,
            idFactory = { "profile-1" },
        )
        controller.save(
            endpoint = "wss://relay.example/voice-session",
            clientId = "amanda-laptop",
            deviceId = "android",
            displayName = "Amanda",
            token = "relay-token",
        )
        AndroidHistoryRecorder(history).apply {
            open("profile-1")
            recordUserTurn("private question")
        }
        assertTrue(history.contains("profile-1"))

        controller.delete("profile-1")

        assertTrue(
            "a deleted Profile's conversation outlived it",
            !history.contains("profile-1"),
        )
        assertTrue(!credentials.hasToken("profile-1"))
    }

    @Test
    fun an_unreadable_history_file_degrades_to_an_empty_conversation() {
        assertEquals(AndroidLocalHistory(), AndroidLocalHistory.fromJson("{ not json"))
        assertEquals(AndroidLocalHistory(), AndroidLocalHistory.fromJson(""))
    }

    @Test
    fun no_audio_or_credential_is_ever_serialized() {
        val store = InMemoryAndroidHistoryStore()
        val recorder = recorder(store)
        recorder.recordUserTurn("check the weather")
        recorder.recordResponse("Rain later")

        val json = store.load("profile-1").toJson()

        listOf("token", "pcm", "audio", "credential").forEach { forbidden ->
            assertTrue(
                "history serialized something it must not: $forbidden",
                !json.contains(forbidden, ignoreCase = true),
            )
        }
    }
}
