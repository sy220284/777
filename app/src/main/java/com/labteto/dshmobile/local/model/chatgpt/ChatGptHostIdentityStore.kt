package com.labteto.dshmobile.local.model.chatgpt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ChatGptHostIdentityStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val mutex = Mutex()
    private val hostIdKey = stringPreferencesKey("chatgpt_host_id_v1")
    private val pendingClientIdKey = stringPreferencesKey("chatgpt_pending_client_id_v1")
    private val pendingClientSavedAtKey = longPreferencesKey("chatgpt_pending_client_saved_at_v1")

    suspend fun getOrCreate(): String = mutex.withLock {
        dataStore.data.first()[hostIdKey]?.takeIf(String::isNotBlank)?.let { return@withLock it }
        val value = "urn:uuid:" + UUID.randomUUID().toString()
        dataStore.edit { it[hostIdKey] = value }
        value
    }

    suspend fun pendingClientId(): String? = mutex.withLock {
        val snapshot = dataStore.data.first()
        val id = snapshot[pendingClientIdKey]?.takeIf(String::isNotBlank) ?: return@withLock null
        val savedAt = snapshot[pendingClientSavedAtKey] ?: 0L
        if (System.currentTimeMillis() - savedAt <= PENDING_REGISTRATION_TTL_MILLIS) return@withLock id
        dataStore.edit {
            it.remove(pendingClientIdKey)
            it.remove(pendingClientSavedAtKey)
        }
        null
    }

    suspend fun rememberPendingClientId(clientId: String) = mutex.withLock {
        require(clientId.isNotBlank() && clientId != CHATGPT_DYNAMIC_CLIENT_ID) {
            "ChatGPT 待恢复注册 client_id 无效"
        }
        dataStore.edit {
            it[pendingClientIdKey] = clientId
            it[pendingClientSavedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearPendingClientId(clientId: String) = mutex.withLock {
        dataStore.edit {
            if (it[pendingClientIdKey] == clientId) {
                it.remove(pendingClientIdKey)
                it.remove(pendingClientSavedAtKey)
            }
        }
    }

    private companion object {
        const val PENDING_REGISTRATION_TTL_MILLIS = 15 * 60_000L
    }
}
