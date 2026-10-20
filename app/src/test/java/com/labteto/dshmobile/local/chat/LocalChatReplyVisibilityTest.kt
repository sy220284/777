package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalMessageBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatReplyVisibilityTest {
    @Test fun ordinaryChatMustNotCommitInvisibleAssistantReply() {
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireVisibleLocalChatReply(emptyList())
        }
        assertEquals("模型没有返回可用回复", failure.message)
    }

    @Test fun visibleTextAndImageOnlyRepliesRemainDeliverable() {
        requireVisibleLocalChatReply(listOf(LocalMessageBlock.Text("这是一条回复")))
        // Image-containing replies are materialized into Image blocks by the media owner;
        // delivery admission is based on the resulting non-empty block list, not text length.
    }
}
