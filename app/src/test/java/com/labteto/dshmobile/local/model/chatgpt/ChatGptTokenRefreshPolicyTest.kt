package com.labteto.dshmobile.local.model.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptTokenRefreshPolicyTest {
    private fun record(now: Long, expiresIn: Long, earliestIn: Long?): ChatGptAccountRecord =
        ChatGptAccountRecord(
            id = "a", clientId = "issued", issuer = CHATGPT_ISSUER, subject = "subject",
            hostId = "urn:uuid:test", idToken = "id", accessToken = "access", refreshToken = "refresh",
            accessTokenExpiresAtEpochSeconds = now + expiresIn, savedAtEpochSeconds = now,
            earliestRefreshAtEpochSeconds = earliestIn?.let { now + it },
        )

    @Test
    fun respectsEarliestRefreshButNeverUsesExpiredAccessToken() {
        val now = 1_800_000_000L
        assertFalse(shouldRefreshChatGptToken(record(now, 3600L, 1800L), now))
        assertFalse(shouldRefreshChatGptToken(record(now, 65L, 50L), now))
        assertTrue(shouldRefreshChatGptToken(record(now, 60L, 0L), now))
        assertTrue(shouldRefreshChatGptToken(record(now, 3L, 50L), now))
        assertTrue(shouldRefreshChatGptToken(record(now, -1L, 50L), now))
        assertTrue(shouldRefreshChatGptToken(record(now, 65L, null), now))
    }
}
