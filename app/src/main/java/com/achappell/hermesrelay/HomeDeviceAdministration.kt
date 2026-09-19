package com.achappell.hermesrelay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal enum class HomeAdministrationError {
    MissingProfile,
    MissingCredential,
    InvalidEndpoint,
    InvalidRequest,
    InvalidConfiguration,
    Unauthorized,
    Forbidden,
    NotFound,
    Conflict,
    RevisionConflict,
    Expired,
    Revoked,
    ServiceUnavailable,
    InvalidResponse,
    TransportUnavailable,
    SecureStorageUnavailable,
}

/** Safe, typed failure for Home administration. Response bodies are never retained. */
internal class HomeAdministrationException(
    val reason: HomeAdministrationError,
    val statusCode: Int? = null,
) : IllegalStateException(reason.name)

internal data class HomeDiscoveredDevice(
    val endpointId: String,
    val label: String,
    val type: String = "android",
)

/** Discovery is deliberately local and side-effect free until approval. */
internal object HomeDeviceDiscoveryAdapter {
    fun identifyManually(endpointId: String, label: String, type: String = "android"):
        HomeDiscoveredDevice {
        return HomeDiscoveredDevice(
            endpointId = validText(endpointId, "endpoint_id"),
            label = validText(label, "label"),
            type = validText(type, "type"),
        )
    }

    fun observe(device: HomeDiscoveredDevice): HomeDiscoveredDevice = identifyManually(
        device.endpointId,
        device.label,
        device.type,
    )

    private fun validText(value: String, field: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 128 || normalized.any(Char::isISOControl)) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        }
        return normalized
    }
}

internal data class HomeRoom(val id: String, val name: String)

internal data class HomeProfile(
    val id: String,
    val name: String,
    val available: Boolean,
)

internal data class HomeWakeMapping(
    val id: String,
    val phrase: String,
    val profileId: String,
    val active: Boolean,
)

internal data class HomeConfiguredDevice(
    val id: String,
    val name: String,
    val roomId: String,
    val priority: Int,
    val wakeClaim: Boolean,
)

internal data class HomeConfigurationSnapshot(
    val revision: Int,
    val rooms: List<HomeRoom>,
    val profiles: List<HomeProfile>,
    val wakeMappings: List<HomeWakeMapping>,
    val devices: List<HomeConfiguredDevice>,
) {
    fun requireValid(expectedRevision: Int? = null) {
        if (revision < 0 || (expectedRevision != null && revision != expectedRevision)) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
        val roomIds = rooms.map { it.id }
        val profileIds = profiles.map { it.id }
        val wakeMappingIds = wakeMappings.map { it.id }
        val deviceIds = devices.map { it.id }
        if (
            roomIds.any(String::isBlank) || roomIds.size != roomIds.toSet().size ||
            profileIds.any(String::isBlank) || profileIds.size != profileIds.toSet().size ||
            wakeMappingIds.any(String::isBlank) ||
            wakeMappingIds.size != wakeMappingIds.toSet().size ||
            deviceIds.any(String::isBlank) || deviceIds.size != deviceIds.toSet().size
        ) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
        }
        val activePhrases = mutableSetOf<String>()
        wakeMappings.forEach { mapping ->
            if (mapping.id.isBlank() || mapping.phrase.isBlank() || mapping.profileId !in profileIds) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
            }
            if (mapping.active && !activePhrases.add(normalizePhrase(mapping.phrase))) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
            }
        }
        val priorities = mutableMapOf<String, MutableSet<Int>>()
        devices.forEach { device ->
            if (
                device.id.isBlank() || device.name.isBlank() || device.roomId !in roomIds ||
                device.priority < 1 ||
                !priorities.getOrPut(device.roomId) { mutableSetOf() }.add(device.priority)
            ) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidConfiguration)
            }
        }
    }

    fun candidateJson(): JSONObject = JSONObject()
        .put("rooms", rooms.toJsonArray { room ->
            JSONObject().put("id", room.id).put("name", room.name)
        })
        .put("profiles", profiles.toJsonArray { profile ->
            JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("available", profile.available)
        })
        .put("wake_mappings", wakeMappings.toJsonArray { mapping ->
            JSONObject()
                .put("id", mapping.id)
                .put("phrase", mapping.phrase)
                .put("profile_id", mapping.profileId)
                .put("active", mapping.active)
        })
        .put("devices", devices.toJsonArray { device ->
            JSONObject()
                .put("id", device.id)
                .put("name", device.name)
                .put("room_id", device.roomId)
                .put("priority", device.priority)
                .put("capabilities", JSONObject().put("wake_claim", device.wakeClaim))
        })

    companion object {
        fun fromSnapshot(snapshot: JSONObject): HomeConfigurationSnapshot {
            return HomeConfigurationSnapshot(
                revision = snapshot.getInt("revision"),
                rooms = snapshot.objectArray("rooms") { item ->
                    HomeRoom(item.requiredText("id"), item.requiredText("name"))
                },
                profiles = snapshot.objectArray("profiles") { item ->
                    HomeProfile(
                        id = item.requiredText("id"),
                        name = item.requiredText("name"),
                        available = item.getBoolean("available"),
                    )
                },
                wakeMappings = snapshot.objectArray("wake_mappings") { item ->
                    HomeWakeMapping(
                        id = item.requiredText("id"),
                        phrase = item.requiredText("phrase"),
                        profileId = item.requiredText("profile_id"),
                        active = item.getBoolean("active"),
                    )
                },
                devices = snapshot.objectArray("devices") { item ->
                    val capabilities = item.getJSONObject("capabilities")
                    HomeConfiguredDevice(
                        id = item.requiredText("id"),
                        name = item.requiredText("name"),
                        roomId = item.requiredText("room_id"),
                        priority = item.getInt("priority"),
                        wakeClaim = capabilities.getBoolean("wake_claim"),
                    )
                },
            ).also { it.requireValid() }
        }
    }
}

