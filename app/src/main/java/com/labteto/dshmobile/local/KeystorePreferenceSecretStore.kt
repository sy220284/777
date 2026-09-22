package com.labteto.dshmobile.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One encrypted string backed by Preferences DataStore and a non-exportable Android Keystore key.
 *
 * Callers choose stable preference/alias names so refactors do not invalidate existing ciphertext.
 */
internal class KeystorePreferenceSecretStore(
    private val dataStore: DataStore<Preferences>,
    preferenceName: String,
    private val alias: String,
) {
    private val key = stringPreferencesKey(preferenceName)

    suspend fun get(): String? {
        val blob = dataStore.data.first()[key] ?: return null
        val value = withContext(Dispatchers.Default) {
            runCatching { decrypt(blob) }.getOrNull()
        }
        if (value == null) clear()
        return value
    }

    suspend fun put(value: String) {
        require(value.isNotBlank()) { "secret cannot be blank" }
        val encrypted = withContext(Dispatchers.Default) { encrypt(value.trim()) }
        dataStore.edit { preferences -> preferences[key] = encrypted }
    }

    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(key) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + SEPARATOR +
            encoder.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) throw GeneralSecurityException("malformed local credential")
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])),
        )
        return String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
