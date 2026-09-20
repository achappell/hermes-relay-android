package com.achappell.hermesrelay

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The non-secret half of a relay configuration.
 *
 * The bearer token is never held here. It lives in [RelayCredentialStore] and
 * is read only by the transport, so a token cannot reach UI state, a saved
 * instance bundle, or a serialized profile file.
 */
internal data class RelayProfile(
    val id: String,
    /** Legacy field retained as the editable route label for old JSON. */
    val endpoint: String,
    val clientId: String,
    val deviceId: String,
    val displayName: String,
    val homeBinding: RelayHomeBinding? = null,
    val homeAdministration: RelayHomeAdministration? = null,
)

/** Versioned, non-secret metadata issued by the Home pairing flow. */
internal data class RelayHomeBinding(
    val approvedRoute: String,
    val conversationHandle: String,
    val schemaVersion: Int = HOME_BINDING_SCHEMA_VERSION,
) {
    companion object {
        const val HOME_BINDING_SCHEMA_VERSION = 1
    }
}

/** Non-secret lifecycle state for Home Device administration. */
internal enum class RelayHomeAdministrationPhase {
    Discovered,
    PendingApproval,
    Approved,
    SetupPending,
    StaleRevision,
    Ready,
    Unavailable,
    Revoked,
    Expired,
}

/** Persisted Home administration metadata; credential material stays in Keystore. */
internal data class RelayHomeAdministration(
    val phase: RelayHomeAdministrationPhase,
    val deviceId: String? = null,
    val generation: Int? = null,
    val configurationRevision: Int? = null,
    val requestId: String? = null,
    val credentialExpiresAt: Double? = null,
    val credentialScope: HomeCredentialScope? = null,
    val lastError: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

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
    StorageUnavailable,
}

/**
 * Validates a profile before it is saved.
 *
 * This is a save-time concern, not a connect-time one: the transport accepts
 * whatever endpoint was stored so that tests can drive it against a local
 * server without a certificate.
 */
internal object RelayProfileValidator {
    const val APPROVED_HOME_BRIDGE_PATH = "/api/v1/bridge/ws"
    const val MAX_HOME_CONVERSATION_HANDLE_BYTES = 256
    const val MAX_HOME_ROUTE_ID_BYTES = 256

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
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return RelayProfileError.EndpointMalformed
        if (uri.scheme == null) return RelayProfileError.EndpointMalformed
        if (!uri.scheme.equals("wss", ignoreCase = true)) {
            return RelayProfileError.EndpointNotSecure
        }
        val host = uri.host
        if (host.isNullOrBlank() || uri.userInfo != null) {
            return RelayProfileError.EndpointMalformed
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return RelayProfileError.EndpointMalformed
        }

        // A bare address can never match a certificate, and accepting one would
        // silently reopen the cleartext path the wss:// requirement closes.
        if (bareAddress.matches(host)) return RelayProfileError.EndpointBareAddress

        return null
    }

    /**
     * The Home pairing contract accepts a host/root base or the exact bridge
     * path.  A route prefix is not a harmless variation: appending the bridge
     * path to it would open a different endpoint.
     */
    fun validateApprovedHomeRoute(route: String): RelayProfileError? {
        val normalized = route.trim()
        val endpointError = validateEndpoint(normalized)
        if (endpointError != null) return endpointError

        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return RelayProfileError.EndpointMalformed
        val rawPath = uri.rawPath.orEmpty()
        if (
            rawPath.isNotEmpty() &&
            rawPath != "/" &&
            rawPath != APPROVED_HOME_BRIDGE_PATH &&
            rawPath != "$APPROVED_HOME_BRIDGE_PATH/"
        ) {
            return RelayProfileError.EndpointMalformed
        }
        return null
    }

    fun isValidHomeConversationHandle(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in 1..MAX_HOME_CONVERSATION_HANDLE_BYTES &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || it.isWhitespace() }
    }
}