internal data class HomeDeviceWakeMapping(val id: String, val phrase: String)

internal data class HomeDeviceConfigurationSnapshot(
    val revision: Int,
    val wakeMappings: List<HomeDeviceWakeMapping>,
)

internal data class HomeCredentialScope(
    val rooms: List<String>,
    val capabilities: List<String>,
    val wakeMappings: List<String> = emptyList(),
)

internal data class HomeProfileMapping(val profileId: String, val label: String)

internal data class HomeEnrollmentRequest(
    val requestId: String,
    val offerId: String,
    val endpointId: String,
    val label: String,
    val type: String,
    val requestedScope: HomeCredentialScope,
    val requestedProfileMappings: List<HomeProfileMapping>,
    val secureStorage: String,
    val confirmationCode: String,
    val expiresAt: Double,
    val status: String,
    val approvedScope: HomeCredentialScope? = null,
)

/** Enrollment codes are secret material for one transition and are redacted in logs. */
internal class HomeEnrollmentOffer(
    val offerId: String,
    val enrollmentCode: String,
    val expiresAt: Double,
) {
    override fun toString() = "HomeEnrollmentOffer(offerId=$offerId, enrollmentCode=<redacted>)"
}

/** Device credential material is never allowed into a generated data-class toString. */
internal class HomeDeviceCredentialMaterial(
    val deviceId: String,
    val credential: String,
    val generation: Int,
    val expiresAt: Double,
    val scope: HomeCredentialScope,
) {
    override fun toString() =
        "HomeDeviceCredentialMaterial(deviceId=$deviceId, generation=$generation, credential=<redacted>)"
}

internal data class HomeEnrollmentSubmission(
    val requestId: String,
    val confirmationCode: String,
    val expiresAt: Double,
)

internal interface HomeDeviceAdministrationClient {
    fun createOffer(expiresInSeconds: Int = 300): HomeEnrollmentOffer
    fun submitEnrollmentRequest(
        offerCode: String,
        device: HomeDiscoveredDevice,
        requestedRooms: List<String>,
        requestedCapabilities: List<String>,
        requestedProfileMappings: List<HomeProfileMapping> = emptyList(),
    ): HomeEnrollmentSubmission

    fun listEnrollmentRequests(): List<HomeEnrollmentRequest>
    fun approveEnrollmentRequest(
        requestId: String,
        scope: HomeCredentialScope,
    ): HomeEnrollmentRequest

    fun consumeEnrollment(
        requestId: String,
        offerCode: String,
    ): HomeDeviceCredentialMaterial

    fun revokeDevice(deviceId: String, reason: String? = null)
    fun renewDevice(deviceId: String, requestId: String, generation: Int): HomeDeviceCredentialMaterial
    fun rotateDevice(deviceId: String, requestId: String, generation: Int): HomeDeviceCredentialMaterial
    fun fetchConfiguration(): HomeConfigurationSnapshot
    fun publishConfiguration(
        snapshot: HomeConfigurationSnapshot,
        expectedRevision: Int,
    ): HomeConfigurationSnapshot

