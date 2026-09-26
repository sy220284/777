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
        assertEquals("a1", index.latestDialogueMessageId)
        assertTrue(index.hasDialogue)
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
        assertEquals("u2", updated.latestDialogueMessageId)
        assertTrue(updated.hasDialogue)
    }

    @Test
    fun emptyTranscriptHasNoDialogueFacts() {
        val index = buildLocalTranscriptRuntimeIndex(emptyList())

        assertEquals(null, index.firstUserTitle)
        assertEquals(0L, index.latestCreatedAt)
        assertEquals(null, index.latestDialogueMessageId)
        assertFalse(index.hasDialogue)
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
    ) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
    )
}
