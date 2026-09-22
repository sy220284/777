package com.labteto.dshmobile.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import javax.inject.Inject
import javax.inject.Singleton

/** Separately encrypted credential for the optional multimodal vision provider. */
@Singleton
class LocalVisionApiKeyStore @Inject constructor(
    dataStore: DataStore<Preferences>,
) {
    private val delegate = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "local_harness_vision_api_key",
        alias = "dsh_local_harness_vision_api_key",
    )

    suspend fun get(): String? = delegate.get()

    suspend fun put(value: String) = delegate.put(value)

    suspend fun clear() = delegate.clear()
}
