package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRelationshipEngineTest {
    private val engine = ChatRelationshipEngine()

    @Test fun ordinaryEmotionalChatStaysImmersive() {
        assertEquals(ChatRelationshipEngine.View.IMMERSIVE, engine.classify("今天有点烦，陪我聊会儿"))
        assertEquals(ChatRelationshipEngine.View.IMMERSIVE, engine.classify("她今天又没回我，气死了"))
    }

    @Test fun explicitRelationshipAnalysisSwitchesToStrategist() {
        assertEquals(ChatRelationshipEngine.View.STRATEGIST, engine.classify("帮我分析一下，她这是什么意思"))
        assertEquals(ChatRelationshipEngine.View.STRATEGIST, engine.classify("/strategy 我们现在什么阶段"))
        assertEquals(ChatRelationshipEngine.View.STRATEGIST, engine.classify("军师，她最近突然冷淡了"))
    }

    @Test fun replyRequestGetsDedicatedCoachView() {
        assertEquals(ChatRelationshipEngine.View.REPLY_COACH, engine.classify("她说今天好累，这句怎么回"))
        assertEquals(ChatRelationshipEngine.View.REPLY_COACH, engine.classify("帮我回：我刚到家"))
    }

    @Test fun unrelatedAnalysisDoesNotTriggerRelationshipStrategist() {
        assertEquals(ChatRelationshipEngine.View.IMMERSIVE, engine.classify("帮我分析一下这部电影的结局"))
    }

    @Test fun promptLocksContinuityAndEvidenceBoundaries() {
        val prompt = engine.prompt("今天随便聊聊")
        assertTrue(prompt.contains("事实、推测、未知"))
        assertTrue(prompt.contains("关系变化必须渐进"))
        assertTrue(prompt.contains("不要为了讨好永远顺着用户"))
        assertTrue(prompt.contains("本轮视角：角色本人"))
        assertFalse(prompt.contains("用户喜欢就自动升级关系"))
    }

    @Test fun replyCoachRequiresSendableAnswerFirst() {
        val prompt = engine.prompt("这句怎么回她")
        assertTrue(prompt.contains("第一屏先给能直接复制发送的成品"))
        assertTrue(prompt.contains("最多三条"))
        assertTrue(prompt.contains("稳一点"))
        assertTrue(prompt.contains("有张力一点"))
        assertTrue(prompt.contains("收一点"))
    }
}
