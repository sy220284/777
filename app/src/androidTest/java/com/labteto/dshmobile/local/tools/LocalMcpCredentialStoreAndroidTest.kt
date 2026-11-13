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


    @Test fun oauthTokensAreEncryptedBoundAndRevokedLocallyWhenNoRemoteEndpointExists() = runBlocking {
        withCredentialStore { store, preferences, id ->
            val endpoint = "https://mcp.example.com/tenant/mcp"
            val credential = LocalMcpOAuthCredential("https://issuer.example.com", "client", "https://issuer.example.com/token",
                endpoint, accessToken = "oauth-access-secret", refreshToken = "oauth-refresh-secret",
                expiresAt = System.currentTimeMillis() + 60_000)
            store.saveOAuth(id, endpoint, credential)
            assertEquals("oauth-access-secret", store.forEndpoint(id, endpoint))
            assertNull(store.forEndpoint(id, "https://mcp.example.com/other/mcp"))
            assertNull(store.forEndpoint("other-$id".take(24), endpoint))
            val persisted = preferences.data.first().asMap().values.joinToString(",")
            assertFalse(persisted.contains("oauth-access-secret"))
            assertFalse(persisted.contains("oauth-refresh-secret"))
            assertFalse(store.revoke(id, endpoint))
            assertNull(store.forEndpoint(id, endpoint))
        }
    }

    @Test fun expiredTokenWithoutRefreshRequiresLoginAndPreservesClientIdentity() = runBlocking {
        withCredentialStore { store, _, id ->
            val endpoint = "https://mcp.example.com/mcp"
            store.saveOAuth(id, endpoint, LocalMcpOAuthCredential("https://issuer.example.com", "original-client",
                "https://issuer.example.com/token", endpoint, accessToken = "expired", expiresAt = 0L))
            val failure = runCatching { store.forEndpoint(id, endpoint) }.exceptionOrNull()
            assertTrue(failure is LocalMcpOAuthFailure)
            assertEquals("original-client", store.oauthForEndpoint(id, endpoint)?.clientId)
            store.save(id, endpoint, "oauth:valid-manual-bearer")
            assertEquals("oauth:valid-manual-bearer", store.forEndpoint(id, endpoint))
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