internal data class RelayProfileCollection(
    val profiles: List<RelayProfile> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: RelayProfile?
        get() = profiles.firstOrNull { it.id == selectedId }

    fun add(profile: RelayProfile): RelayProfileCollection = upsert(profile).let {
        if (it.selectedId == null) it.copy(selectedId = profile.id) else it
    }

    /** Replaces one Profile in place so migration cannot create a second identity. */
    fun upsert(profile: RelayProfile): RelayProfileCollection {
        val existing = profiles.any { it.id == profile.id }
        return copy(
            profiles = if (existing) {
                profiles.map { current -> if (current.id == profile.id) profile else current }
            } else {
                profiles + profile
            },
        )
    }

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
            val item = JSONObject()
                    .put("id", profile.id)
                    .put("endpoint", profile.endpoint)
                    .put("client_id", profile.clientId)
                    .put("device_id", profile.deviceId)
                    .put("display_name", profile.displayName)
            profile.homeBinding?.let { binding ->
                item.put(
                    "home_binding",
                    JSONObject()
                        .put("schema", binding.schemaVersion)
                        .put("route", binding.approvedRoute)
                        .put("conversation_handle", binding.conversationHandle),
                )
            }
            profile.homeAdministration?.let { administration ->
                item.put(
                    "home_administration",
                    JSONObject()
                        .put("schema", RelayHomeAdministration.SCHEMA_VERSION)
                        .put("phase", administration.phase.name)
                        .putOpt("device_id", administration.deviceId)
                        .putOpt("generation", administration.generation)
                        .putOpt("configuration_revision", administration.configurationRevision)
                        .putOpt("request_id", administration.requestId)
                        .putOpt("credential_expires_at", administration.credentialExpiresAt)
                        .putOpt("scope", administration.credentialScope?.toJson())
                        .putOpt("last_error", administration.lastError),
                )
            }
            array.put(item)
        }
        return JSONObject()
            .put("schema_version", PROFILE_COLLECTION_SCHEMA_VERSION)
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
                    val homeBinding = item.optJSONObject("home_binding")?.let { binding ->
                        val route = binding.optString("route").trim()
                        val handle = binding.optString("conversation_handle")
                        val version = binding.optInt("schema", 0)
                        if (
                            version == RelayHomeBinding.HOME_BINDING_SCHEMA_VERSION &&
                            route.isNotBlank() &&
                            handle.isNotBlank()
                        ) {
                            RelayHomeBinding(route, handle, version)
                        } else {
                            null
                        }
                    } ?: legacyHomeBinding(item)
                    val homeAdministration = item.optJSONObject("home_administration")
                        ?.let(::parseHomeAdministration)
                    RelayProfile(
                        id = id,
                        endpoint = item.optString("endpoint"),
                        clientId = item.optString("client_id"),
                        deviceId = item.optString("device_id"),
                        displayName = item.optString("display_name"),
                        homeBinding = homeBinding,
                        homeAdministration = homeAdministration,
                    )
                }
                val selected = root.optString("selected_id").takeIf { it.isNotBlank() }
                RelayProfileCollection(
                    profiles = profiles,
                    selectedId = selected?.takeIf { id -> profiles.any { it.id == id } },
                )
            }.getOrElse { RelayProfileCollection() }
        }

        private fun parseHomeAdministration(administration: JSONObject): RelayHomeAdministration {
            if (
                administration.optInt("schema", 0) !=
                    RelayHomeAdministration.SCHEMA_VERSION
            ) {
                return invalidHomeAdministration()
            }
            return runCatching {
                RelayHomeAdministration(
                    phase = RelayHomeAdministrationPhase.valueOf(
                        administration.getString("phase"),
                    ),
                    deviceId = optionalText(administration, "device_id"),
                    generation = optionalNonNegativeInt(administration, "generation"),
                    configurationRevision = optionalNonNegativeInt(
                        administration,
                        "configuration_revision",
                    ),
                    requestId = optionalText(administration, "request_id"),
                    credentialExpiresAt = optionalPositiveDouble(
                        administration,
                        "credential_expires_at",
                    ),
                    credentialScope = if (
                        !administration.has("scope") ||
                        administration.isNull("scope")
                    ) {
                        null
                    } else {
                        administration.getJSONObject("scope").toHomeCredentialScope()
                    },
                    lastError = optionalText(administration, "last_error"),
                )
            }.getOrElse { invalidHomeAdministration() }
        }

        private fun optionalText(administration: JSONObject, name: String): String? {
            if (!administration.has(name) || administration.isNull(name)) return null
            return administration.getString(name).trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("blank $name")
        }

        private fun optionalNonNegativeInt(administration: JSONObject, name: String): Int? {
            if (!administration.has(name) || administration.isNull(name)) return null
            return administration.getInt(name).also {
                require(it >= 0) { "negative $name" }
            }
        }

        private fun optionalPositiveDouble(administration: JSONObject, name: String): Double? {
            if (!administration.has(name) || administration.isNull(name)) return null
            return administration.getDouble(name).also {
                require(it.isFinite() && it > 0.0) { "invalid $name" }
            }
        }

        private fun invalidHomeAdministration() = RelayHomeAdministration(
            phase = RelayHomeAdministrationPhase.Unavailable,
            lastError = HomeAdministrationError.InvalidResponse.name,
        )

        private fun legacyHomeBinding(item: JSONObject): RelayHomeBinding? {
            val route = item.optString("home_route").trim()
            val handle = item.optString("conversation_handle")
            return if (route.isNotBlank() && handle.isNotBlank()) {
                RelayHomeBinding(route, handle)
            } else {
                null
            }
        }

        private const val PROFILE_COLLECTION_SCHEMA_VERSION = 2
    }
}
