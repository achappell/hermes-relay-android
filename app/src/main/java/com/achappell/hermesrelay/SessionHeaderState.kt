package com.achappell.hermesrelay

/** The design token role used by the connection dot. */
internal enum class SessionHeaderColorRole { Live, Attention, Unavailable }

/** Status label/resource and dot role presented for each transport state. */
internal enum class SessionHeaderStatus(
    val colorRole: SessionHeaderColorRole,
    val labelRes: Int,
) {
    Connected(SessionHeaderColorRole.Live, R.string.android_connection_connected),
    Disconnected(SessionHeaderColorRole.Unavailable, R.string.android_connection_disconnected),
    Reconnecting(SessionHeaderColorRole.Attention, R.string.android_connection_reconnecting),
    Failed(SessionHeaderColorRole.Unavailable, R.string.android_connection_failed),
}

internal fun sessionHeaderStatus(connection: AndroidConnectionState): SessionHeaderStatus = when (connection) {
    AndroidConnectionState.Connected -> SessionHeaderStatus.Connected
    AndroidConnectionState.Disconnected -> SessionHeaderStatus.Disconnected
    is AndroidConnectionState.Reconnecting -> SessionHeaderStatus.Reconnecting
    is AndroidConnectionState.Failed -> SessionHeaderStatus.Failed
}

/** Formats elapsed monotonic time without allocating a formatter or using wall-clock time. */
internal fun formatSessionDuration(elapsedMillis: Long): String {
    val seconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours == 0L) {
        buildString(5) {
            if (minutes < 10) append('0')
            append(minutes)
            append(':')
            if (remainder < 10) append('0')
            append(remainder)
        }
    } else {
        buildString(7) {
            append(hours)
            append(':')
            if (minutes < 10) append('0')
            append(minutes)
            append(':')
            if (remainder < 10) append('0')
            append(remainder)
        }
    }
}
