package com.labteto.dshmobile.local.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.labteto.dshmobile.local.security.KeystorePreferenceSecretStore
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub connector credential encrypted with a dedicated non-exportable Android Keystore key. */
@Singleton
class LocalGitHubCredentialStore @Inject constructor(
    dataStore: DataStore<Preferences>,
) {
    private val delegate = KeystorePreferenceSecretStore(
        dataStore = dataStore,
        preferenceName = "github_connector_token",
        alias = "dsh_github_connector_token",
    )

    suspend fun get(): String? = delegate.get()

    suspend fun put(value: String) {
        val clean = value.trim()
        require(clean.length in 16..4_096) { "GitHub 凭据长度无效" }
        delegate.put(clean)
    }

    suspend fun clear() = delegate.clear()

    suspend fun configured(): Boolean = get()?.isNotBlank() == true
}
