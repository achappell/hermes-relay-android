package com.achappell.hermesrelay

import org.json.JSONArray
import org.json.JSONObject

/**
 * The non-secret half of a relay configuration.
 *
 * The bearer token is never held here. It lives in [RelayCredentialStore] and
 * is read only by the transport, so a token cannot reach UI state, a saved
 * instance bundle, or a serialized profile file.
 */
internal data class RelayProfile(
    val id: String,
    val endpoint: String,
    val clientId: String,
    val deviceId: String,
    val displayName: String,
)

internal enum class RelayProfileField {
    Endpoint,
    ClientId,
    DeviceId,
    DisplayName,
    Token,
}

internal enum class RelayProfileError {
    Required,
    EndpointMalformed,
    EndpointNotSecure,
    EndpointBareAddress,
}

/**
 * Validates a profile before it is saved.
 *
 * This is a save-time concern, not a connect-time one: the transport accepts
 * whatever endpoint was stored so that tests can drive it against a local
 * server without a certificate.
 */
internal object RelayProfileValidator {
    private val bareAddress = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[?[0-9a-fA-F:]+]?$")

    fun validate(
        endpoint: String,
        clientId: String,
        deviceId: String,
        displayName: String,
    ): Map<RelayProfileField, RelayProfileError> {
        val errors = mutableMapOf<RelayProfileField, RelayProfileError>()

        val trimmedEndpoint = endpoint.trim()
        when {
            trimmedEndpoint.isEmpty() -> errors[RelayProfileField.Endpoint] = RelayProfileError.Required
            else -> validateEndpoint(trimmedEndpoint)?.let { errors[RelayProfileField.Endpoint] = it }
        }

        if (clientId.isBlank()) errors[RelayProfileField.ClientId] = RelayProfileError.Required
        if (deviceId.isBlank()) errors[RelayProfileField.DeviceId] = RelayProfileError.Required
        if (displayName.isBlank()) errors[RelayProfileField.DisplayName] = RelayProfileError.Required

        return errors
    }

    private fun validateEndpoint(endpoint: String): RelayProfileError? {
        val separator = endpoint.indexOf("://")
        if (separator <= 0) return RelayProfileError.EndpointMalformed

        val scheme = endpoint.substring(0, separator).lowercase()
        if (scheme != "wss") return RelayProfileError.EndpointNotSecure

        val remainder = endpoint.substring(separator + 3)
        val host = remainder.substringBefore('/').substringBefore('?').substringBefore(':')
        if (host.isEmpty()) return RelayProfileError.EndpointMalformed

        // A bare address can never match a certificate, and accepting one would
        // silently reopen the cleartext path the wss:// requirement closes.
        if (bareAddress.matches(host)) return RelayProfileError.EndpointBareAddress

        return null
    }
}

internal data class RelayProfileCollection(
    val profiles: List<RelayProfile> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: RelayProfile?
        get() = profiles.firstOrNull { it.id == selectedId }

    fun add(profile: RelayProfile): RelayProfileCollection = copy(
        profiles = profiles.filterNot { it.id == profile.id } + profile,
        selectedId = selectedId ?: profile.id,
    )

    fun select(id: String): RelayProfileCollection =
        if (profiles.none { it.id == id }) this else copy(selectedId = id)

    /**
     * Removes a profile. Deleting the selected profile clears the selection
     * immediately rather than leaving a dangling pointer to a profile that no
     * longer exists.
     */
    fun remove(id: String): RelayProfileCollection {
        val remaining = profiles.filterNot { it.id == id }
        return RelayProfileCollection(
            profiles = remaining,
            selectedId = when {
                selectedId != id -> selectedId
                else -> null
            },
        )
    }

    fun toJson(): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("endpoint", profile.endpoint)
                    .put("client_id", profile.clientId)
                    .put("device_id", profile.deviceId)
                    .put("display_name", profile.displayName),
            )
        }
        return JSONObject()
            .put("profiles", array)
            .putOpt("selected_id", selectedId)
            .toString()
    }

    companion object {
        fun fromJson(raw: String): RelayProfileCollection {
            if (raw.isBlank()) return RelayProfileCollection()
            return runCatching {
                val root = JSONObject(raw)
                val array = root.optJSONArray("profiles") ?: JSONArray()
                val profiles = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    RelayProfile(
                        id = id,
                        endpoint = item.optString("endpoint"),
                        clientId = item.optString("client_id"),
                        deviceId = item.optString("device_id"),
                        displayName = item.optString("display_name"),
                    )
                }
                val selected = root.optString("selected_id").takeIf { it.isNotBlank() }
                RelayProfileCollection(
                    profiles = profiles,
                    selectedId = selected?.takeIf { id -> profiles.any { it.id == id } },
                )
            }.getOrElse { RelayProfileCollection() }
        }
    }
}
