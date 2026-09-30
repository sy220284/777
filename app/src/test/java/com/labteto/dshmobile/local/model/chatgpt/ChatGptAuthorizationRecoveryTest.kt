package com.labteto.dshmobile.local.model.chatgpt

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAuthorizationRecoveryTest {
    @Test
    fun invalidGrantGetsExactlyOneFreshAuthorizationOpportunity() {
        assertTrue(shouldRetryChatGptAuthorization("invalid_grant", allowed = true))
        assertFalse(shouldRetryChatGptAuthorization("invalid_grant", allowed = false))
    }

    @Test
    fun otherOAuthFailuresNeverEnterAutomaticAuthorizationLoop() {
        assertFalse(shouldRetryChatGptAuthorization("access_denied", allowed = true))
        assertFalse(shouldRetryChatGptAuthorization(null, allowed = true))
    }

    @Test
    fun terminalRefreshFailuresInvalidateOnlyTheRenewableSession() {
        listOf(
            "invalid_grant",
            "invalid_refresh_token",
            "token_expired",
            "refresh_token_expired",
            "refresh_token_invalidated",
            "refresh_token_reused",
        ).forEach { assertTrue(shouldInvalidateChatGptRefreshToken(it)) }
    }

    @Test
    fun temporaryRefreshFailuresKeepCredentialsForARealRetry() {
        listOf(null, "temporarily_unavailable", "server_error", "invalid_client").forEach {
            assertFalse(shouldInvalidateChatGptRefreshToken(it))
        }
    }

    @Test
    fun closingCallbackListenerUnblocksAnAbandonedAuthorization() = runBlocking {
        val listener = ChatGptOAuthCallbackServer().open()
        try {
            val waiting = async {
                runCatching { listener.await(timeoutMillis = 10_000L) }.exceptionOrNull()
            }
            listener.close()
            assertNotNull(withTimeout(1_000L) { waiting.await() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun issuedClientIdKeepsSameSubjectRegistrationsDistinctAndStable() {
        val first = ChatGptAccountStore.accountId("oaiapp_first", "subject")
        assertEquals(first, ChatGptAccountStore.accountId("oaiapp_first", "subject"))
        assertNotEquals(first, ChatGptAccountStore.accountId("oaiapp_second", "subject"))
    }
}
