package com.labteto.dshmobile.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.model.LocalApiKeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalApiKeyStoreMigrationAndroidTest {
    @Test
    fun emptyMigrationTargetKeepsLegacyCredential() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("api-key-migration-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val store = LocalApiKeyStore(dataStore)
            store.put("legacy-secret")
            assertTrue(store.hasLegacyCredential())

            store.migrate(emptyList())

            assertTrue(store.hasLegacyCredential())
            assertEquals("legacy-secret", store.get())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }
}
