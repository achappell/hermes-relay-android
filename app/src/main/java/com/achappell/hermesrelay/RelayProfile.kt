package com.achappell.hermesrelay

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

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
    /** Set when this Profile is one grant of a HOME-NW-17 personal-client pairing. */
    val homeClientGrant: RelayHomeClientGrantRef? = null,
    /**
     * Which connection path the Profile uses. The default derives from the
     * Home link so a Profile without one is never classified as Standard.
     */
    val mode: RelayProfileMode = RelayProfileMode.derive(homeBinding, homeClientGrant),
    /** The Hermes Profile a Standard connection selects; null for every other mode. */
    val hermesProfile: String? = null,
) {
    /**
     * The key of this Profile's Local History. Non-Standard Profiles keep their
     * id so existing history files remain readable. A Standard Profile is keyed
     * by mode, endpoint and Hermes Profile, never by [id], so two Hermes
     * Profiles, two endpoints, or Standard and Home never share a transcript.
     */
    val historyKey: String
        get() = if (mode == RelayProfileMode.Standard) {
            standardHistoryKey(endpoint, hermesProfile ?: RelayProfileValidator.DEFAULT_HERMES_PROFILE)
        } else {
            id
        }

    companion object {
        fun standardHistoryKey(endpoint: String, hermesProfile: String): String {
            val identity = "standard\n${canonicalEndpoint(endpoint)}\n$hermesProfile"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
            return "std-" + digest.joinToString("") { "%02x".format(it) }.take(40)
        }

        /** Lowercase scheme and host, explicit port kept, trailing slash dropped. */
        private fun canonicalEndpoint(endpoint: String): String {
            val trimmed = endpoint.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val host = uri?.host
            if (uri == null || uri.scheme == null || host == null) return trimmed.lowercase().trimEnd('/')
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "${uri.scheme.lowercase()}://${host.lowercase()}$port$path"
        }
    }
}

/** The connection path a Profile uses. */
internal enum class RelayProfileMode(val wireName: String) {
    HomeBridge("home_bridge"),
    Standard("standard"),
    Legacy("legacy"),
    ;

    companion object {
        /** No Home link means Legacy, never Standard. */
        fun derive(
            homeBinding: RelayHomeBinding?,
            homeClientGrant: RelayHomeClientGrantRef?,
        ): RelayProfileMode =
            if (homeBinding != null || homeClientGrant != null) HomeBridge else Legacy

        fun fromWireName(value: String?): RelayProfileMode? =
            values().firstOrNull { it.wireName == value }
    }
}

/**
 * Points a Profile at one grant of a pairing. Both values are opaque, non-secret
 * Home identities; the credential and conversation handle are never stored here.
 */
internal data class RelayHomeClientGrantRef(
    val pairingId: String,
    val grantId: String,
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
    HermesProfile,
}

internal enum class RelayProfileError {
    Required,
    EndpointMalformed,
    EndpointNotSecure,
    EndpointBareAddress,
    EndpointCarriesCredential,
    HermesProfileInvalid,
    Duplicate,
    StorageUnavailable,
}

