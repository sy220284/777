package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplyImmersionGuardTest {
    @Test
    fun offendingRoleReplyIsRewrittenBeforeCommit() = runTest {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "住在杭州的年轻摄影师，习惯用行动表达关心。",
        )
        var retries = 0
        val events = mutableListOf<String>()

        val result = ChatReplyImmersionGuard.enforce(
            persona = persona,
            initial = "我是AI，没有身体，所以没法抱你。",
            contentOf = { it },
            retry = { hint ->
                retries += 1
                assertTrue(hint.contains("角色沉浸修复"))
                assertTrue(hint.contains("接受、拒绝、躲开、犹豫"))
                "她瞥了你一眼，还是走近抱了一下。"
            },
            onEvent = { action, _ -> events += action },
        )

        assertEquals("她瞥了你一眼，还是走近抱了一下。", result)
        assertEquals(1, retries)
        assertEquals(listOf("retry", "repaired"), events)
    }

    @Test
    fun clearlyAttributedQuotedMaterialDoesNotBlockLegitimateRoleplay() = runTest {
        val persona = PersonaProfile(name = "阿青", portrait = "人类摄影师")
        var attempted = false
        val text = "她读着纸条：“我是AI，没有身体。”然后将纸条揉成一团。"
        val result = ChatReplyImmersionGuard.enforce(
            persona = persona, initial = text, contentOf = { it },
            retry = { attempted = true; "不应触发" },
        )
        assertEquals(text, result)
        assertTrue(!attempted)
    }

    @Test
    fun secondOutOfRoleReplyIsLoggedAndDelivered() = runTest {
        val persona = PersonaProfile(
            name = "阿青",
            portrait = "普通人类，住在杭州。",
        )

        val actions = mutableListOf<String>()
        val result = ChatReplyImmersionGuard.enforce(
            persona = persona,
            initial = "现实中我无法真正触碰你。",
            contentOf = { it },
            retry = { "我没有实体身体，所以还是没法抱你。" },
            onEvent = { action, _ -> actions += action },
        )

        assertEquals("我没有实体身体，所以还是没法抱你。", result)
        assertEquals(listOf("retry", "accepted-with-warning"), actions)
    }

    @Test
    fun failedOptionalImmersionRepairKeepsAlreadyGeneratedReply() = runTest {
        val persona = PersonaProfile(name = "阿青", portrait = "住在杭州的人类摄影师")
        val events = mutableListOf<String>()
        val initial = "我是AI，没有身体。"
        val result = ChatReplyImmersionGuard.enforce(
            persona = persona, initial = initial, contentOf = { it },
            retry = { throw IllegalStateException("模型请求失败") },
            onEvent = { action, _ -> events += action },
        )
        assertEquals(initial, result)
        assertEquals(listOf("retry", "repair-failed-returned-original"), events)
    }

    @Test
    fun unboundGeneralAssistantKeepsExistingBehavior() = runTest {
        var retried = false

        val result = ChatReplyImmersionGuard.enforce(
            persona = PersonaProfile(),
            initial = "作为语言模型，我没有实体身体。",
            contentOf = { it },
            retry = {
                retried = true
                "不应调用"
            },
        )

        assertEquals("作为语言模型，我没有实体身体。", result)
        assertTrue(!retried)
    }
}
