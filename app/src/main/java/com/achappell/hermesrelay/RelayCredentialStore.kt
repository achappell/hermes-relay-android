package com.achappell.hermesrelay

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one bearer token per relay profile.
 *
 * [read] is the transport's entry point and is deliberately the only way to
 * obtain a token. The configuration UI uses [hasToken] so a secret never
 * reaches Compose state, a saved instance bundle, or a log line.
 */
internal interface RelayCredentialStore {
    fun put(profileId: String, token: String)

    fun hasToken(profileId: String): Boolean

    fun read(profileId: String): String?

    fun delete(profileId: String)
}

/**
 * Android Keystore implementation using AES-GCM.
 *
 * The key material never leaves the Keystore; only the ciphertext and its
 * per-record IV are persisted. A hand-rolled envelope keeps this path free of
 * an additional dependency for the small amount of work it actually needs.
 */
internal class KeystoreRelayCredentialStore(
    context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ),
) : RelayCredentialStore {

    override fun put(profileId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val envelope = buildString {
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        preferences.edit().putString(key(profileId), envelope).apply()
    }

    override fun hasToken(profileId: String): Boolean =
        preferences.contains(key(profileId))

    override fun read(profileId: String): String? {
        val envelope = preferences.getString(key(profileId), null) ?: return null
        val separator = envelope.indexOf(':')
        if (separator <= 0) return null

        return runCatching {
            val iv = Base64.decode(envelope.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun delete(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String) = "token:$profileId"

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

/** In-memory store for deterministic tests and the unconfigured bootstrap shell. */
internal class InMemoryRelayCredentialStore(
    initial: Map<String, String> = emptyMap(),
) : RelayCredentialStore {
    private val tokens = initial.toMutableMap()

    override fun put(profileId: String, token: String) {
        tokens[profileId] = token
    }

    override fun hasToken(profileId: String) = tokens.containsKey(profileId)

    override fun read(profileId: String) = tokens[profileId]

    override fun delete(profileId: String) {
        tokens.remove(profileId)
    }
}
