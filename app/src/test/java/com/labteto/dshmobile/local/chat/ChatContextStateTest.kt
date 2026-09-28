package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextStateTest {
    @Test
    fun pendingFactsRemainUntilProcessedSequenceCommits() {
        val base = ChatContextState(
            scene = ChatSceneState(location = "院子"),
            generation = 3L,
        )
        val withPending = base
            .enqueuePending(
                ChatPendingTurn(
                    sequence = 121L,
                    assistantMessageId = "a121",
                    userMessage = "我们进屋吧",
                    assistantMessage = "她和你一起走进屋里。",
                    generation = 3L,
                ),
            )
            .enqueuePending(
                ChatPendingTurn(
                    sequence = 124L,
                    assistantMessageId = "a124",
                    userMessage = "继续",
                    assistantMessage = "她在门边停下。",
                    generation = 3L,
                ),
            )

        assertEquals(listOf(121L, 124L), withPending.pendingTurns.map { it.sequence })

        val committed = withPending.commitProcessed(
            scene = ChatSceneState(location = "屋内"),
            continuity = ChatContinuityState(recentEvents = listOf("两人从院子进入屋内")),
            throughSequence = 121L,
        )

        assertEquals("屋内", committed.scene.location)
        assertEquals(121L, committed.processedThroughSequence)
        assertEquals(listOf(124L), committed.pendingTurns.map { it.sequence })
    }

    @Test
    fun normalizationKeepsStateLightAndBounded() {
        val state = ChatContextState(
            scene = ChatSceneState(
                location = "院子",
                currentEvent = "旧的派生事件",
                lastSceneChange = "房间到院子",
            ),
            continuity = ChatContinuityState(
                recentEvents = (1..8).map { "事件$it" },
                recurringEvents = listOf("反复讨论"),
                decisions = (1..7).map { "决定$it" },
                unfinished = (1..7).map { "待续$it" },
            ),
        ).normalized()

        assertTrue(state.scene.currentEvent.isBlank())
        assertTrue(state.scene.lastSceneChange.isBlank())
        assertTrue(state.continuity.recurringEvents.isEmpty())
        assertEquals(listOf("事件4", "事件5", "事件6", "事件7", "事件8"), state.continuity.recentEvents)
        assertEquals(listOf("决定4", "决定5", "决定6", "决定7"), state.continuity.decisions)
        assertEquals(listOf("待续4", "待续5", "待续6", "待续7"), state.continuity.unfinished)
    }

    @Test
    fun legacyFallbackMergesMissingDomainsWithoutRevivingSoftSceneSnapshot() {
        val current = ChatContextState(
            scene = ChatSceneState(location = "房间"),
            pendingTurns = listOf(
                ChatPendingTurn(
                    sequence = 8L,
                    assistantMessageId = "a8",
                    userMessage = "继续",
                    assistantMessage = "好。",
                    generation = 0L,
                ),
            ),
        )
        val legacy = ChatCharacterState(
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "旧院子",
                participants = listOf("旧人物"),
                positions = listOf("靠着旧墙"),
                activeActions = listOf("喝旧茶"),
                keyObjects = listOf("旧石桌"),
            ),
            continuity = ChatContinuityState(
                recentEvents = listOf("刚确认明日行程"),
                decisions = listOf("明早十点出发"),
                unfinished = listOf("城南之行尚未发生"),
            ),
        )

        val merged = current.withLegacyFallback(legacy)

        assertEquals("房间", merged.scene.location)
        assertEquals("夜晚", merged.scene.sceneTime)
        assertTrue(merged.scene.participants.isEmpty())
        assertTrue(merged.scene.positions.isEmpty())
        assertTrue(merged.scene.activeActions.isEmpty())
        assertTrue(merged.scene.keyObjects.isEmpty())
        assertEquals(listOf("刚确认明日行程"), merged.continuity.recentEvents)
        assertEquals(listOf("明早十点出发"), merged.continuity.decisions)
        assertEquals(listOf("城南之行尚未发生"), merged.continuity.unfinished)
        assertEquals("a8", merged.pendingTurns.single().assistantMessageId)
    }

    @Test
    fun rebasingBranchGenerationKeepsRawPendingButInvalidatesOldWorker() {
        val state = ChatContextState(
            generation = 8L,
            pendingTurns = listOf(
                ChatPendingTurn(
                    sequence = 50L,
                    assistantMessageId = "a50",
                    assistantMessage = "还在院子里。",
                    generation = 8L,
                ),
            ),
        )

        val rebased = state.rebaseGeneration()

        assertEquals(9L, rebased.generation)
        assertEquals(9L, rebased.pendingTurns.single().generation)
        assertEquals("a50", rebased.pendingTurns.single().assistantMessageId)
    }

    @Test
    fun pendingInsideHotWindowUsesMarkerWithoutDuplicatingRawDialogue() {
        val context = ChatContextState(
            scene = ChatSceneState(location = "院子"),
            continuity = ChatContinuityState(decisions = listOf("明早九点出发")),
            generation = 1L,
        ).enqueuePending(
            ChatPendingTurn(
                sequence = 88L,
                assistantMessageId = "a88",
                userMessage = "改成十点吧",
                assistantMessage = "好，明天十点出发。",
                generation = 1L,
            ),
        )

        val rendered = renderChatContextForModel(context)

        assertTrue(rendered.contains("地点=院子"))
        assertTrue(rendered.contains("明早九点出发"))
        assertTrue(rendered.contains("原文已在对话热窗口中"))
        assertTrue(!rendered.contains("改成十点吧"))
        assertTrue(rendered.contains("当前用户输入 > 尚未归并原文 > 当前场景/连续性 > 历史检查点"))
    }

    @Test
    fun oldPendingOutsideHotWindowGetsRawFallback() {
        var context = ChatContextState(generation = 2L)
        repeat(12) { index ->
            context = context.enqueuePending(
                ChatPendingTurn(
                    sequence = (100 + index).toLong(),
                    assistantMessageId = "a$index",
                    userMessage = "旧事实$index",
                    assistantMessage = "角色事实$index",
                    generation = 2L,
                ),
            )
        }

        val rendered = renderChatContextForModel(context)

        assertTrue(rendered.contains("可能已退出热窗口"))
        assertTrue(rendered.contains("旧事实1"))
        assertTrue(!rendered.contains("旧事实11"))
    }

    @Test
    fun repeatedSameSceneMentionDoesNotConsumeHardEventWindow() {
        val moved = ChatContextState(
            scene = ChatSceneState(location = "院子"),
        ).applySceneTurn(
            userMessage = "我们回到房间吧。",
            assistantMessage = "好，回房间。",
            sequence = 10L,
        )
        val repeated = moved.applySceneTurn(
            userMessage = "我们回到房间吧。",
            assistantMessage = "已经在房间了。",
            sequence = 11L,
        )

        assertEquals("房间", moved.scene.location)
        assertEquals(1, moved.sceneEvents.count { it.kind == ChatSceneEventKind.LOCATION })
        assertEquals(moved.sceneEvents, repeated.sceneEvents)
    }

    @Test
    fun largeSoftStateCannotPushPendingFallbackOutOfRequestContext() {
        var context = ChatContextState(
            scene = ChatSceneState(sceneTime = "深夜", location = "很长很长的房间名"),
            continuity = ChatContinuityState(
                recentEvents = (1..5).map { "近期事件$it-" + "事".repeat(170) },
                decisions = (1..4).map { "决定$it-" + "定".repeat(170) },
                unfinished = (1..4).map { "待续$it-" + "续".repeat(170) },
            ),
            generation = 7L,
        )
        repeat(16) { index ->
            context = context.enqueuePending(
                ChatPendingTurn(
                    sequence = (100 + index).toLong(),
                    assistantMessageId = "a$index",
                    userMessage = "用户旧事实$index-" + "问".repeat(170),
                    assistantMessage = "角色旧事实$index-" + "答".repeat(220),
                    generation = 7L,
                ),
            )
        }

        val rendered = renderChatContextForModel(context)

        assertTrue(rendered.contains("#105 用户：用户旧事实5"))
        assertTrue(rendered.contains("地点=很长很长的房间名"))
        assertTrue(rendered.contains("事实优先级：当前用户输入 > 尚未归并原文"))
    }

    @Test
    fun plannerReceivesOnlyEventBackedHardSceneFields() {
        val state = ChatCharacterState()
        val context = ChatContextState(
            scene = ChatSceneState(
                sceneTime = "夜晚",
                location = "房间",
                participants = listOf("旧人物"),
                positions = listOf("靠着院墙"),
                activeActions = listOf("喝茶"),
                keyObjects = listOf("石桌"),
            ),
        )

        val planner = state.withContextForPlanner(context)

        assertEquals("夜晚", planner.scene.sceneTime)
        assertEquals("房间", planner.scene.location)
        assertTrue(planner.scene.participants.isEmpty())
        assertTrue(planner.scene.positions.isEmpty())
        assertTrue(planner.scene.activeActions.isEmpty())
        assertTrue(planner.scene.keyObjects.isEmpty())
    }

}
