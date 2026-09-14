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
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    var collection: RelayProfileCollection = profiles.load()
        private set

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

    fun select(id: String) = update(collection.select(id))

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
            ?: return RelayHomeMigrationResult.Rejected(
                RelayHomeMigrationFailure.ProfileUnavailable,
            )
        if (RelayProfileValidator.validateApprovedHomeRoute(pairing.approvedRoute) != null) {
            return RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.RouteInvalid)
        }
        if (!HomeCredentialValidator.isValid(pairing.deviceCredential)) {
            return RelayHomeMigrationResult.Rejected(RelayHomeMigrationFailure.CredentialInvalid)
        }
        if (pairing.conversationHandle.isBlank() || pairing.conversationHandle.length > 512) {
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

    fun delete(id: String) {
        if (update(collection.remove(id))) {
            credentials.delete(id)
            // A Profile's conversation must not outlive the Profile that held it.
            history?.delete(id)
        }
    }

    private fun update(next: RelayProfileCollection): Boolean {
        if (!profiles.save(next)) return false
        collection = next
        return true
    }
}
