package com.labteto.dshmobile.local.model.chatgpt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.local.KeystorePreferenceSecretStore
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ChatGptAccountStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val idsKey = stringPreferencesKey("chatgpt_account_ids_v1")
    private val selectedKey = stringPreferencesKey("chatgpt_selected_account_v1")

    private fun secret(id: String) = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "chatgpt_account_secret_$id",
        alias = "dsh_chatgpt_accounts_v1",
    )

    suspend fun list(): List<ChatGptAccountRecord> {
        val ids = accountIds()
        return ids.mapNotNull { id ->
            secret(id).get()?.let { raw ->
                runCatching { json.decodeFromString<ChatGptAccountRecord>(raw) }.getOrNull()
            }
        }
    }

    suspend fun get(id: String): ChatGptAccountRecord? =
        secret(id).get()?.let { raw ->
            runCatching { json.decodeFromString<ChatGptAccountRecord>(raw) }.getOrNull()
        }

    suspend fun selectedId(): String? = dataStore.data.first()[selectedKey]

    suspend fun selected(): ChatGptAccountRecord? = selectedId()?.let { get(it) }

    suspend fun put(record: ChatGptAccountRecord, select: Boolean = true) = mutex.withLock {
        secret(record.id).put(json.encodeToString(record))
        dataStore.edit { preferences ->
            val ids = parseIds(preferences[idsKey]).toMutableList()
            if (record.id !in ids) ids += record.id
            preferences[idsKey] = ids.joinToString(",")
            if (select) preferences[selectedKey] = record.id
        }
    }

    /** Refresh may update an existing credential snapshot, never recreate a removed/replaced login. */
    suspend fun replaceCredentials(expected: ChatGptAccountRecord, replacement: ChatGptAccountRecord): Boolean =
        mutex.withLock {
            require(replacement.id == expected.id) { "刷新凭据账户身份不匹配" }
            if (get(expected.id) != expected) return@withLock false
            secret(expected.id).put(json.encodeToString(replacement))
            true
        }

    suspend fun select(id: String) = mutex.withLock {
        require(get(id) != null) { "ChatGPT 账户不存在或凭据已失效" }
        dataStore.edit { it[selectedKey] = id }
    }

    suspend fun clearCredentials(id: String, expected: ChatGptAccountRecord? = null) = mutex.withLock {
        val current = get(id) ?: return@withLock
        if (expected != null && current != expected) return@withLock
        val disconnected = current.copy(
            email = null,
            displayName = null,
            idToken = "",
            accessToken = "",
            refreshToken = "",
            scopes = emptySet(),
            accessTokenExpiresAtEpochSeconds = 0L,
            savedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
        )
        secret(id).put(json.encodeToString(disconnected))
    }

    suspend fun remove(id: String) = mutex.withLock {
        secret(id).clear()
        dataStore.edit { preferences ->
            val remaining = parseIds(preferences[idsKey]).filterNot { it == id }
            preferences[idsKey] = remaining.joinToString(",")
            if (preferences[selectedKey] == id) {
                if (remaining.isEmpty()) preferences.remove(selectedKey)
                else preferences[selectedKey] = remaining.first()
            }
        }
    }

    private suspend fun accountIds(): List<String> =
        parseIds(dataStore.data.first()[idsKey])

    private fun parseIds(raw: String?): List<String> =
        raw.orEmpty().split(',').map(String::trim).filter(String::isNotBlank).distinct()

    companion object {
        fun accountId(clientId: String, subject: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((clientId.trim() + "\u0000" + subject.trim()).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
