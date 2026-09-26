package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptRuntimeIndexTest {
    @Test
    fun buildsStableFactsFromTranscript() {
        val index = buildLocalTranscriptRuntimeIndex(
            listOf(
                message("s1", "system", "内部", 1L),
                message("u1", "user", "第一行\n第二行", 2L),
                message("t1", "tool", "工具", 5L),
                message("a1", "assistant", "回答", 4L),
            ),
        )

        assertEquals("第一行", index.firstUserTitle)
        assertEquals(5L, index.latestCreatedAt)
        assertEquals(4L, index.latestDialogueCreatedAt)
        assertEquals("a1", index.latestDialogueMessageId)
        assertEquals("u1", index.latestUserMessageId)
        assertEquals("第一行\n第二行", index.latestUserContent)
        assertEquals("a1", index.latestMessageId)
        assertEquals("assistant", index.latestMessageRole)
        assertEquals(4L, index.totalMessageCount)
        assertTrue(index.hasDialogue)
        assertFalse(index.branchingEligible)
    }

    @Test
    fun appendKeepsFirstTitleAndAdvancesLatestDialogue() {
        val initial = buildLocalTranscriptRuntimeIndex(
            listOf(message("u1", "user", "原始标题", 10L)),
        )

        val updated = appendLocalTranscriptRuntimeIndex(
            initial,
            listOf(
                message("p1", "progress", "处理中", 11L),
                message("a1", "assistant", "完成", 12L),
                message("u2", "user", "新标题不应覆盖", 13L),
            ),
        )

        assertEquals("原始标题", updated.firstUserTitle)
        assertEquals(13L, updated.latestCreatedAt)
        assertEquals(13L, updated.latestDialogueCreatedAt)
        assertEquals("u2", updated.latestDialogueMessageId)
        assertEquals("u2", updated.latestUserMessageId)
        assertEquals("新标题不应覆盖", updated.latestUserContent)
        assertEquals(4L, updated.totalMessageCount)
        assertTrue(updated.hasDialogue)
        assertFalse(updated.branchingEligible)
    }

    @Test
    fun tracksBranchEligibilityAcrossIncrementalWindowBoundary() {
        val firstHalf = appendLocalTranscriptRuntimeIndex(
            LocalTranscriptRuntimeIndex(),
            listOf(
                message("u1", "user", "问题一", 1L),
                message("a1", "assistant", "回答一", 2L),
            ),
        )
        val complete = appendLocalTranscriptRuntimeIndex(
            firstHalf,
            listOf(
                message("u2", "user", "问题二", 3L),
                message("a2", "assistant", "回答二", 4L),
            ),
        )

        assertTrue(firstHalf.branchingEligible)
        assertTrue(complete.branchingEligible)
        assertEquals("assistant", complete.lastTurnDialogueRole)
    }

    @Test
    fun proactiveAssistantDoesNotChangeTurnAlternationAndCanExistBeforeUserTurn() {
        val normal = buildLocalTranscriptRuntimeIndex(
            listOf(
                message("u1", "user", "稍后找我", 1L),
                message("a1", "assistant", "好", 2L),
                message("a2", "assistant", "我来了", 3L, proactive = true),
            ),
        )
        val startsProactively = buildLocalTranscriptRuntimeIndex(
            listOf(message("a1", "assistant", "先来找你", 1L, proactive = true)),
        )

        assertTrue(normal.branchingEligible)
        assertEquals("assistant", normal.lastTurnDialogueRole)
        assertTrue(startsProactively.branchingEligible)
    }

    @Test
    fun latestUserRemainsParentWhenAssistantIsNewestDialogue() {
        val index = buildLocalTranscriptRuntimeIndex(
            listOf(
                message("u1", "user", "问题", 1L),
                message("a1", "assistant", "旧回答", 2L),
            ),
        )

        assertEquals("a1", index.latestDialogueMessageId)
        assertEquals("u1", index.latestUserMessageId)
        assertEquals("问题", index.latestUserContent)
        assertEquals("a1", index.latestMessageId)
        assertEquals("assistant", index.latestMessageRole)
    }

    @Test
    fun attachmentContextMakesBranchingIneligible() {
        val index = buildLocalTranscriptRuntimeIndex(
            listOf(
                message(
                    "u1",
                    "user",
                    "请看\n本次附件已导入本机工作区：\n- 文件：a.txt",
                    1L,
                ),
                message("a1", "assistant", "看到了", 2L),
            ),
        )

        assertFalse(index.branchingEligible)
    }

    @Test
    fun emptyTranscriptHasNoDialogueFacts() {
        val index = buildLocalTranscriptRuntimeIndex(emptyList())

        assertEquals(null, index.firstUserTitle)
        assertEquals(0L, index.latestCreatedAt)
        assertEquals(0L, index.latestDialogueCreatedAt)
        assertEquals(null, index.latestDialogueMessageId)
        assertEquals(null, index.latestUserMessageId)
        assertEquals(null, index.latestUserContent)
        assertEquals(null, index.latestMessageId)
        assertEquals(null, index.latestMessageRole)
        assertEquals(0L, index.totalMessageCount)
        assertFalse(index.hasDialogue)
        assertTrue(index.branchingEligible)
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        proactive: Boolean = false,
    ) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        proactive = proactive,
    )
}
