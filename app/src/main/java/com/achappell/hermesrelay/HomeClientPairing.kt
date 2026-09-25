package com.achappell.hermesrelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/*
 * ANDROID-HOME-02 slice 1: pair this phone as a HOME-NW-17 personal client and
 * admit each conversation through a fresh client claim.
 *
 * Secrets never leave this file except through [RelayCredentialStore]: the
 * enrollment code, Device credential and conversation handle are held in
 * redacting types, and the persisted pairing record carries only non-secret
 * identities (`device_id`, `grant_id`, generation, expiry, route pin).
 */

/** Where to pair and with which single-use code. The code is secret. */
internal class HomePairingTarget(val homeUrl: String, val code: String) {
    val host: String get() = URI(homeUrl).host

    override fun equals(other: Any?) =
        other is HomePairingTarget && other.homeUrl == homeUrl && other.code == code

    override fun hashCode() = homeUrl.hashCode() * 31 + code.hashCode()

    override fun toString() = "HomePairingTarget(homeUrl=$homeUrl, code=<redacted>)"
}

internal enum class HomePairingInputError {
    NotPairingLink,
    HomeAddressInvalid,
    CodeInvalid,
}

internal sealed interface HomePairingInput {
    data class Parsed(val target: HomePairingTarget) : HomePairingInput

    data class Invalid(val error: HomePairingInputError) : HomePairingInput
}

internal object HomePairingLink {
    const val SCHEME = "hermes-home"
    private const val MIN_CODE_LENGTH = 4
    private const val MAX_CODE_LENGTH = 32
    private val codeCharacters = Regex("[A-Z0-9]+")
    private val bareAddress = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[?[0-9a-fA-F:]+]?$")

    /** Parses `hermes-home://pair?home=<https base>&code=<code>`. */
    fun parse(link: String): HomePairingInput {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
            ?: return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
        if (
            !uri.scheme.equals(SCHEME, ignoreCase = true) ||
            !uri.rawAuthority.equals("pair", ignoreCase = true) ||
            uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" } ||
            uri.rawFragment != null
        ) {
            return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
        }
        val parameters = mutableMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) {
                return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
            }
            val name = decode(pair.substring(0, separator))
                ?: return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
            val value = decode(pair.substring(separator + 1))
                ?: return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
            // A repeated parameter is ambiguous about which Home was meant.
            if (parameters.put(name, value) != null) {
                return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
            }
        }
        val home = parameters["home"]
            ?: return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
        val code = parameters["code"]
            ?: return HomePairingInput.Invalid(HomePairingInputError.NotPairingLink)
        return fromTyped(code, home)
    }

    /** Typed entry: the short code (any case, dash optional) and the Home address. */
    fun fromTyped(code: String, homeAddress: String): HomePairingInput {
        val home = normalizeHomeUrl(homeAddress)
            ?: return HomePairingInput.Invalid(HomePairingInputError.HomeAddressInvalid)
        val normalizedCode = normalizeCode(code)
            ?: return HomePairingInput.Invalid(HomePairingInputError.CodeInvalid)
        return HomePairingInput.Parsed(HomePairingTarget(home, normalizedCode))
    }

    fun normalizeCode(code: String): String? {
        val normalized = code.trim().replace("-", "").uppercase()
        if (normalized.length !in MIN_CODE_LENGTH..MAX_CODE_LENGTH) return null
        return normalized.takeIf(codeCharacters::matches)
    }

    /**
     * Returns `https://host[:port]`, or null. Home refuses to issue a plain-http
     * or loopback link, and a bare address cannot match a certificate.
     */
    fun normalizeHomeUrl(address: String): String? {
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" }) return null
        if (bareAddress.matches(host) || host == "localhost") return null
        val port = uri.port.takeIf { it != -1 && it != 443 }
        return if (port == null) "https://$host" else "https://$host:$port"
    }

    fun bridgeRoute(homeUrl: String): String =
        "wss://${URI(homeUrl).rawAuthority}${RelayProfileValidator.APPROVED_HOME_BRIDGE_PATH}"

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
}

internal enum class HomeClientGrantStatus(val wire: String) {
    Active("active"),
    PendingOwner("pending_owner"),
    ;

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value }
    }
}