/** The normalized result of validating a Standard Profile's identity. */
internal data class StandardProfileValidation(
    val endpoint: String,
    val hermesProfile: String,
    val errors: Map<RelayProfileField, RelayProfileError>,
) {
    val isValid: Boolean get() = errors.isEmpty()
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
    const val STANDARD_GATEWAY_PATH = "/api/ws"
    const val DEFAULT_HERMES_PROFILE = "default"

    private val hermesProfileName = Regex("[A-Za-z0-9._-]{1,64}")
    private val credentialQueryKeys = setOf(
        "token", "access_token", "api_key", "apikey", "key", "secret", "password",
        "auth", "authorization", "bearer", "jwt", "credential", "credentials",
        "sig", "signature",
    )

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

    /**
     * Validates the identity of a Standard Profile: a `wss://host[:port]/api/ws`
     * endpoint carrying no credential, and a Hermes Profile name. The client adds
     * the `token` and `profile` query keys itself, so none is accepted here.
     */
    fun validateStandard(endpoint: String, hermesProfile: String): StandardProfileValidation {
        val errors = mutableMapOf<RelayProfileField, RelayProfileError>()
        val trimmed = endpoint.trim()
        var normalized = trimmed
        if (trimmed.isEmpty()) {
            errors[RelayProfileField.Endpoint] = RelayProfileError.Required
        } else {
            val result = normalizeStandardEndpoint(trimmed)
            if (result.second != null) {
                errors[RelayProfileField.Endpoint] = result.second!!
            } else {
                normalized = result.first
            }
        }

        val profile = hermesProfile.trim().ifEmpty { DEFAULT_HERMES_PROFILE }
        if (!hermesProfileName.matches(profile)) {
            errors[RelayProfileField.HermesProfile] = RelayProfileError.HermesProfileInvalid
        }
        return StandardProfileValidation(normalized, profile, errors)
    }

    private fun normalizeStandardEndpoint(endpoint: String): Pair<String, RelayProfileError?> {
        fun fail(error: RelayProfileError) = endpoint to error
        if (endpoint.indexOf("://") <= 0) return fail(RelayProfileError.EndpointMalformed)
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return fail(RelayProfileError.EndpointMalformed)
        val scheme = uri.scheme ?: return fail(RelayProfileError.EndpointMalformed)
        if (!scheme.equals("https", ignoreCase = true) && !scheme.equals("wss", ignoreCase = true)) {
            return fail(RelayProfileError.EndpointNotSecure)
        }
        if (uri.rawAuthority?.contains('@') == true) {
            return fail(RelayProfileError.EndpointCarriesCredential)
        }
        val host = uri.host
        if (host.isNullOrBlank()) return fail(RelayProfileError.EndpointMalformed)
        if (uri.rawFragment != null) return fail(RelayProfileError.EndpointMalformed)
        uri.rawQuery?.let { query ->
            val carriesCredential = query.split('&').any { pair ->
                val rawKey = pair.substringBefore('=')
                val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }
                    .getOrDefault(rawKey)
                    .trim()
                    .lowercase()
                key in credentialQueryKeys ||
                    key.contains("token") ||
                    key.contains("secret") ||
                    key.contains("password")
            }
            return fail(
                if (carriesCredential) {
                    RelayProfileError.EndpointCarriesCredential
                } else {
                    RelayProfileError.EndpointMalformed
                },
            )
        }
        if (bareAddress.matches(host)) return fail(RelayProfileError.EndpointBareAddress)
        val path = uri.rawPath.orEmpty()
        if (path != STANDARD_GATEWAY_PATH && path != "$STANDARD_GATEWAY_PATH/") {
            return fail(RelayProfileError.EndpointMalformed)
        }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "wss://${host.lowercase()}$port$STANDARD_GATEWAY_PATH" to null
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
                    .put("mode", profile.mode.wireName)
            profile.hermesProfile?.let { item.put("hermes_profile", it) }
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
            profile.homeClientGrant?.let { grant ->
                item.put(
                    "home_client_grant",
                    JSONObject()
                        .put("pairing_id", grant.pairingId)
                        .put("grant_id", grant.grantId),
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
                    val homeClientGrant = item.optJSONObject("home_client_grant")?.let { grant ->
                        val pairingId = grant.optString("pairing_id").trim()
                        val grantId = grant.optString("grant_id").trim()
                        if (pairingId.isNotEmpty() && grantId.isNotEmpty()) {
                            RelayHomeClientGrantRef(pairingId, grantId)
                        } else {
                            null
                        }
                    }
                    val storedMode = RelayProfileMode.fromWireName(
                        item.optString("mode").takeIf { it.isNotBlank() },
                    )
                    if (storedMode == RelayProfileMode.Standard) {
                        // Standard never holds a Home link, so any stored one is dropped.
                        return@mapNotNull RelayProfile(
                            id = id,
                            endpoint = item.optString("endpoint"),
                            clientId = item.optString("client_id"),
                            deviceId = item.optString("device_id"),
                            displayName = item.optString("display_name"),
                            mode = RelayProfileMode.Standard,
                            hermesProfile = item.optString("hermes_profile").trim()
                                .ifEmpty { RelayProfileValidator.DEFAULT_HERMES_PROFILE },
                        )
                    }
                    RelayProfile(
                        id = id,
                        endpoint = item.optString("endpoint"),
                        clientId = item.optString("client_id"),
                        deviceId = item.optString("device_id"),
                        displayName = item.optString("display_name"),
                        homeBinding = homeBinding,
                        homeAdministration = homeAdministration,
                        homeClientGrant = homeClientGrant,
                        mode = if (storedMode == RelayProfileMode.HomeBridge) {
                            RelayProfileMode.HomeBridge
                        } else {
                            RelayProfileMode.derive(homeBinding, homeClientGrant)
                        },
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

        private const val PROFILE_COLLECTION_SCHEMA_VERSION = 3
    }
}
