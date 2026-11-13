package com.labteto.dshmobile.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxQuotaEntryTest {
    @Test
    fun onlyOfficialMiniMaxApiProfilesExposeAccountQuota() {
        val profile = LocalModelProfile("mini", "MiniMax-M3", "https://api.minimaxi.com/v1")
        assertTrue(profile.canQueryMiniMaxQuota())
        assertTrue(profile.copy(baseUrl = "https://api.minimax.io/v1").canQueryMiniMaxQuota())
        assertFalse(profile.copy(model = "deepseek-chat").canQueryMiniMaxQuota())
        assertFalse(profile.copy(baseUrl = "https://proxy.example/v1").canQueryMiniMaxQuota())
        assertFalse(profile.copy(authKind = LocalModelAuthKind.CHATGPT_PLAN).canQueryMiniMaxQuota())
    }
}
