package com.achappell.hermesrelay

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Base64 as JvmBase64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The credential slots have different jobs and are never interchangeable. The
 * Home Device credential is the only credential the active bridge reads; the
 * Home admin credential is restricted to administrative HTTP; the old bearer
 * remains available solely for a deliberate rollback operation.
 */
internal interface RelayCredentialStore {
    /** Legacy API: writes the rollback-only credential slot. */
    fun put(profileId: String, token: String): Boolean

    /** Legacy API: proves that the rollback slot can actually be read. */
    fun hasToken(profileId: String): Boolean

    /** Legacy API: reads the rollback-only credential slot. */
    fun read(profileId: String): String?

    /** Stores a validated Home Device credential in its distinct secure slot. */
    fun putHomeCredential(profileId: String, credential: String): Boolean = false

    /** Reads and decrypts the Home Device credential, or fails closed. */
    fun readHomeCredential(profileId: String): String? = null

    /** This is intentionally a secure read, not a presence check. */
    fun hasReadableHomeCredential(profileId: String): Boolean =
        readHomeCredential(profileId) != null

    /** Used only to restore a failed replacement of the Home slot. */
    fun deleteHomeCredential(profileId: String): Boolean = false

    /** Stores the Home administrative credential in its own secure slot. */
    fun putHomeAdminCredential(profileId: String, credential: String): Boolean = false

    /** Reads the Home administrative credential, or fails closed. */
    fun readHomeAdminCredential(profileId: String): String? = null

    fun hasReadableHomeAdminCredential(profileId: String): Boolean =
        !readHomeAdminCredential(profileId).isNullOrBlank()

    fun deleteHomeAdminCredential(profileId: String) = Unit

    /** Explicit names for the rollback slot used by migration code. */
    fun putRollbackCredential(profileId: String, credential: String): Boolean {
        return put(profileId, credential)
    }

    fun readRollbackCredential(profileId: String): String? = read(profileId)

    fun hasReadableRollbackCredential(profileId: String): Boolean =
        readRollbackCredential(profileId) != null

    /** Distinguishes an absent slot from a slot that cannot be decrypted. */
    fun hasStoredRollbackCredential(profileId: String): Boolean =
        hasReadableRollbackCredential(profileId)

    /** Removes both slots when the owning Profile is deleted. */
    fun delete(profileId: String)
}

/** The production representation required by the Home credential contract. */
internal object HomeCredentialValidator {
    const val BYTE_COUNT = 32
    const val ASCII_LENGTH = 43

    private val encoded = Regex("[A-Za-z0-9_-]{${ASCII_LENGTH}}")

    fun isValid(value: String): Boolean {
        if (!encoded.matches(value)) return false
        return runCatching {
            JvmBase64.getUrlDecoder().decode(value).size == BYTE_COUNT
        }.getOrDefault(false)
    }
}

/**
 * Android Keystore implementation using AES-GCM.
 *
 * Key material never leaves the Keystore; only ciphertext and its per-record
 * IV are persisted. The slot names are deliberately separate so a legacy
 * bearer cannot accidentally become a Home authorization credential.
 */
internal class KeystoreRelayCredentialStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
) : RelayCredentialStore {
    override fun put(profileId: String, token: String): Boolean =
        putRollbackCredential(profileId, token)

    override fun hasToken(profileId: String): Boolean =
        hasReadableRollbackCredential(profileId)

    override fun read(profileId: String): String? = readRollbackCredential(profileId)

    override fun putHomeCredential(profileId: String, credential: String): Boolean {
        if (!HomeCredentialValidator.isValid(credential)) return false
        return writeSecret(homeKey(profileId), credential)
    }

    override fun readHomeCredential(profileId: String): String? =
        readSecret(homeKey(profileId))?.takeIf(HomeCredentialValidator::isValid)

    override fun deleteHomeCredential(profileId: String): Boolean =
        preferences.edit().remove(homeKey(profileId)).commit()

    override fun putHomeAdminCredential(profileId: String, credential: String): Boolean =
        credential.isNotBlank() && writeSecret(homeAdminKey(profileId), credential.trim())

    override fun readHomeAdminCredential(profileId: String): String? =
        readSecret(homeAdminKey(profileId))

    override fun deleteHomeAdminCredential(profileId: String) {
        preferences.edit().remove(homeAdminKey(profileId)).commit()
    }

    override fun putRollbackCredential(profileId: String, credential: String): Boolean =
        credential.isNotBlank() && writeSecret(rollbackKey(profileId), credential.trim())

    override fun readRollbackCredential(profileId: String): String? =
        readSecret(rollbackKey(profileId))
            ?: readSecret(legacyTokenKey(profileId))

    override fun hasStoredRollbackCredential(profileId: String): Boolean =
        preferences.contains(rollbackKey(profileId)) ||
            preferences.contains(legacyTokenKey(profileId))

    override fun delete(profileId: String) {
        preferences.edit()
            .remove(homeKey(profileId))
            .remove(homeAdminKey(profileId))
            .remove(rollbackKey(profileId))
            // Keep deletion compatible with profiles written before the slot
            // split, where the old bearer lived under token:<profile>.
            .remove(legacyTokenKey(profileId))
            .commit()
    }

    private fun writeSecret(storageKey: String, secret: String): Boolean {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val envelope = buildString {
                append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                append(':')
                append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            }
            preferences.edit().putString(storageKey, envelope).commit()
        }.getOrDefault(false)
    }

    private fun readSecret(storageKey: String): String? {
        val envelope = preferences.getString(storageKey, null) ?: return null
        val separator = envelope.indexOf(':')
        if (separator <= 0 || separator == envelope.lastIndex) return null

        return runCatching {
            val iv = Base64.decode(envelope.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun homeKey(profileId: String) = "home-device:$profileId"

    private fun homeAdminKey(profileId: String) = "home-admin:$profileId"

    private fun rollbackKey(profileId: String) = "rollback:$profileId"

    private fun legacyTokenKey(profileId: String) = "token:$profileId"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "hermes_relay_credentials"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes-relay-credential-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/** In-memory store for deterministic tests and typed pairing seams. */
internal class InMemoryRelayCredentialStore(
    initial: Map<String, String> = emptyMap(),
    homeCredentials: Map<String, String> = emptyMap(),
    homeAdminCredentials: Map<String, String> = emptyMap(),
) : RelayCredentialStore {
    private val rollback = initial.toMutableMap()
    private val home = homeCredentials.toMutableMap()
    private val homeAdmin = homeAdminCredentials.toMutableMap()

    override fun put(profileId: String, token: String): Boolean =
        putRollbackCredential(profileId, token)

    override fun hasToken(profileId: String): Boolean =
        hasReadableRollbackCredential(profileId)

    override fun read(profileId: String): String? = readRollbackCredential(profileId)

    override fun putHomeCredential(profileId: String, credential: String): Boolean {
        if (!HomeCredentialValidator.isValid(credential)) return false
        home[profileId] = credential
        return true
    }

    override fun readHomeCredential(profileId: String): String? =
        home[profileId]?.takeIf(HomeCredentialValidator::isValid)

    override fun deleteHomeCredential(profileId: String): Boolean {
        home.remove(profileId)
        return true
    }

    override fun putHomeAdminCredential(profileId: String, credential: String): Boolean {
        if (credential.isBlank()) return false
        homeAdmin[profileId] = credential.trim()
        return true
    }

    override fun readHomeAdminCredential(profileId: String): String? = homeAdmin[profileId]

    override fun deleteHomeAdminCredential(profileId: String) {
        homeAdmin.remove(profileId)
    }

    override fun putRollbackCredential(profileId: String, credential: String): Boolean {
        if (credential.isBlank()) return false
        rollback[profileId] = credential.trim()
        return true
    }

    override fun readRollbackCredential(profileId: String): String? = rollback[profileId]

    override fun hasStoredRollbackCredential(profileId: String): Boolean =
        rollback.containsKey(profileId)

    override fun delete(profileId: String) {
        home.remove(profileId)
        homeAdmin.remove(profileId)
        rollback.remove(profileId)
    }
}
