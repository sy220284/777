package com.labteto.dshmobile.local.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMcpCredentialStoreAndroidTest {
    @Test
    fun bearerIsEncryptedAndCannotBeReusedForAnotherOrigin() = runBlocking {
        withCredentialStore { store, preferences, id ->
            store.save(id, "https://mcp.example.com/v1/mcp", "confidential-bearer-123")
            assertEquals("confidential-bearer-123",
                store.forEndpoint(id, "https://mcp.example.com/v1/mcp"))
            assertNull(store.forEndpoint(id, "https://another.example.com/v1/mcp"))
            assertNull(store.forEndpoint(id, "https://mcp.example.com:8443/v1/mcp"))
            assertNull(store.forEndpoint(id, "https://mcp.example.com/another/mcp"))
            assertNull(store.forEndpoint(id, "https://mcp.example.com/v1/mcp?tenant=other"))
            val persisted = preferences.data.first().asMap().values.joinToString(",")
            assertFalse(persisted.contains("confidential-bearer-123"))
            store.clear(id)
            assertNull(store.forEndpoint(id, "https://mcp.example.com/v1/mcp"))
        }
    }

    @Test
    fun remotePlainHttpCannotReceiveBearerCredential() = runBlocking {
        withCredentialStore { store, _, id ->
            val failure = runCatching {
                store.save(id, "http://mcp.example.com/mcp", "secret")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertNull(store.forEndpoint(id, "https://mcp.example.com/mcp"))
        }
    }

    private suspend fun withCredentialStore(
        block: suspend (LocalMcpCredentialStore,
            androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
            String) -> Unit,
    ) {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val id = "mcp" + uuid.take(16)
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            .resolve("mcp-secret-$uuid.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            block(LocalMcpCredentialStore(prefs), prefs, id)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("dsh_mcp_bearer_$id")
            file.delete()
        }
    }
}