    fun fetchDeviceConfiguration(deviceId: String): HomeDeviceConfigurationSnapshot
}

internal data class HomeHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
) {
    override fun toString() =
        "HomeHttpRequest(method=$method, url=$url, headers=<redacted>, body=<redacted>)"
}

internal data class HomeHttpResponse(val statusCode: Int, val body: String) {
    override fun toString() = "HomeHttpResponse(statusCode=$statusCode, body=<redacted>)"
}

internal fun interface HomeHttpTransport {
    fun execute(request: HomeHttpRequest): HomeHttpResponse
}

internal class OkHttpHomeHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) : HomeHttpTransport {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun execute(request: HomeHttpRequest): HomeHttpResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(jsonMediaType)
        when (request.method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: "".toRequestBody(jsonMediaType))
            "PUT" -> builder.put(body ?: "".toRequestBody(jsonMediaType))
            else -> throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        }
        return client.newCall(builder.build()).execute().use { response ->
            HomeHttpResponse(response.code, response.body?.string().orEmpty())
        }
    }
}

internal class OkHttpHomeDeviceAdministration(
    private val approvedRoute: String,
    private val adminCredential: String?,
    private val deviceCredential: String?,
    private val transport: HomeHttpTransport = OkHttpHomeHttpTransport(),
) : HomeDeviceAdministrationClient {
    private enum class Auth { None, Admin, Device }

    override fun createOffer(expiresInSeconds: Int): HomeEnrollmentOffer {
        if (expiresInSeconds !in 1..300) invalidRequest()
        val body = JSONObject().put("schema", 1).put("expires_in_seconds", expiresInSeconds)
        val response = send("POST", "/api/v1/enrollment/offers", Auth.Admin, body)
        return parse(response) {
            HomeEnrollmentOffer(
                offerId = it.requiredText("offer_id"),
                enrollmentCode = it.requiredText("enrollment_code"),
                expiresAt = it.getDouble("expires_at"),
            )
        }
    }

    override fun submitEnrollmentRequest(
        offerCode: String,
        device: HomeDiscoveredDevice,
        requestedRooms: List<String>,
        requestedCapabilities: List<String>,
        requestedProfileMappings: List<HomeProfileMapping>,
    ): HomeEnrollmentSubmission {
        if (offerCode.isBlank()) invalidRequest()
        val mappings = JSONArray().apply {
            requestedProfileMappings.forEach { mapping ->
                put(JSONObject().put("profile_id", mapping.profileId).put("label", mapping.label))
            }
        }
        val body = JSONObject()
            .put("schema", 1)
            .put("enrollment_code", offerCode)
            .put("endpoint_id", device.endpointId)
            .put("label", device.label)
            .put("type", device.type)
            .put("requested_rooms", JSONArray(requestedRooms))
            .put("requested_capabilities", JSONArray(requestedCapabilities))
            .put("requested_profile_mappings", mappings)
            .put("secure_storage", "platform_secure_store")
        val response = send("POST", "/api/v1/enrollment/requests", Auth.None, body)
        return parse(response) {
            HomeEnrollmentSubmission(
                requestId = it.requiredText("request_id"),
                confirmationCode = it.requiredText("confirmation_code"),
                expiresAt = it.getDouble("expires_at"),
            )
        }
    }

    override fun listEnrollmentRequests(): List<HomeEnrollmentRequest> {
        val response = send("GET", "/api/v1/enrollment/requests", Auth.Admin)
        return parse(response) { root ->
            root.objectArray("requests") { request -> request.toEnrollmentRequest() }
        }
    }

    override fun approveEnrollmentRequest(
        requestId: String,
        scope: HomeCredentialScope,
    ): HomeEnrollmentRequest {
        if (requestId.isBlank()) invalidRequest()
        val grant = JSONObject()
            .put("mode", "selected")
            .put("ids", JSONArray(scope.wakeMappings))
        val body = JSONObject()
            .put("schema", 1)
            .put(
                "scope",
                JSONObject()
                    .put("rooms", JSONArray(scope.rooms))
                    .put("capabilities", JSONArray(scope.capabilities))
                    .put("wake_mapping_grant", grant),
            )
        val response = send(
            "POST",
            "/api/v1/enrollment/requests/${encodePathSegment(requestId)}/approve",
            Auth.Admin,
            body,
        )
        return parse(response) { it.getJSONObject("request").toEnrollmentRequest() }
    }

    override fun consumeEnrollment(
        requestId: String,
        offerCode: String,
    ): HomeDeviceCredentialMaterial {
        if (requestId.isBlank() || offerCode.isBlank()) invalidRequest()
        val body = JSONObject()
            .put("schema", 1)
            .put("enrollment_code", offerCode)
            .put("secure_storage", "platform_secure_store")
        val response = send(
            "POST",
            "/api/v1/enrollment/requests/${encodePathSegment(requestId)}/consume",
            Auth.None,
            body,
        )
        return parse(response) { it.toCredentialMaterial() }
    }

    override fun revokeDevice(deviceId: String, reason: String?) {
        val body = JSONObject().put("schema", 1)
        reason?.let { body.put("reason", it) }
        send(
            "POST",
            "/api/v1/devices/${encodePathSegment(deviceId)}/revoke",
            Auth.Admin,
            body,
        )
    }

    override fun renewDevice(
        deviceId: String,
        requestId: String,
        generation: Int,
    ): HomeDeviceCredentialMaterial = renewOrRotate(
        deviceId,
        requestId,
        generation,
        Auth.Device,
        "renew",
    )

    override fun rotateDevice(
        deviceId: String,
        requestId: String,
        generation: Int,
    ): HomeDeviceCredentialMaterial = renewOrRotate(
        deviceId,
        requestId,
        generation,
        Auth.Admin,
        "rotate",
    )

    private fun renewOrRotate(
        deviceId: String,
        requestId: String,
        generation: Int,
        auth: Auth,
        action: String,
    ): HomeDeviceCredentialMaterial {
        if (generation < 0 || requestId.isBlank()) invalidRequest()
        val body = JSONObject()
            .put("schema", 1)
            .put("request_id", requestId)
            .put("generation", generation)
        val response = send(
            "POST",
            "/api/v1/devices/${encodePathSegment(deviceId)}/credentials/$action",
            auth,
            body,
        )
        return parse(response) { it.toCredentialMaterial() }
    }

    override fun fetchConfiguration(): HomeConfigurationSnapshot {
        val response = send("GET", "/api/v1/configuration", Auth.Admin)
        return parse(response) { it.getJSONObject("snapshot").let(HomeConfigurationSnapshot::fromSnapshot) }
    }

    override fun publishConfiguration(
        snapshot: HomeConfigurationSnapshot,
        expectedRevision: Int,
    ): HomeConfigurationSnapshot {
        snapshot.requireValid(expectedRevision)
        val body = JSONObject()
            .put("schema", 1)
            .put("expected_revision", expectedRevision)
            .put("snapshot", snapshot.candidateJson())
        val response = send("PUT", "/api/v1/configuration", Auth.Admin, body)
        val published = parse(response) {
            it.getJSONObject("snapshot").let(HomeConfigurationSnapshot::fromSnapshot)
        }
        if (published.revision <= expectedRevision) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
        }
        return published
    }

    override fun fetchDeviceConfiguration(deviceId: String): HomeDeviceConfigurationSnapshot {
        val response = send(
            "GET",
            "/api/v1/devices/${encodePathSegment(deviceId)}/configuration",
            Auth.Device,
        )
        return parse(response) {
            val snapshot = it.getJSONObject("snapshot")
            HomeDeviceConfigurationSnapshot(
                revision = snapshot.getInt("revision"),
                wakeMappings = snapshot.objectArray("wake_mappings") { mapping ->
                    HomeDeviceWakeMapping(
                        id = mapping.requiredText("id"),
                        phrase = mapping.requiredText("phrase"),
                    )
                },
            )
        }
    }

    private fun send(
        method: String,
        path: String,
        auth: Auth,
        body: JSONObject? = null,
    ): JSONObject {
        val headers = mutableMapOf("Accept" to "application/json")
        when (auth) {
            Auth.None -> Unit
            Auth.Admin -> headers["Authorization"] = "Bearer ${credential(adminCredential)}"
            Auth.Device -> headers["Authorization"] = "Device ${credential(deviceCredential)}"
        }
        body?.let {
            headers["Content-Type"] = "application/json"
        }
        val response = try {
            transport.execute(
                HomeHttpRequest(
                    method = method,
                    url = HomeRoute.endpoint(approvedRoute, path),
                    headers = headers,
                    body = body?.toString(),
                ),
            )
        } catch (error: HomeAdministrationException) {
            throw error
        } catch (_: Exception) {
            throw HomeAdministrationException(HomeAdministrationError.TransportUnavailable)
        }
        if (response.statusCode !in 200..299) throw mapError(response)
        if (response.statusCode == 204) return JSONObject().put("schema", 1)
        return try {
            JSONObject(response.body)
        } catch (_: Exception) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
        }
    }

    private fun credential(value: String?): String {
        if (value.isNullOrBlank()) {
            throw HomeAdministrationException(HomeAdministrationError.MissingCredential)
        }
        return value.trim()
    }

    private fun mapError(response: HomeHttpResponse): HomeAdministrationException {
        val code = runCatching {
            JSONObject(response.body).getJSONObject("error").getString("code")
        }.getOrNull()
        val reason = when (code) {
            "unauthorized" -> HomeAdministrationError.Unauthorized
            "forbidden" -> HomeAdministrationError.Forbidden
            "not_found" -> HomeAdministrationError.NotFound
            "revision_conflict" -> HomeAdministrationError.RevisionConflict
            "conflict" -> HomeAdministrationError.Conflict
            "expired_or_consumed" -> HomeAdministrationError.Expired
            "revoked", "endpoint_revoked" -> HomeAdministrationError.Revoked
            "invalid_request" -> HomeAdministrationError.InvalidRequest
            "service_unavailable" -> HomeAdministrationError.ServiceUnavailable
            else -> when (response.statusCode) {
                401 -> HomeAdministrationError.Unauthorized
                403 -> HomeAdministrationError.Forbidden
                404 -> HomeAdministrationError.NotFound
                409 -> HomeAdministrationError.RevisionConflict
                410 -> HomeAdministrationError.Expired
                400, 422 -> HomeAdministrationError.InvalidRequest
                408, 429, 500, 502, 503, 504 -> HomeAdministrationError.ServiceUnavailable
                else -> HomeAdministrationError.InvalidResponse
            }
        }
        return HomeAdministrationException(reason, response.statusCode)
    }

    private fun <T> parse(response: JSONObject, parser: (JSONObject) -> T): T {
        return try {
            if (response.optInt("schema", 0) != 1) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
            }
            parser(response)
        } catch (error: HomeAdministrationException) {
            throw error
        } catch (_: Exception) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
        }
    }

    private fun invalidRequest(): Nothing =
        throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
}

