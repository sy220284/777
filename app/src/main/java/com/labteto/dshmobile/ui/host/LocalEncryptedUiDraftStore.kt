package com.labteto.dshmobile.ui.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Android SavedState and navigation snapshots are not a vault. Composer text
 * and attachment metadata are never written to Bundles; a single application-
 * private, Keystore-encrypted owner survives process death and pack switching.
 */
internal class LocalEncryptedUiDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "ui_private_drafts_v1", Context.MODE_PRIVATE,
    )
    private val secretKey: SecretKey by lazy { getOrCreateKey() }

    fun read(domain: String): List<String> {
        checkDomain(domain)
        val encoded = preferences.getString("cipher.$domain", null) ?: return emptyList()
        val bytes = LocalUiDraftCipher.decrypt(secretKey, encoded, domain)
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "保存的草稿超过安全恢复大小预算" }
        return Json.decodeFromString<List<String>>(bytes.toString(Charsets.UTF_8))
            .also { require(it.size <= MAX_ELEMENTS) { "保存的草稿条目过多" } }
    }

    fun write(domain: String, content: List<String>) {
        checkDomain(domain)
        require(content.size <= MAX_ELEMENTS) { "草稿条目超过恢复预算" }
        val data = Json.encodeToString(content).toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_PLAINTEXT_BYTES) { "草稿内容超出安全保存预算" }
        // Even an empty value must be durably represented so old process Bundles
        // cannot later resurrect drafts the user intentionally cleared.
        preferences.edit()
            .putString("cipher.$domain", LocalUiDraftCipher.encrypt(secretKey, data, domain))
            .putBoolean("migrated.$domain", true)
            .apply()
    }

    /** One-time migration, never restore plaintext SavedState after the secure store exists. */
    fun migrateOldSavedStateOnce(domain: String, old: List<String>?) {
        checkDomain(domain)
        if (preferences.getBoolean("migrated.$domain", false)) return
        if (old != null) {
            write(domain, old)
        } else {
            preferences.edit().putBoolean("migrated.$domain", true).apply()
        }
    }

    private fun checkDomain(domain: String) {
        require(domain in setOf("conversation_drafts", "attachment_drafts")) {
            "未知的草稿私有存储域"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "777.ui.draft.encryption.v1"
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        const val MAX_ELEMENTS = 96
    }
}

/** Deterministic format validation; IV random per write, AAD binds the field domain. */
internal object LocalUiDraftCipher {
    fun encrypt(key: SecretKey, plain: ByteArray, domain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(domain.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        require(iv.size == 12)
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(1.toByte()).put(iv).put(encrypted).array())
    }

    fun decrypt(key: SecretKey, blob: String, domain: String): ByteArray {
        val bytes = Base64.getDecoder().decode(blob)
        require(bytes.size in 29..(256 * 1024)) { "加密草稿长度错误" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get().toInt() == 1) { "加密草稿版本不受支持" }
        val iv = ByteArray(12)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(domain.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }
}
