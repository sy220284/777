package com.labteto.dshmobile.local.model.chatgpt

import org.junit.Assert.assertFalse
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
}
