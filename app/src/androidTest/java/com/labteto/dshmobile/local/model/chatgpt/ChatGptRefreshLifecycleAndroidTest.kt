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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    val disconnected = accounts.get(original.id)!!
                    assertEquals("", disconnected.refreshToken)
                    assertEquals("", disconnected.accessToken)
                    assertNull(disconnected.email)
                    assertNull(disconnected.displayName)
                }
            } finally { release.countDown(); refresh.cancelAndJoin() }
        }
    }

    @Test
    fun accountStringNeverContainsRawCredentials() {
        val account = record()
        val rendered = account.toString()
        assertFalse(rendered.contains(account.idToken))
        assertFalse(rendered.contains(account.accessToken))
        assertFalse(rendered.contains(account.refreshToken))
        assertTrue(rendered.contains("credentials=<redacted>"))
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

    @Test
    fun cancellationAfterReceivingRotatedTokenStillPersistsItWithoutReturningSuccess() = runBlocking {
        withAccounts { accounts ->
            val original = record()
            accounts.put(original)
            lateinit var refresh: Deferred<String>
            val bytes = Buffer().writeUtf8("""{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""")
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (bytes.size > 0) return bytes.read(sink, byteCount)
                    refresh.cancel()
                    return -1
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(object : ResponseBody() {
                        override fun contentType(): okhttp3.MediaType? = null
                        override fun contentLength(): Long = -1
                        override fun source(): BufferedSource = source
                    }).build()
            }.build()
            val sessions = ChatGptSessionManager(accounts, http, Json)
            refresh = async(Dispatchers.Default, start = CoroutineStart.LAZY) { sessions.accessToken(original.id) }
            refresh.start()
            refresh.join()
            assertTrue(refresh.isCancelled)
            assertEquals("rotated", accounts.get(original.id)!!.refreshToken)
            assertEquals("new-access", accounts.get(original.id)!!.accessToken)
            assertEquals(original.id, accounts.selectedId())
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
        email = "user@example.com", displayName = "测试用户",
        hostId = "host", idToken = "id-token-secret", accessToken = "access-token-secret", refreshToken = "refresh-token-secret",
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
