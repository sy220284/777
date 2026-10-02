package com.labteto.dshmobile.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/** DeepSeek API key encrypted with the original non-exportable Android Keystore key. */
@Singleton
class LocalApiKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    // Keep both identifiers stable so existing encrypted credentials remain readable after refactor.
    private val delegate = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "local_harness_api_key",
        alias = "dsh_local_harness_api_key",
    )

    @Volatile private var activeId: String? = null

    fun activate(id: String) { activeId = id }

    private fun route(id: String) = KeystorePreferenceSecretStore(
        dataStore, "local_model_key_$id", "dsh_local_harness_api_key",
    )

    suspend fun get(): String? = activeId?.let { route(it).get() } ?: delegate.get()

    suspend fun getFor(id: String): String? = route(id).get()

    suspend fun hasLegacyCredential(): Boolean = delegate.get() != null

    suspend fun putFor(id: String, value: String) = route(id).put(value)

    suspend fun clearFor(id: String) = route(id).clear()

    suspend fun migrate(ids: List<String>) {
        if (ids.isEmpty()) return
        val old = delegate.get() ?: return
        ids.forEach { id -> if (getFor(id) == null) putFor(id, old) }
        delegate.clear()
    }

    suspend fun put(value: String) = activeId?.let { route(it).put(value) } ?: delegate.put(value)

    suspend fun clear() = activeId?.let { route(it).clear() } ?: delegate.clear()
}
