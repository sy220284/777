package com.labteto.dshmobile.local.model.chatgpt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun getOrCreate(): String = mutex.withLock {
        dataStore.data.first()[hostIdKey]?.takeIf(String::isNotBlank)?.let { return@withLock it }
        val value = "urn:uuid:" + UUID.randomUUID().toString()
        dataStore.edit { it[hostIdKey] = value }
        value
    }
}
