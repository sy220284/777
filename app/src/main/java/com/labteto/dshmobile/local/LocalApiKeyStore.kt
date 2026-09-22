package com.labteto.dshmobile.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/** DeepSeek API key encrypted with the original non-exportable Android Keystore key. */
@Singleton
class LocalApiKeyStore @Inject constructor(
    dataStore: DataStore<Preferences>,
) {
    // Keep both identifiers stable so existing encrypted credentials remain readable after refactor.
    private val delegate = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "local_harness_api_key",
        alias = "dsh_local_harness_api_key",
    )

    suspend fun get(): String? = delegate.get()

    suspend fun put(value: String) = delegate.put(value)

    suspend fun clear() = delegate.clear()
}
