package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptPlanConnectionTesterTest {
    private fun plan(id: String, accountId: String) = LocalModelProfile(
        id = id,
        model = "gpt-test",
        baseUrl = "https://api.openai.com/v1",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = accountId,
    )

    @Test
    fun testNeverCrossesIntoAnotherAuthorizationOrApiKey() {
        val accountA = plan("plan-a", "account-a")
        val accountB = plan("plan-b", "account-b")
        val api = LocalModelProfile(
            id = "api",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1",
            protocol = LocalModelProtocol.RESPONSES,
        )
        assertEquals(
            accountA,
            selectChatGptTestProfile(listOf(api, accountB, accountA), accountB.id, "account-a"),
        )
    }

    @Test
    fun activeProfileIsPreferredOnlyWhenItBelongsToTheRequestedAuthorization() {
        val first = plan("first", "account-a")
        val active = plan("active", "account-a")
        assertEquals(
            active,
            selectChatGptTestProfile(listOf(first, active), active.id, "account-a"),
        )
        assertNull(selectChatGptTestProfile(listOf(first, active), active.id, "missing"))
    }
}
