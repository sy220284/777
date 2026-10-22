package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelCompletionReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatReplyVisibilityTest {
    @Test fun ordinaryChatMustNotCommitInvisibleAssistantReply() {
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireVisibleLocalChatReply(emptyList())
        }
        assertEquals("模型没有返回可用回复", failure.message)
    }

    @Test fun partialRegenerationCannotReplacePreviouslyCommittedAnswer() {
        for (reason in listOf(
            LocalModelCompletionReason.OUTPUT_LIMIT,
            LocalModelCompletionReason.REFUSED,
            LocalModelCompletionReason.INTERRUPTED,
        )) {
            val reply = LocalModelReply(
                message = buildJsonObject { put("role", "assistant"); put("content", "半段新回复") },
                content = "半段新回复",
                reasoning = null,
                toolCalls = emptyList(),
                completionReason = reason,
            )
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                requireCompleteChatRegeneration("original-answer", reply)
            }
            org.junit.Assert.assertTrue(failure.message.orEmpty().contains("原回复和会话状态保持不变"))
            requireCompleteChatRegeneration(null, reply)
        }
        val completed = LocalModelReply(
            message = buildJsonObject { put("role", "assistant"); put("content", "完整新回复") },
            content = "完整新回复",
            reasoning = null,
            toolCalls = emptyList(),
            completionReason = LocalModelCompletionReason.COMPLETED,
        )
        requireCompleteChatRegeneration("original-answer", completed)
    }

    @Test fun visibleTextAndImageOnlyRepliesRemainDeliverable() {
        requireVisibleLocalChatReply(listOf(LocalMessageBlock.Text("这是一条回复")))
        // Image-containing replies are materialized into Image blocks by the media owner;
        // delivery admission is based on the resulting non-empty block list, not text length.
    }
}
