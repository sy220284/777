package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.LocalChatBranchNode
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.activeChatBranchMessages
import com.labteto.dshmobile.local.chat.activeTranscriptForUserEdit
import com.labteto.dshmobile.local.chat.appendMaterializedChatBranchMessage
import com.labteto.dshmobile.local.chat.chatBranchInfo
import com.labteto.dshmobile.local.chat.chatBranchLastContext
import com.labteto.dshmobile.local.chat.chatBranchingEligible
import com.labteto.dshmobile.local.chat.decodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.editableChatUserText
import com.labteto.dshmobile.local.chat.encodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.hasChatBranchAlternatives
import com.labteto.dshmobile.local.chat.replayHardChatContextFromTranscript
import com.labteto.dshmobile.local.chat.restoreMaterializedChatBranchState
import com.labteto.dshmobile.local.chat.rewriteChatTranscriptFromUserEdit
import com.labteto.dshmobile.local.chat.selectChatBranchVariant
import com.labteto.dshmobile.local.chat.syncChatBranchState
import com.labteto.dshmobile.local.chat.syncMaterializedChatBranchState
import com.labteto.dshmobile.local.chat.upsertChatBranchNode
import com.labteto.dshmobile.local.chat.withEditedChatUserText
import com.labteto.dshmobile.local.session.LocalHarnessMessage
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
    fun legacyBranchGraphCanStillRepresentSiblingUserTurns() {
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
    fun historicalUserEditDropsOriginalTurnAndEveryLaterMessage() {
        val transcript = (1..100).map { index ->
            message(
                id = "m$index",
                role = "user",
                content = "消息$index",
                at = index.toLong(),
            )
        }
        val edited = message("edited-50", "user", "修改后的第50条", 101)

        val rewritten = rewriteChatTranscriptFromUserEdit(
            activeMessages = transcript,
            originalMessageId = "m50",
            editedMessage = edited,
        )!!

        assertEquals(50, rewritten.size)
        assertEquals((1..49).map { "m$it" } + "edited-50", rewritten.map { it.id })
        assertTrue(rewritten.none { it.id == "m50" })
        assertTrue(rewritten.none { it.id in (51..100).map { n -> "m$n" }.toSet() })
    }

    @Test
    fun historicalEditReadsDurablePrefixWhenMessageIsOutsideHotWindow() {
        val history = (1..100).map { index ->
            message("m$index", "user", "消息$index", index.toLong())
        }
        var durableReads = 0
        val active = activeTranscriptForUserEdit(
            messageId = "m50",
            activeBranch = emptyList(),
            hotMessages = history.takeLast(20),
            loadDurableTranscript = { durableReads++; history },
        )
        val rewritten = rewriteChatTranscriptFromUserEdit(
            active, "m50", message("edited", "user", "改后的第50条", 101),
        )!!

        assertEquals(1, durableReads)
        assertEquals((1..49).map { "m$it" } + "edited", rewritten.map { it.id })
    }

    @Test
    fun historicalEditReadsDurablePrefixWhenBranchGraphIsIncomplete() {
        val history = (1..100).map { index ->
            message("m$index", "user", "消息$index", index.toLong())
        }
        val active = activeTranscriptForUserEdit(
            messageId = "m50",
            activeBranch = history.takeLast(20),
            hotMessages = history.takeLast(20),
            loadDurableTranscript = { history },
        )

        assertEquals(history, active)
    }

    @Test
    fun visibleHotTargetStillKeepsDurablePrefixWhenRuntimeCountCouldBeStale() {
        val history = (1..100).map { index ->
            message("m$index", "user", "消息$index", index.toLong())
        }
        var durableReads = 0

        val active = activeTranscriptForUserEdit(
            messageId = "m90",
            activeBranch = emptyList(),
            hotMessages = history.takeLast(20),
            loadDurableTranscript = { durableReads++; history },
        )

        assertEquals(1, durableReads)
        assertEquals((1..100).map { "m$it" }, active.map { it.id })
    }

    @Test
    fun historicalEditMergesDurablePrefixWithVisibleHotTailWhenArchiveLags() {
        val durable = (1..40).map { index ->
            message("m$index", "user", "消息$index", index.toLong())
        }
        val hotTail = (41..60).map { index ->
            message("m$index", "user", "消息$index", index.toLong())
        }

        val active = activeTranscriptForUserEdit(
            messageId = "m50",
            activeBranch = emptyList(),
            hotMessages = hotTail,
            loadDurableTranscript = { durable },
        )
        val rewritten = rewriteChatTranscriptFromUserEdit(
            activeMessages = active,
            originalMessageId = "m50",
            editedMessage = message("edited", "user", "修改后的第50条", 61),
        )!!

        assertEquals((1..60).map { "m$it" }, active.map { it.id })
        assertEquals((1..49).map { "m$it" } + "edited", rewritten.map { it.id })
    }

    @Test
    fun historicalEditUsesLiveTailOverStaleDurableOverlap() {
        val durable = (1..55).map { index ->
            message("m$index", "user", "旧消息$index", index.toLong())
        }
        val hotTail = (51..60).map { index ->
            message("m$index", "user", "当前消息$index", index.toLong())
        }

        val active = activeTranscriptForUserEdit(
            messageId = "m58",
            activeBranch = emptyList(),
            hotMessages = hotTail,
            loadDurableTranscript = { durable },
        )

        assertEquals((1..60).map { "m$it" }, active.map { it.id })
        assertEquals("当前消息51", active.first { it.id == "m51" }.content)
        assertEquals("当前消息55", active.first { it.id == "m55" }.content)
    }

    @Test
    fun historicalEditDoesNotRetainStaleDurableFutureWhenIdsDoNotOverlap() {
        val durable = (1..80).map { index ->
            message("old-$index", "user", "旧时间线$index", index.toLong())
        }
        val liveTail = (41..60).map { index ->
            message("live-$index", "user", "当前时间线$index", index.toLong())
        }

        val active = activeTranscriptForUserEdit(
            messageId = "live-50",
            activeBranch = emptyList(),
            hotMessages = liveTail,
            loadDurableTranscript = { durable },
        )

        assertEquals(
            (1..40).map { "old-$it" } + (41..60).map { "live-$it" },
            active.map { it.id },
        )
        assertTrue(active.none { it.id in (41..80).map { n -> "old-$n" }.toSet() })
    }

    @Test
    fun historicalEditReplayDoesNotKeepDeletedFutureLocation() {
        val retained = listOf(
            message("u1", "user", "进去说吧。", 1),
            message("a1", "assistant", "她和你一起走进房间。", 2),
        )
        val deletedFuture = retained + listOf(
            message("u2", "user", "出去走走。", 3),
            message("a2", "assistant", "两人一起走到院子。", 4),
        )

        val beforeEdit = replayHardChatContextFromTranscript(
            messages = deletedFuture,
            generation = 1L,
        )
        val afterEdit = replayHardChatContextFromTranscript(
            messages = retained,
            generation = 2L,
        )

        assertEquals("院子", beforeEdit.scene.location)
        assertEquals("房间", afterEdit.scene.location)
        assertTrue(afterEdit.sceneEvents.none { it.to == "院子" })
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
    fun appendingMaterializedMessageKeepsLinearChatUnmaterialized() {
        val user = message("u1", "user", "你好", 1)
        val reply = message("a1", "assistant", "你好。", 2)

        val afterUser = appendMaterializedChatBranchMessage(
            current = LocalChatBranchState(),
            activeMessages = emptyList(),
            message = user,
            parentId = null,
            chatState = ChatCharacterState(),
        )
        val afterReply = appendMaterializedChatBranchMessage(
            current = afterUser,
            activeMessages = listOf(user),
            message = reply,
            parentId = user.id,
            chatState = ChatCharacterState(),
        )

        assertTrue(afterUser.nodes.isEmpty())
        assertTrue(afterReply.nodes.isEmpty())
    }

    @Test
    fun appendingMaterializedMessageExtendsExistingRealBranch() {
        val user = message("u1", "user", "在吗", 1)
        val oldReply = message("a1", "assistant", "在。", 2)
        val newReply = message("a2", "assistant", "在啊。", 3)
        val branched = upsertChatBranchNode(
            syncChatBranchState(
                current = LocalChatBranchState(),
                activeMessages = listOf(user, oldReply),
                chatState = ChatCharacterState(),
                replySuggestions = emptyList(),
            ),
            LocalChatBranchNode(
                message = newReply,
                parentId = user.id,
                chatStateAfter = ChatCharacterState(mood = "新分支"),
            ),
            select = true,
        )
        val nextUser = message("u2", "user", "继续", 4)

        val extended = appendMaterializedChatBranchMessage(
            current = branched,
            activeMessages = listOf(user, newReply),
            message = nextUser,
            parentId = newReply.id,
            chatState = ChatCharacterState(mood = "新分支"),
        )

        assertEquals(listOf("u1", "a2", "u2"), activeChatBranchMessages(extended).map { it.id })
        assertTrue(hasChatBranchAlternatives(extended))
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
    fun restoringRealBranchDoesNotReparentFromTruncatedHotWindow() {
        val rootUser = message("u1", "user", "第一句", 1)
        val oldReply = message("a1", "assistant", "旧回答", 2)
        val newReply = message("a2", "assistant", "新回答", 3)
        val nextUser = message("u2", "user", "继续", 4)
        var branches = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(rootUser, oldReply),
            chatState = ChatCharacterState(),
            replySuggestions = emptyList(),
        )
        branches = upsertChatBranchNode(
            branches,
            LocalChatBranchNode(newReply, parentId = rootUser.id),
            select = true,
        )
        branches = appendMaterializedChatBranchMessage(
            current = branches,
            activeMessages = listOf(newReply),
            message = nextUser,
            parentId = newReply.id,
            chatState = ChatCharacterState(),
        )

        val restored = restoreMaterializedChatBranchState(
            current = branches,
            activeMessages = listOf(nextUser),
            chatState = ChatCharacterState(),
            replySuggestions = emptyList(),
        )

        assertEquals(listOf("u1", "a2", "u2"), activeChatBranchMessages(restored).map { it.id })
        assertEquals("a2", restored.nodes.first { it.message.id == "u2" }.parentId)
    }

    @Test
    fun consecutiveUserTurnsRemainEligibleOnceTheConversationIsIdle() {
        val first = message("u1", "user", "第一条", 1)
        val queued = message("u2", "user", "排队补充", 2)

        assertTrue(chatBranchingEligible(listOf(first, queued)))
    }

    @Test
    fun groupChatWithSeveralAssistantRepliesRemainsEditable() {
        val user = message("u1", "user", "你们都说说", 1)
        val firstReply = message("a1", "assistant", "我先来。", 2)
        val secondReply = message("a2", "assistant", "那我接着说。", 3)
        val nextUser = message("u2", "user", "继续", 4)

        assertTrue(chatBranchingEligible(listOf(user, firstReply, secondReply, nextUser)))
    }

    @Test
    fun editingAttachmentMessageChangesOnlyUserTextAndKeepsAttachmentContext() {
        val original = message(
            "u1",
            "user",
            "看看这个\n\n本次附件已导入本机工作区：\n- 图片：a.png → .dsh/attachments/a.png（12 B）",
            1,
        )

        assertEquals("看看这个", editableChatUserText(original))
        val edited = withEditedChatUserText(original, "重新看一下重点")
        assertTrue(edited.startsWith("重新看一下重点"))
        assertTrue(edited.contains("本次附件已导入本机工作区："))
        assertTrue(edited.contains(".dsh/attachments/a.png"))
        assertTrue(chatBranchingEligible(listOf(original)))
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
    fun switchingBranchRestoresItsOwnSceneContext() {
        val user = message("u1", "user", "去哪边", 1)
        val courtyard = message("a1", "assistant", "留在院子。", 2)
        var branches = syncChatBranchState(
            current = LocalChatBranchState(),
            activeMessages = listOf(user, courtyard),
            chatState = ChatCharacterState(),
            replySuggestions = emptyList(),
            chatContext = ChatContextState(scene = ChatSceneState(location = "院子")),
        )
        val room = message("a2", "assistant", "回到房间。", 3)
        branches = upsertChatBranchNode(
            branches,
            LocalChatBranchNode(
                message = room,
                parentId = user.id,
                chatStateAfter = ChatCharacterState(),
                chatContextAfter = ChatContextState(scene = ChatSceneState(location = "房间")),
            ),
            select = true,
        )

        assertEquals("房间", chatBranchLastContext(branches)!!.scene.location)

        val oldSelected = selectChatBranchVariant(branches, "a2", 0)!!
        assertEquals("院子", chatBranchLastContext(oldSelected)!!.scene.location)
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
