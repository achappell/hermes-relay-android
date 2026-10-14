package com.achappell.hermesrelay

import org.junit.Assert.*
import org.junit.Test

class AudioRouteEvidenceTest {
    private val headphones = OutputRoute(7, true)
    private val speaker = OutputRoute(1, false)
    private class Queries : AudioRouteQueries {
        var actual = emptyList<OutputRoute>()
        var policy = emptyList<OutputRoute>()
        override fun trackRoutes() = actual
        override fun mediaRoutes() = policy
    }

    @Test fun paused_null_track_uses_selected_media_policy_not_added_device_inventory() {
        val queries = Queries().apply { policy = listOf(speaker) }
        val evidence = AudioRouteEvidence(queries)
        val changes = mutableListOf<AudioRouteChange>()
        evidence.observe { changes += it }
        evidence.registration().added(setOf(headphones.id))
        assertEquals(listOf(speaker), changes.single().selected)
        queries.policy = listOf(headphones)
        assertEquals(listOf(headphones), evidence.selectedRoutes())
    }

    @Test fun actual_route_has_priority_and_multiple_outputs_are_preserved() {
        val queries = Queries().apply {
            actual = listOf(headphones, speaker)
            policy = listOf(headphones)
        }
        val evidence = AudioRouteEvidence(queries)
        assertEquals(listOf(headphones, speaker), evidence.selectedRoutes())
        assertFalse(evidence.selectedRoutes().all { it.personal })
    }

    @Test fun older_android_without_an_inactive_policy_query_remains_unknown() {
        val queries = Queries()
        val evidence = AudioRouteEvidence(queries)
        evidence.observe { }
        evidence.registration().added(setOf(headphones.id))
        assertTrue(evidence.selectedRoutes().isEmpty())
        queries.actual = listOf(headphones)
        assertEquals(listOf(headphones), evidence.selectedRoutes())
    }

    @Test fun removed_id_vetoes_stale_actual_and_policy_routes_until_added_again() {
        val queries = Queries().apply { actual = listOf(headphones); policy = listOf(headphones) }
        val evidence = AudioRouteEvidence(queries)
        val changes = mutableListOf<AudioRouteChange>()
        evidence.observe { changes += it }
        val registration = evidence.registration()
        registration.removed(setOf(headphones.id))
        assertTrue(changes.single().selected.isEmpty())
        assertEquals(setOf(headphones.id), changes.single().removedIds)
        queries.actual = emptyList()
        assertTrue(evidence.selectedRoutes().isEmpty())
        registration.added(setOf(99))
        assertTrue(evidence.selectedRoutes().isEmpty())
        registration.added(setOf(headphones.id))
        assertEquals(listOf(headphones), evidence.selectedRoutes())
    }

    @Test fun stopped_registration_cannot_mutate_new_observation_evidence() {
        val queries = Queries().apply { policy = listOf(headphones) }
        val evidence = AudioRouteEvidence(queries)
        val changes = mutableListOf<AudioRouteChange>()
        evidence.observe { changes += it }
        val stale = evidence.registration()
        evidence.stop()
        evidence.observe { changes += it }
        stale.removed(setOf(headphones.id))
        stale.noisy()
        assertTrue(changes.isEmpty())
        assertEquals(listOf(headphones), evidence.selectedRoutes())
    }

    @Test fun detached_or_replaced_track_cannot_send_a_new_registration_event() {
        val evidence = AudioRouteEvidence(Queries())
        val changes = mutableListOf<AudioRouteChange>()
        evidence.observe { changes += it }
        val old = Any()
        val current = Any()
        evidence.attachTrack(old)
        evidence.attachTrack(current)
        evidence.detachTrack(old)
        evidence.routingChanged(old)
        assertTrue(changes.isEmpty())
        evidence.routingChanged(current)
        assertEquals(1, changes.size)
        evidence.detachTrack(current)
        evidence.routingChanged(current)
        assertEquals(1, changes.size)
    }

    @Test fun emitted_selection_is_an_immutable_snapshot_of_query_time() {
        val mutable = mutableListOf(headphones)
        val queries = Queries().apply { actual = mutable }
        val evidence = AudioRouteEvidence(queries)
        val changes = mutableListOf<AudioRouteChange>()
        evidence.observe { changes += it }
        evidence.registration().changed()
        mutable.clear()
        assertEquals(listOf(headphones), changes.single().selected)
    }
}
