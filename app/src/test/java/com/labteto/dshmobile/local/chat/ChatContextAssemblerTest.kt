package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextAssemblerTest {
    @Test
    fun lowInformationRepliesDoNotRecallLongTermMemory() {
        assertFalse(ChatMemorySelector.shouldRecall("嗯"))
        assertFalse(ChatMemorySelector.shouldRecall("继续"))
        assertFalse(ChatMemorySelector.shouldRecall("然后呢"))
        assertTrue(ChatMemorySelector.shouldRecall("你还记得我们第一次见面吗"))
        assertTrue(ChatMemorySelector.shouldRecall("你喜欢我吗"))
        assertTrue(ChatMemorySelector.shouldRecall("还爱我吗"))
        assertTrue(ChatMemorySelector.shouldRecall("我们算什么关系"))
        assertFalse(ChatMemorySelector.shouldRecall("喜欢"))
    }

    @Test
    fun assemblerDropsMemoryThatDuplicatesCurrentState() {
        val context = ChatContextAssembler.assemble(
            dynamicPrompt = "【当前状态】\n关注：昨天的争执",
            relationshipMemory = "【本轮相关长期记忆】\n- 关注：昨天的争执\n- 她平时喜欢喝茶",
            userInput = "继续",
        )
        assertTrue(context.contains("昨天的争执"))
        assertTrue(context.contains("她平时喜欢喝茶"))
        assertTrue(context.indexOf("昨天的争执") == context.lastIndexOf("昨天的争执"))
        assertTrue(context.contains("不主动复述"))
    }

    @Test
    fun repetitionGuardKeepsExtendedSentenceWithNewInformation() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了，因为我已经把门锁好了。我们先去看看门外是谁。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )

        assertTrue(result.text.contains("因为我已经把门锁好了"))
        assertTrue(result.text.contains("我们先去看看门外是谁"))
    }

    @Test
    fun repetitionGuardRemovesRepeatedSentenceWhenReplyAlsoHasNewContent() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了。我们先去看看门外是谁。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )
        assertFalse(result.text.contains("这件事你不用再担心了"))
        assertTrue(result.text.contains("我们先去看看门外是谁"))
        assertTrue(result.repeatedSegments.isNotEmpty())
    }

    @Test
    fun repetitionGuardKeepsOppositePolarityCorrection() {
        val result = ChatRepetitionGuard.filter(
            candidate = "我已经决定明天不去城南了，我们改到后天再说。",
            recentAssistantReplies = listOf("我已经决定明天去城南了，我们九点出发。"),
        )

        assertTrue(result.text.contains("明天不去城南"))
        assertTrue(result.text.contains("改到后天"))
    }

    @Test
    fun repetitionGuardStillRemovesSamePolarityParaphrase() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了。我们去看看门外。",
            recentAssistantReplies = listOf("这件事不用担心了。"),
        )

        assertFalse(result.text.contains("不用再担心"))
        assertTrue(result.text.contains("去看看门外"))
    }

    @Test
    fun recentRoleplayBeatsAreInjectedAsSemanticNoveltyGuard() {
        val rendered = ChatContextAssembler.assemble(
            dynamicPrompt = "【当前状态】\n情绪=自然",
            relationshipMemory = "",
            userInput = "继续",
            recentAssistantReplies = listOf(
                "她轻轻叹了口气，转头看向窗外。",
                "她沉默片刻，又端起茶喝了一口。",
            ),
        )

        assertTrue(rendered.contains("近期已使用互动节拍"))
        assertTrue(rendered.contains("叹气"))
        assertTrue(rendered.contains("看向别处"))
        assertTrue(rendered.contains("沉默停顿"))
        assertTrue(rendered.contains("端起饮品"))
        assertTrue(rendered.contains("不只换同义词"))
    }
}