/** A Profile grant as Home shows it to a client: never the Profile ID. */
internal data class HomeClientGrant(
    val grantId: String,
    val label: String,
    val status: HomeClientGrantStatus,
    val available: Boolean,
)

/** Non-secret, persisted state of one pairing with one Home. */
internal data class HomeClientPairingRecord(
    val pairingId: String,
    val homeUrl: String,
    val deviceId: String,
    val generation: Int,
    val credentialExpiresAt: Double,
    val grants: List<HomeClientGrant>,
    val routeId: String? = null,
) {
    val credentialSlot: String get() = credentialSlot(pairingId)

    companion object {
        /** Keystore slot shared by every Profile of this pairing. */
        fun credentialSlot(pairingId: String) = "pairing:$pairingId"
    }
}

internal data class HomeClientPairings(
    val records: List<HomeClientPairingRecord> = emptyList(),
    /** Stable per-install endpoint IDs keyed by Home, so pairing again replaces. */
    val endpointIds: Map<String, String> = emptyMap(),
) {
    fun find(pairingId: String) = records.firstOrNull { it.pairingId == pairingId }

    fun forHome(homeUrl: String) = records.firstOrNull { it.homeUrl == homeUrl }

    fun upsert(record: HomeClientPairingRecord) = copy(
        records = records.filterNot { it.pairingId == record.pairingId } + record,
    )

    fun remove(pairingId: String) = copy(records = records.filterNot { it.pairingId == pairingId })

    fun toJson(): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("records", JSONArray().apply {
            records.forEach { record ->
                put(
                    JSONObject()
                        .put("pairing_id", record.pairingId)
                        .put("home_url", record.homeUrl)
                        .put("device_id", record.deviceId)
                        .put("generation", record.generation)
                        .put("credential_expires_at", record.credentialExpiresAt)
                        .putOpt("route_id", record.routeId)
                        .put("grants", JSONArray().apply {
                            record.grants.forEach { grant ->
                                put(
                                    JSONObject()
                                        .put("grant_id", grant.grantId)
                                        .put("label", grant.label)
                                        .put("status", grant.status.wire)
                                        .put("available", grant.available),
                                )
                            }
                        }),
                )
            }
        })
        .put("endpoint_ids", JSONObject(endpointIds))
        .toString()

    companion object {
        const val SCHEMA_VERSION = 1

        /** An unreadable file yields no pairings; it never affects other Profiles. */
        fun fromJson(raw: String): HomeClientPairings = runCatching {
            val root = JSONObject(raw)
            require(root.getInt("schema") == SCHEMA_VERSION)
            val records = root.getJSONArray("records").let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    HomeClientPairingRecord(
                        pairingId = item.getString("pairing_id"),
                        homeUrl = item.getString("home_url"),
                        deviceId = item.getString("device_id"),
                        generation = item.getInt("generation"),
                        credentialExpiresAt = item.getDouble("credential_expires_at"),
                        routeId = if (item.isNull("route_id")) null else item.optString("route_id"),
                        grants = item.getJSONArray("grants").toGrants(),
                    )
                }
            }
            val ids = root.optJSONObject("endpoint_ids") ?: JSONObject()
            HomeClientPairings(
                records = records,
                endpointIds = ids.keys().asSequence().associateWith(ids::getString),
            )
        }.getOrElse { HomeClientPairings() }
    }
}

internal interface HomeClientPairingStore {
    fun load(): HomeClientPairings

    fun save(pairings: HomeClientPairings): Boolean
}

internal class FileHomeClientPairingStore(private val file: File) : HomeClientPairingStore {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override fun load(): HomeClientPairings = runCatching {
        if (!file.exists()) HomeClientPairings() else HomeClientPairings.fromJson(file.readText())
    }.getOrElse { HomeClientPairings() }

    override fun save(pairings: HomeClientPairings): Boolean = runCatching {
        val serialized = pairings.toJson()
        val temporary = File("${file.absolutePath}.tmp")
        temporary.writeText(serialized)
        if (!temporary.renameTo(file)) {
            file.writeText(serialized)
            temporary.delete()
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val FILE_NAME = "home-client-pairings.json"
    }
}

internal class InMemoryHomeClientPairingStore(
    private var pairings: HomeClientPairings = HomeClientPairings(),
) : HomeClientPairingStore {
    override fun load() = pairings

    override fun save(pairings: HomeClientPairings): Boolean {
        this.pairings = pairings
        return true
    }
}

internal sealed interface HomeConsumeResult {
    class Approved(
        val material: HomeDeviceCredentialMaterial,
        val grants: List<HomeClientGrant>,
    ) : HomeConsumeResult

