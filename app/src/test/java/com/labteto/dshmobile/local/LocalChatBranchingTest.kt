package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatBranchingTest {
    private fun message(id: String, role: String, content: String, at: Long) =
        LocalHarnessMessage(id = id, role = role, content = content, createdAt = at)

    @Test
    fun regeneratedAssistantKeepsOldReplyAndCanSwitchBothWays() {
        val user = message("u1", "user", "在吗", 1)
        val oldReply = message("a1", "assistant", "在。", 2)
        var branches = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(user, oldReply),
            chatState = ChatCharacterState(mood = "平静"),
            replySuggestions = emptyList(),
        )
        val newReply = message("a2", "assistant", "在啊，怎么突然找我？", 3)
        branches = upsertChatBranchNode(
            branches,
            LocalChatBranchNode(
                message = newReply,
                parentId = user.id,
                chatStateAfter = ChatCharacterState(mood = "好奇"),
            ),
            select = true,
        )

        assertEquals(listOf("u1", "a2"), activeChatBranchMessages(branches).map { it.id })
        val info = chatBranchInfo(branches, "a2")
        assertNotNull(info)
        assertEquals(1, info!!.index)
        assertEquals(2, info.count)

        val oldSelected = selectChatBranchVariant(branches, "a2", 0)!!
        assertEquals(listOf("u1", "a1"), activeChatBranchMessages(oldSelected).map { it.id })

        val newSelected = selectChatBranchVariant(oldSelected, "a1", 1)!!
        assertEquals(listOf("u1", "a2"), activeChatBranchMessages(newSelected).map { it.id })
    }

    @Test
    fun editedUserCreatesSiblingAndOriginalDownstreamBranchStaysIntact() {
        val firstUser = message("u1", "user", "第一句", 1)
        val firstAnswer = message("a1", "assistant", "第一答", 2)
        val originalUser = message("u2", "user", "原消息", 3)
        val originalAnswer = message("a2", "assistant", "原回答", 4)
        var branches = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(firstUser, firstAnswer, originalUser, originalAnswer),
            chatState = ChatCharacterState(mood = "原分支"),
            replySuggestions = listOf(ChatReplySuggestion(label = "原", text = "原")),
        )

        val editedUser = message("u3", "user", "修改后的消息", 5)
        branches = upsertChatBranchNode(
            branches,
            LocalChatBranchNode(
                message = editedUser,
                parentId = firstAnswer.id,
                chatStateAfter = ChatCharacterState(mood = "分支前"),
            ),
            select = true,
        )
        assertEquals(listOf("u1", "a1", "u3"), activeChatBranchMessages(branches).map { it.id })

        val editedInfo = chatBranchInfo(branches, "u3")!!
        assertEquals(2, editedInfo.count)

        val originalSelected = selectChatBranchVariant(branches, "u3", 0)!!
        assertEquals(
            listOf("u1", "a1", "u2", "a2"),
            activeChatBranchMessages(originalSelected).map { it.id },
        )
    }

    @Test
    fun linearChatDoesNotMaterializeDuplicateBranchHistory() {
        val user = message("u1", "user", "你好", 1)
        val reply = message("a1", "assistant", "你好。", 2)

        val branches = syncMaterializedChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(user, reply),
            chatState = ChatCharacterState(mood = "自然"),
            replySuggestions = emptyList(),
        )

        assertTrue(branches.nodes.isEmpty())
        assertTrue(branches.selectedChildByParent.isEmpty())
    }

    @Test
    fun restoreDropsLegacyLinearGraphButKeepsRealAlternatives() {
        val user = message("u1", "user", "在吗", 1)
        val oldReply = message("a1", "assistant", "在。", 2)
        val linear = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(user, oldReply),
            chatState = ChatCharacterState(mood = "平静"),
            replySuggestions = emptyList(),
        )

        assertTrue(
            restoreMaterializedChatBranchState(
                current = linear,
                activeMessages = listOf(user, oldReply),
                chatState = ChatCharacterState(mood = "平静"),
                replySuggestions = emptyList(),
            ).nodes.isEmpty(),
        )

        val newReply = message("a2", "assistant", "在啊。", 3)
        val branched = upsertChatBranchNode(
            linear,
            LocalChatBranchNode(
                message = newReply,
                parentId = user.id,
                chatStateAfter = ChatCharacterState(mood = "好奇"),
            ),
            select = true,
        )
        val restored = restoreMaterializedChatBranchState(
            current = branched,
            activeMessages = listOf(user, newReply),
            chatState = ChatCharacterState(mood = "好奇"),
            replySuggestions = emptyList(),
        )

        assertTrue(hasChatBranchAlternatives(restored))
        assertEquals(listOf("u1", "a2"), activeChatBranchMessages(restored).map { it.id })
    }

    @Test
    fun consecutiveUserTurnsAreNotEligibleForBranchEditing() {
        val first = message("u1", "user", "第一条", 1)
        val queued = message("u2", "user", "排队补充", 2)

        assertTrue(!chatBranchingEligible(listOf(first, queued)))
    }

    @Test
    fun proactiveAssistantMessageDoesNotBreakNormalTurnBranching() {
        val user = message("u1", "user", "晚点找我", 1)
        val reply = message("a1", "assistant", "好。", 2)
        val proactive = LocalHarnessMessage(
            id = "a2",
            role = "assistant",
            content = "你不是说晚点找你么，我来了。",
            createdAt = 3,
            proactive = true,
        )

        assertTrue(chatBranchingEligible(listOf(user, reply, proactive)))

        val branches = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(user, reply, proactive),
            chatState = ChatCharacterState(mood = "主动"),
            replySuggestions = emptyList(),
        )
        assertEquals(listOf("u1", "a1", "a2"), activeChatBranchMessages(branches).map { it.id })
    }

    @Test
    fun branchStateRoundTripsThroughEventPayload() {
        val user = message("u1", "user", "你好", 1)
        val state = upsertChatBranchNode(
            LocalChatBranchState(),
            LocalChatBranchNode(
                message = user,
                chatStateAfter = ChatCharacterState(mood = "自然"),
            ),
            select = true,
        )

        val decoded = decodeChatBranchStateEvent(encodeChatBranchStateEvent(state))
        assertEquals(state, decoded)
        assertTrue(chatBranchingEligible(activeChatBranchMessages(decoded!!)))
    }
}
