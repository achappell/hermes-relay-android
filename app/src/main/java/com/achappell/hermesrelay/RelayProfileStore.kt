package com.achappell.hermesrelay

import android.content.Context
import java.io.File

/** Persists the non-secret relay profile collection in app-private storage. */
internal interface RelayProfileStore {
    fun load(): RelayProfileCollection

    fun save(collection: RelayProfileCollection): Boolean
}

internal class FileRelayProfileStore(
    private val file: File,
) : RelayProfileStore {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override fun load(): RelayProfileCollection =
        runCatching {
            if (!file.exists()) RelayProfileCollection()
            else RelayProfileCollection.fromJson(file.readText())
        }.getOrElse { RelayProfileCollection() }

    override fun save(collection: RelayProfileCollection): Boolean = runCatching {
        val serialized = collection.toJson()
        val temporary = File("${file.absolutePath}.tmp")
        temporary.writeText(serialized)
        if (!temporary.renameTo(file)) {
            // Some Android filesystems do not replace an existing destination
            // via renameTo. The temporary write still prevents a half-written
            // JSON document from becoming the normal path.
            file.writeText(serialized)
            temporary.delete()
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val FILE_NAME = "relay-profiles.json"
    }
}

internal class InMemoryRelayProfileStore(
    private var collection: RelayProfileCollection = RelayProfileCollection(),
) : RelayProfileStore {
    override fun load() = collection

    override fun save(collection: RelayProfileCollection): Boolean {
        this.collection = collection
        return true
    }
}

/** The non-secret result handed back by the approved Home pairing flow. */
internal data class RelayHomePairing(
    val approvedRoute: String,
    val deviceCredential: String,
    val conversationHandle: String,
)

internal enum class RelayHomeMigrationFailure {
    ProfileUnavailable,
    RouteInvalid,
    CredentialInvalid,
    ConversationHandleInvalid,
    SecureStorageUnavailable,
}

internal sealed interface RelayHomeMigrationResult {
    data class Migrated(val profile: RelayProfile) : RelayHomeMigrationResult

    data class Rejected(val reason: RelayHomeMigrationFailure) : RelayHomeMigrationResult
}

internal sealed interface StandardSetupResult {
    data class Saved(val profile: RelayProfile) : StandardSetupResult

    data class Invalid(
        val errors: Map<RelayProfileField, RelayProfileError>,
    ) : StandardSetupResult

    data class CheckFailed(val reason: AndroidHomeUnavailableReason) : StandardSetupResult

    data object StorageUnavailable : StandardSetupResult

    /** The selected Standard conversation has an active, uncertain or finishing turn. */
    data object Blocked : StandardSetupResult

    /** The Profile does not exist or is not a Standard Profile. */
    data object NotStandard : StandardSetupResult
}

/**
 * Owns the configured relay state for the UI.
 *
 * Deleting a profile removes its credential in the same step, so a stored
 * token can never outlive the profile that named it.
 */
internal class RelayConfigurationController(
    private val profiles: RelayProfileStore,
    private val credentials: RelayCredentialStore,
    private val history: AndroidHistoryStore? = null,
    private val homeClientPairings: HomeClientPairingStore? = null,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val standardChecker: StandardConnectionChecker? = null,
) {
    var collection: RelayProfileCollection = profiles.load()
        private set

    /**
     * True when the selected conversation has an active, uncertain or finishing
     * turn. Consulted only for a change that involves a Standard Profile (the
     * selected one or the one being selected); Home-to-Home switching keeps its
     * existing behavior.
     */
    var switchGuard: () -> Boolean = { false }

    /** True while switching away from, deleting or editing the selected Standard Profile is refused. */
    val switchBlocked: Boolean
        get() = collection.selected?.mode == RelayProfileMode.Standard && switchGuard()

    /**
     * True when selecting [targetId] is refused: the selection would change and
     * either side is a Standard Profile while the selected conversation is busy.
     */
    fun switchBlockedFor(targetId: String): Boolean {
        if (targetId == collection.selectedId) return false
        val involvesStandard = collection.selected?.mode == RelayProfileMode.Standard ||
            collection.profiles.firstOrNull { it.id == targetId }?.mode == RelayProfileMode.Standard
        return involvesStandard && switchGuard()
    }

    fun save(
        endpoint: String,
        clientId: String,
        deviceId: String,
        displayName: String,
        token: String,
    ): Map<RelayProfileField, RelayProfileError> {
        val errors = RelayProfileValidator
            .validate(endpoint, clientId, deviceId, displayName)
            .toMutableMap()
        if (token.isBlank()) {
            errors[RelayProfileField.Token] = RelayProfileError.Required
        }
        if (errors.isNotEmpty()) return errors

        val profile = RelayProfile(
            id = idFactory(),
            endpoint = endpoint.trim(),
            clientId = clientId.trim(),
            deviceId = deviceId.trim(),
            displayName = displayName.trim(),
            mode = RelayProfileMode.HomeBridge,
        )
        if (!credentials.put(profile.id, token.trim())) {
            return mapOf(RelayProfileField.Token to RelayProfileError.StorageUnavailable)
        }
        if (!update(collection.add(profile))) {
            credentials.delete(profile.id)
            return mapOf(RelayProfileField.Token to RelayProfileError.StorageUnavailable)
        }
        return emptyMap()
    }

    /** Returns true when the selection was performed; false while a busy conversation blocks the switch. */
    fun select(id: String): Boolean {
        if (switchBlockedFor(id)) return false
        return update(collection.select(id))
    }

    /**
     * Adds a Standard Profile after a verified connection check. The token is
     * stored only after the check passes and is rolled back if the Profile
     * cannot be saved. No other Profile, credential or selection changes.
     */
    fun setupStandard(
        endpoint: String,
        hermesProfile: String,
        token: String,
    ): StandardSetupResult {
        val validation = RelayProfileValidator.validateStandard(endpoint, hermesProfile)
        val errors = validation.errors.toMutableMap()
        val cleanToken = token.trim()
        if (!isUsableStandardToken(cleanToken)) {
            errors[RelayProfileField.Token] = RelayProfileError.Required
        }
        if (errors.isNotEmpty()) return StandardSetupResult.Invalid(errors)

        val identity = RelayProfile.standardHistoryKey(validation.endpoint, validation.hermesProfile)
        if (collection.profiles.any { it.mode == RelayProfileMode.Standard && it.historyKey == identity }) {
            return StandardSetupResult.Invalid(
                mapOf(RelayProfileField.Endpoint to RelayProfileError.Duplicate),
            )
        }

        verifyStandard(validation, cleanToken)?.let { return it }

        val profile = RelayProfile(
            id = idFactory(),
            endpoint = validation.endpoint,
            clientId = STANDARD_CLIENT_ID,
            deviceId = "",
            displayName = standardDisplayName(validation.endpoint, validation.hermesProfile),
            mode = RelayProfileMode.Standard,
            hermesProfile = validation.hermesProfile,
        )
        if (!credentials.putStandardCredential(profile.id, cleanToken)) {
            return StandardSetupResult.StorageUnavailable
        }
        if (!update(collection.add(profile))) {
            credentials.deleteStandardCredential(profile.id)
            return StandardSetupResult.StorageUnavailable
        }
        return StandardSetupResult.Saved(profile)
    }

    /**
     * Edits one Standard Profile. A changed endpoint or Hermes Profile is a new
     * identity: a freshly entered token is required and verified, the old token
     * is never reused, and the old identity's Local History is deleted.
     */
    fun updateStandard(
        profileId: String,
        endpoint: String,
        hermesProfile: String,
        token: String?,
    ): StandardSetupResult {
        val current = collection.profiles.firstOrNull { it.id == profileId }
            ?.takeIf { it.mode == RelayProfileMode.Standard }
            ?: return StandardSetupResult.NotStandard
        if (profileId == collection.selectedId && switchBlocked) return StandardSetupResult.Blocked

        val validation = RelayProfileValidator.validateStandard(endpoint, hermesProfile)
        val errors = validation.errors.toMutableMap()
        val cleanToken = token?.trim().orEmpty()
        val identityChanged = validation.endpoint != current.endpoint ||
            validation.hermesProfile != (current.hermesProfile ?: RelayProfileValidator.DEFAULT_HERMES_PROFILE)
        if (cleanToken.isEmpty()) {
            // The identity is only meaningful once it validates.
            if (identityChanged && errors.isEmpty()) errors[RelayProfileField.Token] = RelayProfileError.Required
        } else if (!isUsableStandardToken(cleanToken)) {
            errors[RelayProfileField.Token] = RelayProfileError.Required
        }
        if (errors.isNotEmpty()) return StandardSetupResult.Invalid(errors)

        val identity = RelayProfile.standardHistoryKey(validation.endpoint, validation.hermesProfile)
        if (
            collection.profiles.any {
                it.id != profileId && it.mode == RelayProfileMode.Standard && it.historyKey == identity
            }
        ) {
            return StandardSetupResult.Invalid(
                mapOf(RelayProfileField.Endpoint to RelayProfileError.Duplicate),
            )
        }
        if (cleanToken.isEmpty()) return StandardSetupResult.Saved(current)

        verifyStandard(validation, cleanToken)?.let { return it }

        val previousCredential = credentials.readStandardCredential(profileId)
        if (!credentials.putStandardCredential(profileId, cleanToken)) {
            return StandardSetupResult.StorageUnavailable
        }
        if (!identityChanged) return StandardSetupResult.Saved(current)

        val oldKey = current.historyKey
        val updated = current.copy(
            endpoint = validation.endpoint,
            hermesProfile = validation.hermesProfile,
            displayName = standardDisplayName(validation.endpoint, validation.hermesProfile),
        )
        if (!update(collection.upsert(updated))) {
            if (previousCredential != null) {
                credentials.putStandardCredential(profileId, previousCredential)
            } else {
                credentials.deleteStandardCredential(profileId)
            }
            return StandardSetupResult.StorageUnavailable
        }
        deleteHistoryIfUnused(oldKey)
        return StandardSetupResult.Saved(updated)
    }

    private fun verifyStandard(
        validation: StandardProfileValidation,
        token: String,
    ): StandardSetupResult? {
        val checker = standardChecker
            ?: return StandardSetupResult.CheckFailed(AndroidHomeUnavailableReason.TransportUnavailable)
        return when (val result = checker.check(validation.endpoint, validation.hermesProfile, token)) {
            StandardCheckResult.Verified -> null
            is StandardCheckResult.Failed -> StandardSetupResult.CheckFailed(result.reason)
        }
    }

    private fun isUsableStandardToken(token: String): Boolean =
        token.isNotEmpty() && token.none { it.isWhitespace() }

    private fun standardDisplayName(endpoint: String, hermesProfile: String): String {
        val host = runCatching { java.net.URI(endpoint).host }.getOrNull() ?: endpoint
        return if (hermesProfile == RelayProfileValidator.DEFAULT_HERMES_PROFILE) {
            "Standard · $host"
        } else {
            "Standard · $host · $hermesProfile"
        }
    }

    private fun deleteHistoryIfUnused(historyKey: String) {
        if (collection.profiles.none { it.historyKey == historyKey }) history?.delete(historyKey)
    }

    /**
     * Applies an approved Home pairing to an existing Profile in place.
     *
     * This is a pairing transition, not a conversion of the legacy bearer. The
     * old secret is copied into the rollback namespace before the new Home
     * secret is written, and neither is exposed through Profile JSON.
     */
    fun migrateToHome(
        profileId: String,
        pairing: RelayHomePairing,
    ): RelayHomeMigrationResult {
        val current = collection.profiles.firstOrNull { it.id == profileId }
            ?.takeIf { it.mode != RelayProfileMode.Standard }
            ?: return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.ProfileUnavailable,
            )
        if (RelayProfileValidator.validateApprovedHomeRoute(pairing.approvedRoute) != null) {
            return RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.RouteInvalid)
        }
        if (!HomeCredentialValidator.isValid(pairing.deviceCredential)) {
            return RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.CredentialInvalid)
        }
        if (!RelayProfileValidator.isValidHomeConversationHandle(pairing.conversationHandle)) {
            return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.ConversationHandleInvalid,
            )
        }

        val hasStoredLegacy = credentials.hasStoredRollbackCredential(profileId)
        if (hasStoredLegacy && !credentials.hasReadableRollbackCredential(profileId)) {
            return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.SecureStorageUnavailable,
            )
        }
        val previousHomeCredential = credentials.readHomeCredential(profileId)
        credentials.readRollbackCredential(profileId)?.let { legacy ->
            if (!credentials.putRollbackCredential(profileId, legacy)) {
                return RelayHomeMigrationResult.Rejected(
                    RelayHomeMigrationFailure.SecureStorageUnavailable,
                )
            }
        }
        if (!credentials.putHomeCredential(profileId, pairing.deviceCredential)) {
            return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.SecureStorageUnavailable,
            )
        }

        val migrated = current.copy(
            homeBinding = RelayHomeBinding(
                approvedRoute = pairing.approvedRoute.trim().trimEnd('/'),
                conversationHandle = pairing.conversationHandle,
            ),
            mode = RelayProfileMode.HomeBridge,
        )
        val next = collection.upsert(migrated)
        if (!profiles.save(next)) {
            credentials.deleteHomeCredential(profileId)
            previousHomeCredential?.let { credentials.putHomeCredential(profileId, it) }
            return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.SecureStorageUnavailable,
            )
        }
        collection = next
        return RelayHomeMigrationResult.Migrated(migrated)
    }

    /**
     * Stores a newly issued Home Device credential without inventing a
     * conversation handle. Enrollment and bridge conversation binding are
     * separate Home-owned transitions.
     */
    fun enrollHomeDevice(
        profileId: String,
        deviceId: String,
        credential: String,
        generation: Int,
        requestId: String?,
        credentialScope: HomeCredentialScope? = null,
        credentialExpiresAt: Double? = null,
    ): Boolean {
        val current = collection.profiles.firstOrNull { it.id == profileId }
            ?.takeIf { it.mode != RelayProfileMode.Standard }
            ?: return false
        if (
            !HomeCredentialValidator.isValid(credential) ||
            deviceId.isBlank() ||
            generation < 0 ||
            credentialExpiresAt?.let { !it.isFinite() || it <= 0.0 } == true
        ) {
            return false
        }
        val previousCredential = credentials.readHomeCredential(profileId)
        if (!credentials.putHomeCredential(profileId, credential)) return false

        val next = collection.upsert(
            current.copy(
                homeAdministration = RelayHomeAdministration(
                    phase = RelayHomeAdministrationPhase.Approved,
                    deviceId = deviceId.trim(),
                    generation = generation,
                    requestId = requestId,
                    credentialScope = credentialScope,
                    credentialExpiresAt = credentialExpiresAt,
                ),
            ),
        )
        if (!profiles.save(next)) {
            val rollbackSucceeded = credentials.deleteHomeCredential(profileId) &&
                (previousCredential == null || credentials.putHomeCredential(profileId, previousCredential))
            if (!rollbackSucceeded) return false
            return false
        }
        collection = next
        return true
    }

    /** Updates only non-secret Home administration metadata. */
    fun updateHomeAdministration(
        profileId: String,
        administration: RelayHomeAdministration,
    ): Boolean {
        val current = collection.profiles.firstOrNull { it.id == profileId }
            ?.takeIf { it.mode != RelayProfileMode.Standard }
            ?: return false
        return update(collection.upsert(current.copy(homeAdministration = administration)))
    }

    /** Revocation removes the active Device secret before publishing state. */
    fun revokeHomeDevice(profileId: String, phase: RelayHomeAdministrationPhase): Boolean {
        val current = collection.profiles.firstOrNull { it.id == profileId }
            ?.takeIf { it.mode != RelayProfileMode.Standard }
            ?: return false
        if (!credentials.deleteHomeCredential(profileId)) return false
        val next = collection.upsert(
            current.copy(
                homeAdministration = (current.homeAdministration
                    ?: RelayHomeAdministration(phase = phase)).copy(
                    phase = phase,
                    credentialExpiresAt = null,
                    credentialScope = null,
                ),
            ),
        )
        if (!profiles.save(next)) {
            return false
        }
        collection = next
        return true
    }

    /**
     * Adds one Profile per active grant of a personal-client pairing that has
     * no Profile yet. Returns the new Profile IDs, or null when nothing could
     * be saved. The first new Profile is selected when none is.
     */
    fun addHomeClientProfiles(record: HomeClientPairingRecord): List<String>? {
        val host = java.net.URI(record.homeUrl).host
        val route = HomePairingLink.bridgeRoute(record.homeUrl)
        var next = collection
        val added = mutableListOf<String>()
        record.grants
            .filter { it.status == HomeClientGrantStatus.Active }
            .filter { grant ->
                next.profiles.none {
                    it.homeClientGrant == RelayHomeClientGrantRef(record.pairingId, grant.grantId)
                }
            }
            .forEach { grant ->
                val profile = RelayProfile(
                    id = idFactory(),
                    endpoint = route,
                    clientId = HOME_CLIENT_ID,
                    deviceId = record.deviceId,
                    displayName = "${grant.label} · $host",
                    homeClientGrant = RelayHomeClientGrantRef(record.pairingId, grant.grantId),
                    mode = RelayProfileMode.HomeBridge,
                )
                next = next.add(profile)
                added += profile.id
            }
        if (added.isEmpty()) return emptyList()
        return if (update(next)) added else null
    }

    /** Returns true when the Profile was removed; false while a Standard turn blocks it. */
    fun delete(id: String): Boolean {
        if (id == collection.selectedId && switchBlocked) return false
        val deleted = collection.profiles.firstOrNull { it.id == id }
        if (!update(collection.remove(id))) return false
        credentials.delete(id)
        // A Profile's conversation must not outlive the Profile that held it,
        // unless another Profile still shares the same history key.
        deleted?.let { deleteHistoryIfUnused(it.historyKey) } ?: history?.delete(id)
        deleted?.homeClientGrant?.let(::releasePairingIfUnused)
        return true
    }

    /** The pairing credential is removed with the Home's last Profile. */
    private fun releasePairingIfUnused(grant: RelayHomeClientGrantRef) {
        if (collection.profiles.any { it.homeClientGrant?.pairingId == grant.pairingId }) return
        credentials.deleteHomeCredential(HomeClientPairingRecord.credentialSlot(grant.pairingId))
        homeClientPairings?.let { store -> store.save(store.load().remove(grant.pairingId)) }
    }

    private companion object {
        const val HOME_CLIENT_ID = "android"
        const val STANDARD_CLIENT_ID = "android"
    }

    private fun update(next: RelayProfileCollection): Boolean {
        if (!profiles.save(next)) return false
        collection = next
        return true
    }
}
