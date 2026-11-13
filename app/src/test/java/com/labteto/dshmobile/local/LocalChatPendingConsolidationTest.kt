package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.renderPendingTurnsForPlanner
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPendingConsolidationTest {
    @Test
    fun plannerTranscriptPreservesRealTurnAlternation() {
        val rendered = renderPendingTurnsForPlanner(
            listOf(
                ChatPendingTurn(
                    sequence = 105L,
                    userMessage = "没事了",
                    assistantMessage = "那就好",
                ),
                ChatPendingTurn(
                    sequence = 101L,
                    userMessage = "我生气了",
                    assistantMessage = "对不起",
                ),
            ),
        )

        val firstUser = rendered.indexOf("#101\n用户：我生气了")
        val firstAssistant = rendered.indexOf("角色：对不起")
        val secondUser = rendered.indexOf("#105\n用户：没事了")
        val secondAssistant = rendered.indexOf("角色：那就好")

        assertTrue(firstUser >= 0)
        assertTrue(firstUser < firstAssistant)
        assertTrue(firstAssistant < secondUser)
        assertTrue(secondUser < secondAssistant)
        assertTrue(rendered.contains("后面的回合可以纠正、结束或覆盖前面的临时状态"))
    }
}
