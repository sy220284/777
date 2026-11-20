package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityEvidence
import com.labteto.dshmobile.local.chat.ChatContinuityFactKind
import com.labteto.dshmobile.local.chat.ChatContinuityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStoryOverviewProjectionTest {
    @Test fun sourceableFactsKeepOriginalMessageReferences() {
        val context = ChatContextState(continuity = ChatContinuityState(evidence = listOf(
            ChatContinuityEvidence(kind = ChatContinuityFactKind.EVENT,
                text = "人物已经抵达山庄", sourceAssistantMessageId = "message-7", eventId = "event-7"),
            ChatContinuityEvidence(kind = ChatContinuityFactKind.DECISION,
                text = "决定明天启程", sourceUserMessageId = "message-8", eventId = "decision-8"),
            ChatContinuityEvidence(kind = ChatContinuityFactKind.OPEN_THREAD,
                text = "仍待确认路线", eventId = "thread-9"),
        )))
        val result = chatStoryOverviewItems(context)
        assertEquals(3, result.size)
        assertEquals("message-7", result[0].sourceMessageId)
        assertEquals("message-8", result[1].sourceMessageId)
        assertNull(result[2].sourceMessageId)
    }

    @Test fun emptyStoryHasNoInventedEvents() {
        assertEquals(emptyList<ChatStoryOverviewItem>(), chatStoryOverviewItems(ChatContextState()))
    }
}
