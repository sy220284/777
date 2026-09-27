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
        assertEquals(5, state.continuity.recentEvents.size)
        assertEquals(4, state.continuity.decisions.size)
        assertEquals(4, state.continuity.unfinished.size)
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
}
