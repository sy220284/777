package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatSceneState
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
                ChatPendingTurn(sequence = 20L, assistantMessageId = "a20", generation = 3L),
                ChatPendingTurn(sequence = 22L, assistantMessageId = "a22", generation = 3L),
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
            complete = true,
        )

        assertEquals(listOf("大家在客厅聊天"), result.continuity.recentEvents)
        assertEquals(listOf("明天十点出门"), result.continuity.decisions)
        assertEquals(listOf("明早出门尚未发生"), result.continuity.unfinished)
        assertEquals(22L, result.processedThroughSequence)
        assertEquals(emptyList<ChatPendingTurn>(), result.pendingTurns)
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
