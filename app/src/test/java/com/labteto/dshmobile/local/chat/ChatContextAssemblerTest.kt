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
    fun repetitionGuardRemovesRepeatedSentenceWhenReplyAlsoHasNewContent() {
        val result = ChatRepetitionGuard.filter(
            candidate = "这件事你不用再担心了。我们先去看看门外是谁。",
            recentAssistantReplies = listOf("这件事你不用再担心了。"),
        )
        assertFalse(result.text.contains("这件事你不用再担心了"))
        assertTrue(result.text.contains("我们先去看看门外是谁"))
        assertTrue(result.repeatedSegments.isNotEmpty())
    }
}
