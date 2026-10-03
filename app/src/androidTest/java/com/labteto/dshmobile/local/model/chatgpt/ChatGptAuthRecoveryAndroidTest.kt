package com.labteto.dshmobile.local.model.chatgpt

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGptAuthRecoveryAndroidTest {
    @Test
    fun replayedInvalidationChecksCurrentAuthorizationWithoutPublishingAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("auth-replay-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val accounts = ChatGptAccountStore(preferences, Json)
        val events = ChatGptPlanAuthorizationEvents()
        val resolver = com.labteto.dshmobile.local.model.LocalModelCredentialResolver(
            com.labteto.dshmobile.local.LocalApiKeyStore(preferences), accounts,
            ChatGptSessionManager(accounts, OkHttpClient(), Json), events,
        )
        val id = ChatGptAccountStore.accountId("oaiapp_replay", "subject")
        val record = ChatGptAccountRecord(
            id = id, clientId = "oaiapp_replay", issuer = CHATGPT_ISSUER, subject = "subject",
            hostId = "host", idToken = "id", accessToken = "access", refreshToken = "refresh",
            scopes = setOf(CHATGPT_PLAN_SCOPE, CHATGPT_RESOURCE_INVOKE_SCOPE),
            accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE, savedAtEpochSeconds = 1,
        )
        try {
            accounts.put(record.copy(scopes = emptySet()))
            events.invalidate(id)
            assertFalse(resolver.hasChatGptPlanAuthorization(id))
            accounts.put(record)
            assertEquals(id, withTimeout(1_000) { events.invalidatedAccounts.first() })
            assertTrue(resolver.hasChatGptPlanAuthorization(id))
            assertFalse(resolver.hasChatGptPlanAuthorization("unknown-account"))
            assertEquals(listOf(id), events.invalidatedAccounts.replayCache)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun signedInWithoutPlanScopeStaysSavedAndSkipsModelDiscovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("auth-no-plan-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val accounts = ChatGptAccountStore(preferences, Json)
        var networkCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls += 1
            error("未启用套餐的登录账户不应访问模型目录：${chain.request().url}")
        }.build()
        val auth = ChatGptAuthCoordinator(
            context,
            accounts,
            ChatGptHostIdentityStore(preferences),
            ChatGptOAuthCallbackServer(),
            ChatGptSessionManager(accounts, http, Json),
            ChatGptIdTokenVerifier(http, Json),
        )
        val record = ChatGptAccountRecord(
            id = "test-no-plan-${UUID.randomUUID()}",
            clientId = "client",
            issuer = CHATGPT_ISSUER,
            subject = "subject",
            hostId = "host",
            idToken = "id",
            accessToken = "access",
            refreshToken = "refresh",
            scopes = setOf(CHATGPT_RESOURCE_INVOKE_SCOPE),
            accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
            savedAtEpochSeconds = 1,
        )
        try {
            accounts.put(record)
            auth.refresh()

            assertEquals(ChatGptAuthPhase.CONNECTED, auth.state.value.phase)
            assertTrue(auth.state.value.signedIn)
            assertFalse(auth.state.value.connected)
            assertTrue(auth.state.value.models.isEmpty())
            assertEquals(0, networkCalls)
            assertEquals(record, accounts.get(record.id))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun refreshScopeDowngradeImmediatelyBecomesSignedInPlanDisabledState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("auth-downgrade-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val accounts = ChatGptAccountStore(preferences, Json)
        var modelCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/oauth/token")) {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(
                        """{"access_token":"signed-in-only","refresh_token":"rotated","expires_in":3600,"scope":"openid profile email offline_access resource.invoke"}""".toResponseBody(),
                    ).build()
            } else {
                modelCalls += 1
                error("套餐 scope 已失效后不应继续访问模型目录")
            }
        }.build()
        val auth = ChatGptAuthCoordinator(
            context,
            accounts,
            ChatGptHostIdentityStore(preferences),
            ChatGptOAuthCallbackServer(),
            ChatGptSessionManager(accounts, http, Json),
            ChatGptIdTokenVerifier(http, Json),
        )
        val record = ChatGptAccountRecord(
            id = "test-downgrade-${UUID.randomUUID()}",
            clientId = "client",
            issuer = CHATGPT_ISSUER,
            subject = "subject",
            hostId = "host",
            idToken = "id",
            accessToken = "expired",
            refreshToken = "refresh",
            scopes = setOf(CHATGPT_PLAN_SCOPE, CHATGPT_RESOURCE_INVOKE_SCOPE),
            accessTokenExpiresAtEpochSeconds = 0,
            savedAtEpochSeconds = 1,
        )
        try {
            accounts.put(record)
            auth.refresh()

            assertEquals(ChatGptAuthPhase.CONNECTED, auth.state.value.phase)
            assertTrue(auth.state.value.signedIn)
            assertFalse(auth.state.value.connected)
            assertTrue(auth.state.value.models.isEmpty())
            assertEquals(0, modelCalls)
            val stored = accounts.get(record.id)!!
            assertEquals("rotated", stored.refreshToken)
            assertFalse(stored.sharingEnabled)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun failedModelProbeKeepsAuthorizationAndRecoversWithoutLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("auth-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val accounts = ChatGptAccountStore(preferences, Json)
        var offline = false
        var delayProbe = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (offline) throw IOException("test network unavailable")
            if (delayProbe && chain.request().url.encodedPath.endsWith("/models")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"models":[{"slug":"gpt-test","visibility":"list","display_name":"Test"}]}""".toResponseBody()).build()
        }.build()
        val auth = ChatGptAuthCoordinator(context, accounts, ChatGptHostIdentityStore(preferences),
            ChatGptOAuthCallbackServer(), ChatGptSessionManager(accounts, http, Json), ChatGptIdTokenVerifier(http, Json))
        val record = ChatGptAccountRecord(
            id = "test-${UUID.randomUUID()}", clientId = "client", issuer = CHATGPT_ISSUER, subject = "subject",
            hostId = "host", idToken = "id", accessToken = "access", refreshToken = "refresh",
            scopes = setOf(CHATGPT_PLAN_SCOPE, CHATGPT_RESOURCE_INVOKE_SCOPE), accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE, savedAtEpochSeconds = 1,
        )
        try {
            accounts.put(record)
            auth.refresh()
            assertEquals(ChatGptAuthPhase.CONNECTED, auth.state.value.phase)
            val models = auth.state.value.models
            offline = true
            auth.refresh()
            assertEquals(ChatGptAuthPhase.UNVERIFIED, auth.state.value.phase)
            assertFalse(auth.state.value.connected)
            assertEquals(models, auth.state.value.models)
            assertEquals(record, accounts.get(record.id))
            assertNotNull(auth.state.value.error)
            offline = false
            auth.refresh()
            assertEquals(ChatGptAuthPhase.CONNECTED, auth.state.value.phase)
            assertEquals(record, accounts.get(record.id))
            // A late successful probe must not reconnect an account the user has disconnected.
            delayProbe = true
            val probe = async(Dispatchers.Default) { auth.refresh() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val disconnect = async(Dispatchers.Default) { auth.disconnect(record.id) }
            release.countDown()
            withTimeout(5_000) { probe.await(); disconnect.await() }
            delayProbe = false
            auth.refresh()
            assertEquals(ChatGptAuthPhase.DISCONNECTED, auth.state.value.phase)
            assertEquals("", accounts.get(record.id)!!.accessToken)
        } finally {
            release.countDown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