    data object Pending : HomeConsumeResult

    data object Rejected : HomeConsumeResult

    data object Expired : HomeConsumeResult
}

internal data class HomeClientConfiguration(
    val revision: Int,
    val grants: List<HomeClientGrant>,
)

internal sealed interface HomeClientClaimResult {
    class Granted(val conversationHandle: String) : HomeClientClaimResult {
        override fun toString() = "Granted(conversationHandle=<redacted>)"
    }

    data class Denied(val code: String) : HomeClientClaimResult
}

/** The HOME-NW-17 personal-client routes. */
internal interface HomeClientService {
    fun submit(
        target: HomePairingTarget,
        endpointId: String,
        label: String,
    ): HomeEnrollmentSubmission

    fun consume(homeUrl: String, requestId: String, code: String): HomeConsumeResult

    fun renew(
        homeUrl: String,
        credential: String,
        deviceId: String,
        requestId: String,
        generation: Int,
    ): HomeDeviceCredentialMaterial

    fun configuration(homeUrl: String, credential: String, deviceId: String): HomeClientConfiguration

    fun claim(
        homeUrl: String,
        credential: String,
        deviceId: String,
        revision: Int,
        grantId: String,
        claimId: String,
    ): HomeClientClaimResult
}

internal class HttpHomeClientService(
    private val transport: HomeHttpTransport = OkHttpHomeHttpTransport(),
) : HomeClientService {
    private class Reply(val status: Int, val body: JSONObject?) {
        val errorCode: String?
            get() = body?.optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank)
    }

    override fun submit(
        target: HomePairingTarget,
        endpointId: String,
        label: String,
    ): HomeEnrollmentSubmission {
        val body = JSONObject()
            .put("schema", 1)
            .put("enrollment_code", target.code)
            .put("endpoint_id", endpointId)
            .put("label", label)
            .put("type", "android")
            .put("requested_rooms", JSONArray())
            .put("requested_capabilities", JSONArray().put(CLIENT_CLAIM))
            .put("secure_storage", SECURE_STORAGE)
        val reply = send("POST", target.homeUrl, "/api/v1/enrollment/requests", null, body)
        val json = successBody(reply)
        return parse {
            HomeEnrollmentSubmission(
                requestId = json.text("request_id"),
                confirmationCode = json.text("confirmation_code"),
                expiresAt = json.getDouble("expires_at"),
            )
        }
    }

    override fun consume(homeUrl: String, requestId: String, code: String): HomeConsumeResult {
        val body = JSONObject()
            .put("schema", 1)
            .put("enrollment_code", code)
            .put("secure_storage", SECURE_STORAGE)
        val reply = send(
            "POST",
            homeUrl,
            "/api/v1/enrollment/requests/${segment(requestId)}/consume",
            null,
            body,
        )
        when (reply.errorCode) {
            "approval_pending" -> return HomeConsumeResult.Pending
            "rejected" -> return HomeConsumeResult.Rejected
            "expired_or_consumed" -> return HomeConsumeResult.Expired
        }
        val json = successBody(reply)
        return parse {
            HomeConsumeResult.Approved(
                material = json.toMaterial(),
                grants = json.optJSONArray("client_grants")?.toGrants().orEmpty(),
            )
        }
    }

    override fun renew(
        homeUrl: String,
        credential: String,
        deviceId: String,
        requestId: String,
        generation: Int,
    ): HomeDeviceCredentialMaterial {
        val body = JSONObject()
            .put("schema", 1)
            .put("request_id", requestId)
            .put("generation", generation)
        val reply = send(
            "POST",
            homeUrl,
            "/api/v1/devices/${segment(deviceId)}/credentials/renew",
            credential,
            body,
        )
        val json = successBody(reply)
        return parse { json.toMaterial() }
    }