internal data class HomeAdministrationState(
    val phase: RelayHomeAdministrationPhase? = null,
    val discoveredDevice: HomeDiscoveredDevice? = null,
    val requestId: String? = null,
    val deviceId: String? = null,
    val generation: Int? = null,
    val credentialExpiresAt: Double? = null,
    val configuration: HomeConfigurationSnapshot? = null,
    val error: HomeAdministrationError? = null,
)

/** Coordinates the full lifecycle while leaving Profile identity and Home authority intact. */
internal class HomeDeviceAdministrationController(
    private val relayConfiguration: RelayConfigurationController,
    private val credentials: RelayCredentialStore,
    private val clientFactory: (
        route: String,
        adminCredential: String?,
        deviceCredential: String?,
    ) -> HomeDeviceAdministrationClient = { route, admin, device ->
        OkHttpHomeDeviceAdministration(route, admin, device)
    },
    private val nowEpochSeconds: () -> Double = {
        System.currentTimeMillis() / 1000.0
    },
) {
    private var boundProfileId: String? = relayConfiguration.collection.selectedId

    var state: HomeAdministrationState = initialState()
        private set

    fun synchronizeSelectedProfile() {
        val selectedId = relayConfiguration.collection.selectedId
        if (selectedId == boundProfileId) return
        boundProfileId = selectedId
        state = initialState()
    }

    private fun initialState(): HomeAdministrationState {
        val profile = relayConfiguration.collection.selected ?: return HomeAdministrationState()
        val administration = profile.homeAdministration ?: return HomeAdministrationState()
        val expired = administration.credentialExpiresAt?.let { it <= nowEpochSeconds() } == true
        val missingDeviceCredential = administration.phase == RelayHomeAdministrationPhase.Ready &&
            !credentials.hasReadableHomeCredential(profile.id)
        val phase = when {
            expired -> RelayHomeAdministrationPhase.Expired
            missingDeviceCredential -> RelayHomeAdministrationPhase.Unavailable
            else -> administration.phase
        }
        return HomeAdministrationState(
            phase = phase,
            requestId = administration.requestId,
            deviceId = administration.deviceId,
            generation = administration.generation,
            credentialExpiresAt = administration.credentialExpiresAt,
            error = when {
                expired -> HomeAdministrationError.Expired
                missingDeviceCredential -> HomeAdministrationError.SecureStorageUnavailable
                else -> administration.lastError?.let { name ->
                    runCatching { HomeAdministrationError.valueOf(name) }.getOrNull()
                }
            },
        )
    }

    fun discover(endpointId: String, label: String, type: String = "android"):
        HomeDiscoveredDevice {
        synchronizeSelectedProfile()
        val device = HomeDeviceDiscoveryAdapter.identifyManually(endpointId, label, type)
        state = state.copy(
            phase = RelayHomeAdministrationPhase.Discovered,
            discoveredDevice = device,
            requestId = null,
            deviceId = null,
            generation = null,
            credentialExpiresAt = null,
            configuration = null,
            error = null,
        )
        return device
    }

    fun saveAdminCredential(credential: String): Boolean {
        synchronizeSelectedProfile()
        val profile = selectedProfile() ?: return false
        val saved = credentials.putHomeAdminCredential(profile.id, credential)
        if (!saved) {
            state = state.copy(error = HomeAdministrationError.SecureStorageUnavailable)
        }
        return saved
    }

    fun createOffer(expiresInSeconds: Int = 300): HomeEnrollmentOffer = runAdmin {
        client().createOffer(expiresInSeconds)
    }

    fun submitEnrollmentRequest(
        offerCode: String,
        requestedRooms: List<String>,
        requestedCapabilities: List<String>,
        requestedProfileMappings: List<HomeProfileMapping> = emptyList(),
    ): HomeEnrollmentSubmission = runAdmin {
        val device = state.discoveredDevice
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        client().submitEnrollmentRequest(
            offerCode,
            device,
            requestedRooms,
            requestedCapabilities,
            requestedProfileMappings,
        ).also { submission ->
            state = state.copy(
                phase = RelayHomeAdministrationPhase.PendingApproval,
                requestId = submission.requestId,
                error = null,
            )
            persist(
                phase = RelayHomeAdministrationPhase.PendingApproval,
                requestId = submission.requestId,
            )
        }
    }

    fun listEnrollmentRequests(): List<HomeEnrollmentRequest> = runAdmin {
        client().listEnrollmentRequests()
    }

    fun approveEnrollmentRequest(
        requestId: String = state.requestId
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest),
        scope: HomeCredentialScope,
    ): HomeEnrollmentRequest = runAdmin {
        client().approveEnrollmentRequest(requestId, scope).also { approved ->
            if (
                approved.requestId != requestId ||
                approved.status.lowercase() != "approved" ||
                approved.approvedScope == null
            ) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
            }
            state = state.copy(
                phase = RelayHomeAdministrationPhase.Approved,
                requestId = requestId,
                error = null,
            )
            persist(
                phase = RelayHomeAdministrationPhase.Approved,
                requestId = requestId,
            )
        }
    }

    fun consumeEnrollment(
        offerCode: String,
        requestId: String = state.requestId
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest),
    ): HomeDeviceCredentialMaterial = runAdmin {
        val material = client().consumeEnrollment(requestId, offerCode)
        requireUsableCredentialMaterial(material)
        val profile = selectedProfile()
            ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
        if (
            !relayConfiguration.enrollHomeDevice(
                profileId = profile.id,
                deviceId = material.deviceId,
                credential = material.credential,
                generation = material.generation,
                requestId = requestId,
                credentialExpiresAt = material.expiresAt,
            )
        ) {
            throw HomeAdministrationException(HomeAdministrationError.SecureStorageUnavailable)
        }
        state = state.copy(
            phase = RelayHomeAdministrationPhase.Approved,
            requestId = requestId,
            deviceId = material.deviceId,
            generation = material.generation,
            credentialExpiresAt = material.expiresAt,
            error = null,
        )
        material
    }

    fun fetchConfiguration(): HomeConfigurationSnapshot = runAdmin {
        client().fetchConfiguration().also { snapshot ->
            state = state.copy(
                phase = RelayHomeAdministrationPhase.SetupPending,
                configuration = snapshot,
                error = null,
            )
            persist(
                phase = RelayHomeAdministrationPhase.SetupPending,
                revision = snapshot.revision,
            )
        }
    }

    fun publishConfiguration(snapshot: HomeConfigurationSnapshot): HomeConfigurationSnapshot =
        runAdmin {
            val profile = selectedProfile()
                ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
            val administration = profile.homeAdministration
                ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
            if (
                state.phase == RelayHomeAdministrationPhase.Revoked ||
                state.phase == RelayHomeAdministrationPhase.Expired ||
                !credentials.hasReadableHomeCredential(profile.id)
            ) {
                throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
            }
            val deviceId = administration.deviceId
                ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
            val published = client().publishConfiguration(snapshot, snapshot.revision)
            val verified = client().fetchDeviceConfiguration(deviceId)
            if (
                verified.revision != published.revision ||
                verified.wakeMappings.any { mapping ->
                    published.wakeMappings.firstOrNull { it.id == mapping.id }?.phrase != mapping.phrase
                } ||
                verified.wakeMappings.map { it.id }.size != verified.wakeMappings.map { it.id }.toSet().size
            ) {
                throw HomeAdministrationException(HomeAdministrationError.RevisionConflict)
            }
            state = state.copy(
                phase = RelayHomeAdministrationPhase.Ready,
                configuration = published,
                deviceId = deviceId,
                generation = administration.generation,
                credentialExpiresAt = administration.credentialExpiresAt,
                error = null,
            )
            persist(
                phase = RelayHomeAdministrationPhase.Ready,
                revision = published.revision,
            )
            published
        }

    fun revokeDevice(reason: String? = null) = runAdmin {
        val deviceId = administrationDeviceId()
        client().revokeDevice(deviceId, reason)
        if (!relayConfiguration.revokeHomeDevice(
                selectedProfileId(),
                RelayHomeAdministrationPhase.Revoked,
            )
        ) {
            throw HomeAdministrationException(HomeAdministrationError.SecureStorageUnavailable)
        }
        state = state.copy(
            phase = RelayHomeAdministrationPhase.Revoked,
            credentialExpiresAt = null,
            error = null,
        )
    }

    fun renewDevice(): HomeDeviceCredentialMaterial = runAdmin {
        val profile = selectedProfile()
            ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
        val administration = profile.homeAdministration
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        val material = client().renewDevice(
            administration.deviceId ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
            administration.requestId ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
            administration.generation ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
        )
        acceptReplacement(material)
    }

    fun reEnrollDevice(): HomeDeviceCredentialMaterial = runAdmin {
        val profile = selectedProfile()
            ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
        val administration = profile.homeAdministration
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        val material = client().rotateDevice(
            administration.deviceId ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
            administration.requestId ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
            administration.generation ?: throw HomeAdministrationException(
                HomeAdministrationError.InvalidRequest,
            ),
        )
        acceptReplacement(material)
    }

    private fun acceptReplacement(material: HomeDeviceCredentialMaterial): HomeDeviceCredentialMaterial {
        requireUsableCredentialMaterial(material)
        val profile = selectedProfile()
            ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
        if (
            !relayConfiguration.enrollHomeDevice(
                profile.id,
                material.deviceId,
                material.credential,
                material.generation,
                profile.homeAdministration?.requestId,
                material.expiresAt,
            )
        ) {
            throw HomeAdministrationException(HomeAdministrationError.SecureStorageUnavailable)
        }
        state = state.copy(
            phase = RelayHomeAdministrationPhase.SetupPending,
            deviceId = material.deviceId,
            generation = material.generation,
            credentialExpiresAt = material.expiresAt,
            error = null,
        )
        persist(RelayHomeAdministrationPhase.SetupPending)
        return material
    }

    private fun selectedProfile(): RelayProfile? {
        if (relayConfiguration.collection.selectedId != boundProfileId) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)
        }
        return relayConfiguration.collection.selected
    }

    private fun selectedProfileId(): String = selectedProfile()?.id
        ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)

    private fun administrationDeviceId(): String = selectedProfile()?.homeAdministration?.deviceId
        ?: throw HomeAdministrationException(HomeAdministrationError.InvalidRequest)

    private fun client(): HomeDeviceAdministrationClient {
        val profile = selectedProfile()
            ?: throw HomeAdministrationException(HomeAdministrationError.MissingProfile)
        val route = profile.homeBinding?.approvedRoute
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidEndpoint)
        return clientFactory(
            route,
            credentials.readHomeAdminCredential(profile.id),
            credentials.readHomeCredential(profile.id),
        )
    }

    private fun persist(
        phase: RelayHomeAdministrationPhase,
        requestId: String? = state.requestId,
        revision: Int? = state.configuration?.revision,
    ): Boolean {
        val profile = selectedProfile() ?: return false
        val current = profile.homeAdministration
        if (!relayConfiguration.updateHomeAdministration(
            profile.id,
            RelayHomeAdministration(
                phase = phase,
                deviceId = state.deviceId ?: current?.deviceId,
                generation = state.generation ?: current?.generation,
                configurationRevision = revision,
                requestId = requestId,
                credentialExpiresAt = state.credentialExpiresAt ?: current?.credentialExpiresAt,
                lastError = state.error?.name,
            ),
        )) {
            throw HomeAdministrationException(HomeAdministrationError.SecureStorageUnavailable)
        }
        return true
    }

    private fun requireUsableCredentialMaterial(material: HomeDeviceCredentialMaterial) {
        if (material.expiresAt <= nowEpochSeconds()) {
            throw HomeAdministrationException(HomeAdministrationError.Expired)
        }
    }

    private fun <T> runAdmin(action: () -> T): T {
        synchronizeSelectedProfile()
        return try {
            action()
        } catch (error: HomeAdministrationException) {
            val phase = when (error.reason) {
                HomeAdministrationError.Expired -> RelayHomeAdministrationPhase.Expired
                HomeAdministrationError.Revoked -> RelayHomeAdministrationPhase.Revoked
                HomeAdministrationError.RevisionConflict -> RelayHomeAdministrationPhase.StaleRevision
                else -> RelayHomeAdministrationPhase.Unavailable
            }
            state = state.copy(phase = phase, error = error.reason)
            runCatching { persist(phase) }
            throw error
        }
    }
}

