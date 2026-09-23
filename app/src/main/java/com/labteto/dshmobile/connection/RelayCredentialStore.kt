package com.labteto.dshmobile.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relay device tokens, encrypted at rest.
 *
 * A device token is not a session identifier that can be re-fetched — the relay shows it once, at
 * pairing, and stores only a keyed hash of it afterwards. It is also the whole credential, and
 * authenticating to a relay grants the same power as a shell on the machine running the harness,
 * because the agent runs commands there. So it is treated the way a password would be rather than
 * the way [HostConfig] is.
 *
 * The key lives in the `AndroidKeyStore` and never leaves it; only ciphertext reaches DataStore,
 * beside the remembered hosts. `androidx.security-crypto` would do the same wrapping, but it is
 * deprecated and would introduce a second persistence mechanism next to the one this app already
 * has, so the ~40 lines are written out instead.
 *
 * User authentication is deliberately *not* required on the key: the foreground service and
 * [KeepAliveWorker] reconnect while the screen is locked, and a key that needed a present user
 * would turn every background reconnect into a silent failure.
 */
@Singleton
class RelayCredentialStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** The bearer value for [hostId] — the full header, not the bare token — or null when unpaired. */
    suspend fun authorization(hostId: String): String? = token(hostId)?.let { "Bearer $it" }

    /**
     * The stored token for [hostId], or null when there is none this device can still read.
     *
     * A blob that will not decrypt is dropped rather than raised. That happens when the Keystore key
     * was invalidated — a full device restore, or the user removing every screen lock — and the
     * result is the same terminal state as a revoked token: this device has no credential and has to
     * pair again. Reporting it as an error would only offer the user a choice they do not have.
     */
    suspend fun token(hostId: String): String? {
        val blob = blobs()[hostId] ?: return null
        val plain = withContext(Dispatchers.Default) { runCatching { decrypt(blob) }.getOrNull() }
        if (plain == null) removeIfUnchanged(hostId, blob)
        return plain
    }

    /** Remember [token] for [hostId], replacing any previous one without clobbering other hosts. */
    suspend fun put(hostId: String, token: String) {
        val blob = withContext(Dispatchers.Default) { encrypt(token) }
        dataStore.edit { prefs ->
            val current = decodeBlobs(prefs[KEY]).toMutableMap()
            current[hostId] = blob
            prefs[KEY] = WireJson.encodeToString(serializer, current)
        }
    }

    /** Forget the credential for [hostId]. Safe to call when there is none. */
    suspend fun remove(hostId: String) {
        dataStore.edit { prefs ->
            val current = decodeBlobs(prefs[KEY]).toMutableMap()
            if (current.remove(hostId) == null) return@edit
            if (current.isEmpty()) prefs.remove(KEY)
            else prefs[KEY] = WireJson.encodeToString(serializer, current)
        }
    }

    /**
     * Drop a corrupt blob only if it is still the blob that failed to decrypt.
     *
     * A fresh pairing may replace the credential while decryption is happening on another
     * dispatcher. Blindly calling [remove] here could otherwise delete that brand-new token.
     */
    private suspend fun removeIfUnchanged(hostId: String, failedBlob: String) {
        dataStore.edit { prefs ->
            val current = decodeBlobs(prefs[KEY]).toMutableMap()
            if (current[hostId] != failedBlob) return@edit
            current.remove(hostId)
            if (current.isEmpty()) prefs.remove(KEY)
            else prefs[KEY] = WireJson.encodeToString(serializer, current)
        }
    }

    /** Forget every credential — the Settings "clear data" action, and a signed-out-everywhere relay. */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private suspend fun blobs(): Map<String, String> =
        decodeBlobs(dataStore.data.first()[KEY])

    private fun decodeBlobs(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    /**
     * AES-GCM, with the IV prefixed to the ciphertext.
     *
     * GCM generates its own IV per encryption and the Keystore refuses a caller-supplied one for
     * this key, so the IV has to be read back off the cipher and carried with the payload — it is
     * not a secret, only a value that must never repeat under one key.
     */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + SEPARATOR + encoder.encodeToString(body)
    }

    private fun decrypt(blob: String): String {
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) throw GeneralSecurityException("malformed credential blob")
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])))
        return String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
    }

    /** The Keystore key, generated on first use. */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        val KEY = stringPreferencesKey("relay_tokens_json")
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dsh_relay_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        /** Not valid base64, so it cannot occur inside either half. */
        const val SEPARATOR = ":"
    }
}
