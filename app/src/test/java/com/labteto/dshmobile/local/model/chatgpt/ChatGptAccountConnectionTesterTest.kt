package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatGptAccountConnectionTesterTest {
    private fun plan(id: String, accountId: String) = LocalModelProfile(
        id = id,
        model = "gpt-test",
        baseUrl = "https://api.openai.com/v1",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
        credentialRef = accountId,
    )

    @Test
    fun selectedAccountProbeNeverBorrowsAnotherAuthorization() {
        val first = plan("first", "account-a")
        val second = plan("second", "account-b")
        assertEquals(
            first,
            selectChatGptProbeProfile(
                profiles = listOf(first, second),
                activeProfileId = second.id,
                accountId = "account-a",
            ),
        )
    }

    @Test
    fun activeRouteIsPreferredInsideTheSameAuthorization() {
        val first = plan("first", "account-a")
        val second = first.copy(id = "second", model = "gpt-test-2")
        assertEquals(
            second,
            selectChatGptProbeProfile(
                profiles = listOf(first, second),
                activeProfileId = second.id,
                accountId = "account-a",
            ),
        )
    }

    @Test
    fun missingAuthorizationHasNoProbeRoute() {
        assertNull(
            selectChatGptProbeProfile(
                profiles = listOf(plan("first", "account-a")),
                activeProfileId = null,
                accountId = "missing",
            ),
        )
    }
}
