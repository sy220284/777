package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.finalizeGroupContextAfterRefresh
import com.labteto.dshmobile.local.chat.mergeGroupContinuity
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalGroupChatContinuityTest {
    @Test
    fun incompleteRefreshKeepsPendingFactsForLaterRetry() {
        val context = ChatContextState(
            scene = ChatSceneState(location = "客厅"),
            continuity = ChatContinuityState(decisions = listOf("今晚留在家里")),
            processedThroughSequence = 10L,
            pendingTurns = listOf(
                ChatPendingTurn(
                    sequence = 20L,
                    assistantMessageId = "a20",
                    userMessage = "明天十点出门",
                    assistantMessage = "好，十点。",
                    generation = 3L,
                ),
            ),
            generation = 3L,
        )

        val result = finalizeGroupContextAfterRefresh(
            context = context,
            statesInReplyOrder = listOf(ChatCharacterState()),
            processedPending = context.pendingTurns,
            complete = false,
        )

        assertEquals(context, result)
        assertEquals(listOf(20L), result.pendingTurns.map { it.sequence })
        assertEquals(10L, result.processedThroughSequence)
    }

    @Test
    fun completeRefreshMergesIndependentFieldsInReplyOrderAndCommitsPending() {
        val base = ChatContinuityState(
            recentEvents = listOf("大家在客厅聊天"),
            decisions = listOf("今晚留在家里"),
            unfinished = listOf("明日安排待定"),
        )
        val context = ChatContextState(
            scene = ChatSceneState(location = "客厅"),
            continuity = base,
            processedThroughSequence = 10L,
            pendingTurns = listOf(
                ChatPendingTurn(
                    sequence = 20L,
                    userMessageId = "u20",
                    assistantMessageId = "a20",
                    userMessage = "那就定了，明天十点出门。",
                    assistantMessage = "好，明天十点出门。",
                    generation = 3L,
                ),
                ChatPendingTurn(
                    sequence = 22L,
                    userMessageId = "u22",
                    assistantMessageId = "a22",
                    userMessage = "明早出门还没发生，等明天。",
                    assistantMessage = "嗯，等明早再出门。",
                    generation = 3L,
                ),
            ),
            generation = 3L,
        )
        val first = ChatCharacterState(
            continuity = base.copy(
                decisions = listOf("明天十点出门"),
            ),
        )
        val second = ChatCharacterState(
            continuity = base.copy(
                unfinished = listOf("明早出门尚未发生"),
            ),
        )

        val result = finalizeGroupContextAfterRefresh(
            context = context,
            statesInReplyOrder = listOf(first, second),
            processedPending = context.pendingTurns,
            complete = true,
        )

        assertEquals(listOf("大家在客厅聊天"), result.continuity.recentEvents)
        assertEquals(listOf("今晚留在家里", "明天十点出门"), result.continuity.decisions)
        assertEquals(
            listOf("明日安排待定", "明早出门尚未发生"),
            result.continuity.unfinished,
        )
        assertEquals(
            "u20",
            result.continuity.evidence.single { it.kind.name == "DECISION" }.sourceUserMessageId,
        )
        assertEquals(
            "u22",
            result.continuity.evidence.single { it.kind.name == "OPEN_THREAD" }.sourceUserMessageId,
        )
        assertEquals(22L, result.processedThroughSequence)
        assertEquals(emptyList<ChatPendingTurn>(), result.pendingTurns)
    }

    @Test
    fun successfulGroupRefreshCommitsOnlyPendingActuallyShownToPlanner() {
        val context = ChatContextState(
            continuity = ChatContinuityState(recentEvents = listOf("旧事件")),
            processedThroughSequence = 10L,
            pendingTurns = listOf(
                ChatPendingTurn(
                    sequence = 20L,
                    assistantMessageId = "a20",
                    userMessage = "已归并前两条",
                    assistantMessage = "记录到了。",
                    generation = 4L,
                ),
                ChatPendingTurn(
                    sequence = 22L,
                    assistantMessageId = "a22",
                    userMessage = "继续",
                    assistantMessage = "好。",
                    generation = 4L,
                ),
                ChatPendingTurn(
                    sequence = 24L,
                    assistantMessageId = "a24",
                    userMessage = "第三条还没归并",
                    assistantMessage = "收到。",
                    generation = 4L,
                ),
            ),
            generation = 4L,
        )
        val processed = context.pendingTurns.take(2)
        val state = ChatCharacterState(
            continuity = ChatContinuityState(recentEvents = listOf("已归并前两条")),
        )

        val result = finalizeGroupContextAfterRefresh(
            context = context,
            statesInReplyOrder = listOf(state),
            processedPending = processed,
            complete = true,
        )

        assertEquals(22L, result.processedThroughSequence)
        assertEquals(listOf(24L), result.pendingTurns.map { it.sequence })
        assertEquals(listOf("已归并前两条"), result.continuity.recentEvents)
    }

    @Test
    fun laterResponderWinsOnlyTheFieldItAlsoChanges() {
        val base = ChatContinuityState(
            recentEvents = listOf("旧事件"),
            decisions = listOf("旧决定"),
            unfinished = listOf("旧待续"),
        )
        val first = ChatCharacterState(
            continuity = base.copy(
                recentEvents = listOf("第一位角色补充的事件"),
                decisions = listOf("第一位角色确认的决定"),
            ),
        )
        val second = ChatCharacterState(
            continuity = base.copy(
                recentEvents = listOf("第二位角色更新的事件"),
            ),
        )

        val merged = mergeGroupContinuity(base, listOf(first, second))

        assertEquals(listOf("第二位角色更新的事件"), merged.recentEvents)
        assertEquals(listOf("第一位角色确认的决定"), merged.decisions)
        assertEquals(listOf("旧待续"), merged.unfinished)
    }
}
