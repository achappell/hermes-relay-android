package com.achappell.hermesrelay

import android.content.Context
import java.io.File

/** Persists the non-secret relay profile collection in app-private storage. */
internal interface RelayProfileStore {
    fun load(): RelayProfileCollection

    fun save(collection: RelayProfileCollection)
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

    override fun save(collection: RelayProfileCollection) {
        runCatching { file.writeText(collection.toJson()) }
    }

    private companion object {
        const val FILE_NAME = "relay-profiles.json"
    }
}

internal class InMemoryRelayProfileStore(
    private var collection: RelayProfileCollection = RelayProfileCollection(),
) : RelayProfileStore {
    override fun load() = collection

    override fun save(collection: RelayProfileCollection) {
        this.collection = collection
    }
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
        credentials.put(profile.id, token.trim())
        update(collection.add(profile))
        return emptyMap()
    }

    fun select(id: String) = update(collection.select(id))

    fun delete(id: String) {
        credentials.delete(id)
        // A Profile's conversation must not outlive the Profile that held it.
        history?.delete(id)
        update(collection.remove(id))
    }

    private fun update(next: RelayProfileCollection) {
        collection = next
        profiles.save(next)
    }
}
