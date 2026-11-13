package com.labteto.dshmobile.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.security.KeystorePreferenceSecretStore
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystorePreferenceSecretStoreAndroidTest {
    @Test
    fun malformedCiphertextIsRetainedAndDoesNotCreateAReplacementKey() = runBlocking {
        withStore { preferences, alias ->
            val key = stringPreferencesKey("secret")
            preferences.edit { it[key] = "broken" }
            val store = KeystorePreferenceSecretStore(preferences, "secret", alias)
            assertTrue(runCatching { store.get() }.exceptionOrNull() is IOException)
            assertEquals("broken", preferences.data.first()[key])
            assertFalse(keyStore().containsAlias(alias))
        }
    }

    @Test
    fun missingEncryptionKeyRetainsCiphertextWithoutCreatingANewKey() = runBlocking {
        withStore { preferences, alias ->
            val store = KeystorePreferenceSecretStore(preferences, "secret", alias)
            store.put("authorized")
            val key = stringPreferencesKey("secret")
            val encrypted = preferences.data.first()[key]
            keyStore().deleteEntry(alias)
            assertTrue(runCatching { store.get() }.exceptionOrNull() is IOException)
            assertEquals(encrypted, preferences.data.first()[key])
            assertFalse(keyStore().containsAlias(alias))
        }
    }

    @Test
    fun independentStoresShareTheFirstKeyDuringConcurrentRegistration() = runBlocking {
        withStore { preferences, alias ->
            coroutineScope {
                (0 until 16).map { index -> async(Dispatchers.Default) {
                    KeystorePreferenceSecretStore(preferences, "secret-$index", alias).put("token-$index")
                } }.awaitAll()
            }
            for (index in 0 until 16) {
                assertEquals("token-$index", KeystorePreferenceSecretStore(preferences, "secret-$index", alias).get())
            }
        }
    }

    private suspend fun withStore(
        test: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, String) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("secret-$id.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try { test(preferences, "test-777-$id") } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            keyStore().deleteEntry("test-777-$id")
            file.delete()
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
