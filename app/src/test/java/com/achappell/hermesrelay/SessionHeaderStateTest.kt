package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHeaderStateTest {
    @Test
    fun connection_states_map_to_dot_color_role_and_status_text_resource() {
        assertPresentation(
            AndroidConnectionState.Connected,
            SessionHeaderColorRole.Live,
            R.string.android_connection_connected,
        )
        assertPresentation(
            AndroidConnectionState.Disconnected,
            SessionHeaderColorRole.Unavailable,
            R.string.android_connection_disconnected,
        )
        assertPresentation(
            AndroidConnectionState.Reconnecting(2, 5),
            SessionHeaderColorRole.Attention,
            R.string.android_connection_reconnecting,
        )
        assertPresentation(
            AndroidConnectionState.Failed("offline"),
            SessionHeaderColorRole.Unavailable,
            R.string.android_connection_failed,
        )
    }

    private fun assertPresentation(
        connection: AndroidConnectionState,
        colorRole: SessionHeaderColorRole,
        labelRes: Int,
    ) {
        val status = sessionHeaderStatus(connection)
        assertEquals(colorRole, status.colorRole)
        assertEquals(labelRes, status.labelRes)
    }

    @Test
    fun elapsed_duration_uses_minutes_until_one_hour_and_hours_afterward() {
        val clock = ManualClock()
        val startedAt = clock.nanoTime()
        assertEquals("00:00", formatSessionDuration((clock.nanoTime() - startedAt) / 1_000_000))
        clock.advanceMillis(65_000)
        assertEquals("01:05", formatSessionDuration((clock.nanoTime() - startedAt) / 1_000_000))
        clock.advanceMillis(3_600_000)
        assertEquals("1:01:05", formatSessionDuration((clock.nanoTime() - startedAt) / 1_000_000))
    }
}
