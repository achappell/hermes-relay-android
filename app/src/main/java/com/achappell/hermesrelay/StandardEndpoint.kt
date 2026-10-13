package com.achappell.hermesrelay

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Validation never includes the supplied endpoint in an error message. */
internal object StandardEndpoint {
    private val secretKey = Regex("token|auth|key|secret|credential|password|bearer|signature", RegexOption.IGNORE_CASE)

    fun validate(endpoint: String): RelayProfileError? {
        if (endpoint.isBlank()) return RelayProfileError.Required
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            ?: return RelayProfileError.EndpointMalformed
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("wss", true)) {
            return RelayProfileError.EndpointNotSecure
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || uri.port == 0
        ) return RelayProfileError.EndpointMalformed
        val unsafeQuery = runCatching {
            uri.rawQuery?.split('&', ';')?.any { entry ->
                val key = URLDecoder.decode(entry.substringBefore('='), "UTF-8")
                secretKey.containsMatchIn(key)
            } == true
        }.getOrDefault(true)
        return if (unsafeQuery) RelayProfileError.EndpointMalformed else null
    }

    fun canonicalize(endpoint: String): String {
        require(validate(endpoint) == null) { "Invalid Standard endpoint" }
        val uri = URI(endpoint.trim()).normalize()
        val path = uri.rawPath.orEmpty().let { if (it.isEmpty() || it == "/") "/api/ws" else it }
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "wss://${uri.host.lowercase(Locale.ROOT)}$port$path$query"
    }

    fun normalizeHermesProfile(value: String): String = value.trim().ifEmpty { "default" }
}
