package com.labteto.dshmobile.local.model.chatgpt

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGptRefreshLifecycleAndroidTest {
    @Test
    fun lateRefreshCannotRecreateRemovedCredentialsOrReconnectDisconnectedAccount() = runBlocking {
        for (remove in listOf(false, true)) withAccounts { accounts ->
            val original = record()
            accounts.put(original)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sessions = ChatGptSessionManager(accounts, delayedTokenClient(entered, release, 200), Json)
            val refresh = async(Dispatchers.Default) { runCatching { sessions.accessToken(original.id) } }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (remove) accounts.remove(original.id) else accounts.clearCredentials(original.id)
                release.countDown()
                assertTrue(withTimeout(3_000) { refresh.await() }.isFailure)
                if (remove) {
                    assertNull(accounts.get(original.id))
                    assertTrue(accounts.list().isEmpty())
                } else {
                    assertEquals("", accounts.get(original.id)!!.refreshToken)
                    assertEquals("", accounts.get(original.id)!!.accessToken)
                }
            } finally { release.countDown(); refresh.cancelAndJoin() }
        }
    }

    @Test
    fun staleInvalidGrantCannotInvalidateANewerLogin() = runBlocking {
        withAccounts { accounts ->
            val original = record()
            accounts.put(original)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sessions = ChatGptSessionManager(accounts, delayedTokenClient(entered, release, 400), Json)
            val refresh = async(Dispatchers.Default) { runCatching { sessions.accessToken(original.id) } }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val replacement = original.copy(accessToken = "new-login", refreshToken = "new-refresh")
                accounts.put(replacement)
                release.countDown()
                assertTrue(withTimeout(3_000) { refresh.await() }.isFailure)
                assertEquals(replacement, accounts.get(original.id))
            } finally { release.countDown(); refresh.cancelAndJoin() }
        }
    }

    @Test
    fun normalRefreshUpdatesCredentialsWithoutChangingSelectedAccount() = runBlocking {
        withAccounts { accounts ->
            val original = record()
            val other = original.copy(id = "other", accessToken = "other-token")
            accounts.put(original)
            accounts.put(other)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).apply { countDown() }
            val sessions = ChatGptSessionManager(accounts, delayedTokenClient(entered, release, 200), Json)
            assertEquals("refreshed", sessions.accessToken(original.id))
            assertNotNull(accounts.get(original.id))
            assertEquals(other.id, accounts.selectedId())
            assertEquals("refreshed", accounts.get(original.id)!!.accessToken)
        }
    }

    private suspend fun withAccounts(test: suspend (ChatGptAccountStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.cacheDir.resolve("refresh-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try { test(ChatGptAccountStore(preferences, Json)) } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    private fun record() = ChatGptAccountRecord(
        id = "test-account", clientId = "client", issuer = CHATGPT_ISSUER, subject = "subject",
        hostId = "host", idToken = "id", accessToken = "expired", refreshToken = "refresh",
        scopes = setOf(CHATGPT_PLAN_SCOPE), accessTokenExpiresAtEpochSeconds = 0, savedAtEpochSeconds = 1,
    )

    private fun delayedTokenClient(entered: CountDownLatch, release: CountDownLatch, status: Int) =
        OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            val body = if (status == 200) """{"access_token":"refreshed","expires_in":3600}"""
                else """{"error":"invalid_grant"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
}