private object HomeRoute {
    fun endpoint(route: String, path: String): String {
        if (RelayProfileValidator.validateApprovedHomeRoute(route) != null) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidEndpoint)
        }
        val uri = runCatching { URI(route.trim()) }.getOrNull()
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidEndpoint)
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() }
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidEndpoint)
        val scheme = if (uri.scheme.equals("wss", ignoreCase = true)) "https" else "http"
        return "$scheme://$authority$path"
    }
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

private fun normalizePhrase(value: String): String = value.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

private fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject): JSONArray = JSONArray().also { array ->
    forEach { array.put(encode(it)) }
}

private fun JSONObject.requiredText(name: String): String {
    val value = optString(name, "").trim()
    if (value.isEmpty()) throw IllegalArgumentException("missing $name")
    return value
}

private fun <T> JSONObject.objectArray(name: String, parse: (JSONObject) -> T): List<T> {
    val array = optJSONArray(name) ?: throw IllegalArgumentException("missing $name")
    return (0 until array.length()).map { index ->
        parse(array.optJSONObject(index) ?: throw IllegalArgumentException("invalid $name[$index]"))
    }
}

private fun JSONObject.toCredentialScope(): HomeCredentialScope {
    return HomeCredentialScope(
        rooms = stringArray("rooms"),
        capabilities = stringArray("capabilities"),
        wakeMappings = stringArray("wake_mappings"),
    )
}

