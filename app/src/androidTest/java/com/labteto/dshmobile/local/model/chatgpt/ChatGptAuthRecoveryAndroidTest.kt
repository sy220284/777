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
            scopes = setOf(CHATGPT_PLAN_SCOPE), accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE, savedAtEpochSeconds = 1,
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