    override fun configuration(
        homeUrl: String,
        credential: String,
        deviceId: String,
    ): HomeClientConfiguration {
        val reply = send(
            "GET",
            homeUrl,
            "/api/v1/devices/${segment(deviceId)}/configuration",
            credential,
            null,
        )
        val json = successBody(reply)
        return parse {
            val snapshot = json.getJSONObject("snapshot")
            HomeClientConfiguration(
                revision = snapshot.getInt("revision").also { require(it >= 0) },
                grants = snapshot.optJSONArray("client_grants")?.toGrants().orEmpty(),
            )
        }
    }

    override fun claim(
        homeUrl: String,
        credential: String,
        deviceId: String,
        revision: Int,
        grantId: String,
        claimId: String,
    ): HomeClientClaimResult {
        val body = JSONObject()
            .put("schema", 1)
            .put("claim_id", claimId)
            .put("device_id", deviceId)
            .put("configuration_revision", revision)
            .put("grant_id", grantId)
            .put("session", JSONObject().put("mode", "new"))
        val reply = send("POST", homeUrl, "/api/v1/client-claims", credential, body)
        reply.errorCode?.takeIf { it in CLAIM_DENIALS }?.let {
            return HomeClientClaimResult.Denied(it)
        }
        val json = successBody(reply)
        return parse {
            require(json.text("claim_id") == claimId)
            val decision = json.text("decision")
            if (decision != "granted") return@parse HomeClientClaimResult.Denied(decision)
            val handle = json.text("conversation_handle")
            require(RelayProfileValidator.isValidHomeConversationHandle(handle))
            HomeClientClaimResult.Granted(handle)
        }
    }

    private fun send(
        method: String,
        homeUrl: String,
        path: String,
        deviceCredential: String?,
        body: JSONObject?,
    ): Reply {
        val headers = mutableMapOf("Accept" to "application/json")
        deviceCredential?.let { headers["Authorization"] = "Device $it" }
        body?.let { headers["Content-Type"] = "application/json" }
        val url = HomePairingLink.normalizeHomeUrl(homeUrl)
            ?: throw HomeAdministrationException(HomeAdministrationError.InvalidEndpoint)
        val response = try {
            transport.execute(HomeHttpRequest(method, url + path, headers, body?.toString()))
        } catch (error: HomeAdministrationException) {
            throw error
        } catch (_: Exception) {
            throw HomeAdministrationException(HomeAdministrationError.TransportUnavailable)
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        return Reply(response.statusCode, json)
    }

    private fun successBody(reply: Reply): JSONObject {
        if (reply.status !in 200..299) {
            val reason = when (reply.errorCode) {
                "unauthorized" -> HomeAdministrationError.Unauthorized
                "forbidden" -> HomeAdministrationError.Forbidden
                "not_found" -> HomeAdministrationError.NotFound
                "conflict" -> HomeAdministrationError.Conflict
                "revoked", "endpoint_revoked" -> HomeAdministrationError.Revoked
                "invalid_request" -> HomeAdministrationError.InvalidRequest
                else -> when (reply.status) {
                    401 -> HomeAdministrationError.Unauthorized
                    403 -> HomeAdministrationError.Forbidden
                    404 -> HomeAdministrationError.NotFound
                    409 -> HomeAdministrationError.Conflict
                    410 -> HomeAdministrationError.Expired
                    400, 422 -> HomeAdministrationError.InvalidRequest
                    408, 429, 500, 502, 503, 504 -> HomeAdministrationError.ServiceUnavailable
                    else -> HomeAdministrationError.InvalidResponse
                }
            }
            throw HomeAdministrationException(reason, reply.status)
        }
        val body = reply.body
        if (body == null || body.optInt("schema", 0) != 1) {
            throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
        }
        return body
    }

    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (error: HomeAdministrationException) {
        throw error
    } catch (_: Exception) {
        throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
    }

    private fun segment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val CLIENT_CLAIM = "client_claim"
        const val SECURE_STORAGE = "platform_secure_store"
        val CLAIM_DENIALS = setOf(
            "client_claim_unavailable",
            "grant_pending",
            "stale_configuration",
            "profile_unavailable",
            "claim_limit",
            "session_unavailable",
            "session_busy",
        )
    }
}

internal sealed interface HomePairingOutcome {
    data class Paired(
        val pairingId: String,
        val addedProfileIds: List<String>,
        val pendingOwnerLabels: List<String>,
    ) : HomePairingOutcome