private fun JSONObject.toEnrollmentRequest(): HomeEnrollmentRequest = HomeEnrollmentRequest(
    requestId = requiredText("request_id"),
    offerId = requiredText("offer_id"),
    endpointId = requiredText("endpoint_id"),
    label = requiredText("label"),
    type = requiredText("type"),
    requestedScope = getJSONObject("requested_scope").toCredentialScope(),
    requestedProfileMappings = objectArray("requested_profile_mappings") { mapping ->
        HomeProfileMapping(mapping.requiredText("profile_id"), mapping.requiredText("label"))
    },
    secureStorage = requiredText("secure_storage"),
    confirmationCode = requiredText("confirmation_code"),
    expiresAt = getDouble("expires_at"),
    status = requiredText("status"),
    approvedScope = optJSONObject("approved_scope")?.toCredentialScope(),
)

private fun JSONObject.toCredentialMaterial(): HomeDeviceCredentialMaterial = HomeDeviceCredentialMaterial(
    deviceId = requiredText("device_id"),
    credential = requiredText("credential").also {
        if (!HomeCredentialValidator.isValid(it)) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
        }
    },
    generation = getInt("generation"),
    expiresAt = getDouble("expires_at"),
    scope = getJSONObject("scope").toCredentialScope(),
)

private fun JSONObject.stringArray(name: String): List<String> {
    val array = optJSONArray(name) ?: throw IllegalArgumentException("missing $name")
    return (0 until array.length()).map { index ->
        array.optString(index, "").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("invalid $name[$index]")
    }
}
