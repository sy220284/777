package com.labteto.dshmobile.local.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock

/** UI-independent event-backed transcript pagination limits remain available to any new pack. */
class LocalTranscriptHistoryBudgetTest {
    @Test fun sharedPagerPreservesRichToolMediaBlocksAndRemovesLiveDuplicates() = runBlocking {
        val live = LocalHarnessMessage("live-1", "user", "当前输入", createdAt = 4L)
        val image = LocalHarnessMessage("old-image", "assistant", "图片", createdAt = 3L,
            blocks = listOf(LocalMessageBlock.Image("images/photo.png", "image/png", "photo.png", 123L)))
        val tool = LocalHarnessMessage("old-tool", "tool", "完成", toolName = "read",
            createdAt = 2L, toolIsError = false, toolExecutionId = "exec-1")
        var requestedLimit = -1
        val pager = TranscriptHistoryPager<LocalHarnessMessage, Int>(
            readPage = { _, _, limit ->
                requestedLimit = limit
                listOf(image, tool, live) to null
            },
            liveMessages = { listOf(live) },
            currentSessionId = { "active" },
            messageId = LocalHarnessMessage::id,
            countDialogue = { rows -> rows.count { it.role == "user" || it.role == "assistant" } },
        )
        val page = requireNotNull(pager.bootstrap("active"))
        assertEquals(LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES, requestedLimit)
        assertEquals(listOf("old-image", "old-tool"), page.olderMessages.map { it.id })
        assertEquals("images/photo.png",
            (page.olderMessages.first().blocks.single() as LocalMessageBlock.Image).relativePath)
        assertEquals("exec-1", page.olderMessages.last().toolExecutionId)
        assertEquals(3, page.loadedRawMessages)
    }

    @Test fun sharedPagerRejectsPagesFromSessionChangedWhileReading() = runBlocking {
        var selected = "A"
        val pager = TranscriptHistoryPager<String, Int>(
            readPage = { _, _, _ ->
                selected = "B"
                listOf("stale") to null
            },
            liveMessages = { emptyList() },
            currentSessionId = { selected },
            messageId = { it },
            countDialogue = { it.size },
        )
        assertEquals(null, pager.bootstrap("A"))
    }

    @Test fun loadingIsBounded() {
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES in 1..200)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD in 1..20)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD in 1..4000)
        assertTrue(LOCAL_TRANSCRIPT_HISTORY_PAGE_MESSAGES * LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD <= LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD)
        assertEquals(12, LOCAL_TRANSCRIPT_INITIAL_VISIBLE_DIALOGUE_MESSAGES)
        assertEquals(24, LOCAL_TRANSCRIPT_HISTORY_VISIBLE_DIALOGUE_BATCH_MESSAGES)
    }
}