    data object Rejected : HomePairingOutcome

    data object Expired : HomePairingOutcome

    data object Cancelled : HomePairingOutcome

    data class Failed(val reason: HomeAdministrationError) : HomePairingOutcome
}

/**
 * Drives one pairing from submission to saved Profiles. Every call blocks and
 * belongs on a worker thread.
 */
internal class HomeClientPairingCoordinator(
    private val store: HomeClientPairingStore,
    private val credentials: RelayCredentialStore,
    private val service: HomeClientService,
    private val configuration: RelayConfigurationController,
    private val deviceLabel: String,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun submit(target: HomePairingTarget): HomeEnrollmentSubmission {
        val pairings = store.load()
        val endpointId = pairings.endpointIds[target.homeUrl] ?: idFactory().also { id ->
            if (!store.save(pairings.copy(endpointIds = pairings.endpointIds + (target.homeUrl to id)))) {
                throw HomeAdministrationException(HomeAdministrationError.SecureStorageUnavailable)
            }
        }
        return service.submit(target, endpointId, deviceLabel)
    }

    fun awaitApproval(
        target: HomePairingTarget,
        submission: HomeEnrollmentSubmission,
        isCancelled: () -> Boolean,
    ): HomePairingOutcome {
        while (true) {
            if (isCancelled()) return HomePairingOutcome.Cancelled
            val result = try {
                service.consume(target.homeUrl, submission.requestId, target.code)
            } catch (error: HomeAdministrationException) {
                return HomePairingOutcome.Failed(error.reason)
            }
            when (result) {
                is HomeConsumeResult.Approved -> return finish(target, result)
                HomeConsumeResult.Rejected -> return HomePairingOutcome.Rejected
                HomeConsumeResult.Expired -> return HomePairingOutcome.Expired
                HomeConsumeResult.Pending -> {
                    if (clock() >= submission.expiresAt) return HomePairingOutcome.Expired
                    sleeper(POLL_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun finish(
        target: HomePairingTarget,
        approved: HomeConsumeResult.Approved,
    ): HomePairingOutcome {
        val material = approved.material
        if (!HomeCredentialValidator.isValid(material.credential) || material.generation < 0) {
            return HomePairingOutcome.Failed(HomeAdministrationError.InvalidResponse)
        }
        val pairings = store.load()
        // Pairing the same Home again keeps its identity so its Profiles and
        // history stay attached; Home has already revoked the old generation.
        val existing = pairings.forHome(target.homeUrl)
        val pairingId = existing?.pairingId ?: idFactory()
        val slot = HomeClientPairingRecord.credentialSlot(pairingId)
        val previousCredential = credentials.readHomeCredential(slot)
        if (
            !credentials.putHomeCredential(slot, material.credential) ||
            credentials.readHomeCredential(slot) != material.credential
        ) {
            restore(slot, previousCredential)
            return HomePairingOutcome.Failed(HomeAdministrationError.SecureStorageUnavailable)
        }
        val record = HomeClientPairingRecord(
            pairingId = pairingId,
            homeUrl = target.homeUrl,
            deviceId = material.deviceId,
            generation = material.generation,
            credentialExpiresAt = material.expiresAt,
            grants = approved.grants,
        )
        if (!store.save(pairings.upsert(record))) {
            restore(slot, previousCredential)
            return HomePairingOutcome.Failed(HomeAdministrationError.SecureStorageUnavailable)
        }
        val added = configuration.addHomeClientProfiles(record)
            ?: return HomePairingOutcome.Failed(HomeAdministrationError.SecureStorageUnavailable)
        return HomePairingOutcome.Paired(
            pairingId = pairingId,
            addedProfileIds = added,
            pendingOwnerLabels = approved.grants
                .filter { it.status == HomeClientGrantStatus.PendingOwner }
                .map { it.label },
        )
    }

    private fun restore(slot: String, previous: String?) {
        credentials.deleteHomeCredential(slot)
        previous?.let { credentials.putHomeCredential(slot, it) }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}

internal sealed interface HomeClientClaimOutcome {
    class Claimed(val binding: RelayHomeBinding, val credential: String) : HomeClientClaimOutcome {
        override fun toString() = "Claimed(binding=<redacted>, credential=<redacted>)"
    }

    data class Unavailable(
        val message: String,
        val reason: AndroidHomeUnavailableReason,
        val retryable: Boolean = false,
    ) : HomeClientClaimOutcome
}

/**
 * Admits one conversation for a paired Profile: renew when due, read the
 * current revision, then claim a new session. A claimed handle is single-use
 * and expires unopened after 90 seconds, so this runs immediately before
 * `conversation.open`, never at load.
 */
internal class HomeClientClaimProvider(
    private val store: HomeClientPairingStore,
    private val credentials: RelayCredentialStore,
    private val service: HomeClientService,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun credentialFor(grant: RelayHomeClientGrantRef): String? =
        credentials.readHomeCredential(HomeClientPairingRecord.credentialSlot(grant.pairingId))

    fun claim(grant: RelayHomeClientGrantRef): HomeClientClaimOutcome {
        var record = store.load().find(grant.pairingId)
            ?: return unavailable(
                "This Profile's Home pairing is missing. Pair with Home again.",
                AndroidHomeUnavailableReason.MissingBinding,
            )
        var credential = credentials.readHomeCredential(record.credentialSlot)
            ?: return unavailable(
                "The Home credential cannot be read. Pair with Home again.",
                AndroidHomeUnavailableReason.InvalidCredential,
            )
        val now = clock()
        if (now >= record.credentialExpiresAt) {
            return unavailable(
                "This phone's Home pairing has expired. Pair with Home again.",
                AndroidHomeUnavailableReason.Unauthorized,
            )
        }
        if (now >= record.credentialExpiresAt - RENEWAL_WINDOW_SECONDS) {
            when (val renewed = renew(record, credential)) {
                is Renewal.Renewed -> {
                    record = renewed.record
                    credential = renewed.credential
                }
                is Renewal.Failed -> return renewed.outcome
                // Still valid: a Home that cannot renew right now must not
                // strand a credential that has days left.
                Renewal.Deferred -> Unit
            }
        }

        var refreshed = false
        while (true) {
            val configuration = try {
                service.configuration(record.homeUrl, credential, record.deviceId)
            } catch (error: HomeAdministrationException) {
                return fromError(error)
            }
            record = record.copy(grants = configuration.grants)
            store.save(store.load().upsert(record))
            val current = configuration.grants.firstOrNull { it.grantId == grant.grantId }
                ?: return denial("client_claim_unavailable")
            if (current.status == HomeClientGrantStatus.PendingOwner) return denial("grant_pending")
            if (!current.available) return denial("profile_unavailable")

            val result = try {
                service.claim(
                    homeUrl = record.homeUrl,
                    credential = credential,
                    deviceId = record.deviceId,
                    revision = configuration.revision,
                    grantId = grant.grantId,
                    claimId = "client-${idFactory()}",
                )
            } catch (error: HomeAdministrationException) {
                return fromError(error)
            }
            when (result) {
                is HomeClientClaimResult.Granted -> return HomeClientClaimOutcome.Claimed(
                    binding = RelayHomeBinding(
                        approvedRoute = HomePairingLink.bridgeRoute(record.homeUrl),
                        conversationHandle = result.conversationHandle,
                    ),
                    credential = credential,
                )
                is HomeClientClaimResult.Denied -> {
                    if (result.code == "stale_configuration" && !refreshed) {
                        refreshed = true
                        continue
                    }
                    return denial(result.code)
                }
            }
        }
    }

    /**
     * Pins the bridge route identity on the first ready after pairing. Returns
     * false when a later ready names a different route.
     */
    fun acceptRoute(grant: RelayHomeClientGrantRef, routeId: String): Boolean {
        val pairings = store.load()
        val record = pairings.find(grant.pairingId) ?: return false
        return when (record.routeId) {
            null -> store.save(pairings.upsert(record.copy(routeId = routeId)))
            else -> record.routeId == routeId
        }
    }

    private sealed interface Renewal {
        class Renewed(val record: HomeClientPairingRecord, val credential: String) : Renewal

        class Failed(val outcome: HomeClientClaimOutcome) : Renewal

        data object Deferred : Renewal
    }

    private fun renew(record: HomeClientPairingRecord, credential: String): Renewal {
        val material = try {
            service.renew(
                homeUrl = record.homeUrl,
                credential = credential,
                deviceId = record.deviceId,
                requestId = "renew-${idFactory()}",
                generation = record.generation,
            )
        } catch (error: HomeAdministrationException) {
            return when (error.reason) {
                HomeAdministrationError.Unauthorized,
                HomeAdministrationError.Revoked,
                -> Renewal.Failed(fromError(error))
                else -> Renewal.Deferred
            }
        }
        if (
            material.deviceId != record.deviceId ||
            !HomeCredentialValidator.isValid(material.credential)
        ) {
            return Renewal.Deferred
        }
        if (!credentials.putHomeCredential(record.credentialSlot, material.credential)) {
            // Home has replaced the credential; the old one is now superseded.
            return Renewal.Failed(
                unavailable(
                    "The renewed Home credential could not be stored. Pair with Home again.",
                    AndroidHomeUnavailableReason.SecureStorageUnavailable,
                ),
            )
        }
        val renewed = record.copy(
            generation = material.generation,
            credentialExpiresAt = material.expiresAt,
        )
        store.save(store.load().upsert(renewed))
        return Renewal.Renewed(renewed, material.credential)
    }

    private fun fromError(error: HomeAdministrationException): HomeClientClaimOutcome =
        when (error.reason) {
            HomeAdministrationError.Unauthorized,
            HomeAdministrationError.Revoked,
            HomeAdministrationError.Expired,
            -> unavailable(
                "Home no longer accepts this phone's pairing. Pair with Home again.",
                AndroidHomeUnavailableReason.Unauthorized,
            )
            HomeAdministrationError.TransportUnavailable,
            HomeAdministrationError.ServiceUnavailable,
            -> HomeClientClaimOutcome.Unavailable(
                "Home could not be reached to start a conversation.",
                AndroidHomeUnavailableReason.TransportUnavailable,
                retryable = true,
            )
            else -> unavailable(
                "Home returned an unexpected answer to the conversation request.",
                AndroidHomeUnavailableReason.ProtocolError,
            )
        }

    private fun denial(code: String): HomeClientClaimOutcome = when (code) {
        "grant_pending" -> unavailable(
            "This Profile is waiting for its owner to approve this phone.",
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
        )
        "profile_unavailable" -> unavailable(
            "This Profile is currently unavailable on Home.",
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
        )
        "client_claim_unavailable" -> unavailable(
            "Home no longer grants this phone access to this Profile.",
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
        )
        "claim_limit" -> unavailable(
            "This phone already has the most conversations Home allows. Close one and try again.",
            AndroidHomeUnavailableReason.AuthorizationUnavailable,
        )
        else -> unavailable(
            "Home declined the conversation request.",
            AndroidHomeUnavailableReason.RequestRejected,
        )
    }

    private fun unavailable(message: String, reason: AndroidHomeUnavailableReason) =
        HomeClientClaimOutcome.Unavailable(message, reason)

    private companion object {
        const val RENEWAL_WINDOW_SECONDS = 14 * 24 * 60 * 60.0
    }
}

private fun JSONObject.text(name: String): String =
    getString(name).trim().also { require(it.isNotEmpty()) { "missing $name" } }

private fun JSONObject.toMaterial(): HomeDeviceCredentialMaterial {
    val credential = text("credential")
    if (!HomeCredentialValidator.isValid(credential)) {
        throw HomeAdministrationException(HomeAdministrationError.InvalidResponse)
    }
    val expiresAt = getDouble("expires_at")
    require(expiresAt.isFinite() && expiresAt > 0.0)
    return HomeDeviceCredentialMaterial(
        deviceId = text("device_id"),
        credential = credential,
        generation = getInt("generation").also { require(it >= 0) },
        expiresAt = expiresAt,
        scope = getJSONObject("scope").toHomeCredentialScope(),
    )
}

private fun JSONArray.toGrants(): List<HomeClientGrant> = (0 until length()).mapNotNull { index ->
    val item = getJSONObject(index)
    // An unknown status is not a grant this client may use.
    val status = HomeClientGrantStatus.fromWire(item.getString("status")) ?: return@mapNotNull null
    HomeClientGrant(
        grantId = item.text("grant_id"),
        label = item.text("label"),
        status = status,
        available = item.getBoolean("available"),
    )
}
