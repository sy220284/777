package com.labteto.dshmobile.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkHandoffMaterialsTest {
    private fun row(id: String, role: String, text: String, proactive: Boolean = false, tool: String? = null) =
        LocalHarnessMessage(id = id, role = role, content = text, createdAt = 1L,
            proactive = proactive, toolName = tool)

    @Test
    fun candidatesExcludeScheduledToolRowsAndDeduplicateHistoryOverlap() {
        val old = listOf(row("a", "user", "最初要求"), row("b", "assistant", "建议"),
            row("p", "assistant", "定时问候", proactive = true), row("t", "tool", "工具结果", tool = "run"))
        val live = listOf(row("b", "assistant", "建议"), row("c", "user", "新要求"))
        assertEquals(listOf("a", "b", "c"), workHandoffDialogueCandidates(old, live).map { it.id })
    }

    @Test
    fun onlySelectedMessagesAreAppendedAndEditedSummaryIsPreserved() {
        val candidates = listOf(row("a", "user", "需要的材料"), row("b", "assistant", "不相关"),
            row("t", "tool", "秘密结果", tool = "run"))
        val result = workHandoffSummaryWithSelectedMessages(
            "自定义摘要", "chat-123", candidates, listOf("a", "a", "t", "nonexistent"),
        )
        assertTrue(result.startsWith("自定义摘要"))
        assertTrue(result.contains("来源会话：chat-123"))
        assertEquals(1, "消息ID: a".toRegex().findAll(result).count())
        assertFalse(result.contains("不相关"))
        assertFalse(result.contains("秘密结果"))
        assertEquals("不添加", workHandoffSummaryWithSelectedMessages("不添加", "chat-123", candidates, emptyList()))
    }

    @Test
    fun olderLoadedMessagesRemainSelectableAndSearchableAfterLongConversation() {
        val older = (1..80).map { index -> row("old-$index", "user", "历史事件-$index") }
        val live = (1..20).map { index -> row("live-$index", "assistant", "新讨论-$index") }
        val candidates = workHandoffDialogueCandidates(older, live)
        assertEquals(100, candidates.size)
        assertEquals(listOf("old-1"), visibleWorkHandoffMessages(candidates, "历史事件-1", emptyList())
            .filter { it.id == "old-1" }.map { it.id })
        assertTrue(visibleWorkHandoffMessages(candidates, "", listOf("old-1"))
            .any { it.id == "old-1" })
        val selected = workHandoffSummaryWithSelectedMessages("", "chat-older", candidates, listOf("old-1"))
        assertTrue(selected.contains("历史事件-1"))
        assertFalse(selected.contains("新讨论-20"))
    }

    @Test
    fun selectedMediaOnlyMessageKeepsVerifiedWorkspaceReferenceWithoutCopyingBytes() {
        val hash = "a".repeat(64)
        val media = LocalHarnessMessage(
            id = "media-1", role = "user", content = "", createdAt = 1L,
            blocks = listOf(LocalMessageBlock.Image(
                relativePath = ".dsh/attachments/$hash.jpg", mediaType = "image/jpeg",
                name = "草图.jpg", bytes = 123L, attachmentId = hash,
            )),
        )
        val selected = workHandoffDialogueCandidates(listOf(media), emptyList())
        assertEquals(listOf("media-1"), selected.map { it.id })
        assertTrue(visibleWorkHandoffMessages(selected, "草图", emptyList()).isNotEmpty())
        val summary = workHandoffSummaryWithSelectedMessages("", "chat-source", selected, listOf("media-1"))
        assertTrue(summary.contains("消息ID: media-1"))
        assertTrue(summary.contains(".dsh/attachments/$hash.jpg"))
        assertTrue(summary.contains("读取时重新校验"))
    }

    @Test
    fun untrustedAndToolMediaCannotEscapeThroughHandoff() {
        val hash = "b".repeat(64)
        val forged = LocalHarnessMessage(
            id = "untrusted", role = "user", content = "资料", createdAt = 2L,
            blocks = listOf(
                LocalMessageBlock.File(
                    relativePath = "../private.txt", mediaType = "text/plain",
                    name = "内部文件", bytes = 42L, attachmentId = hash,
                ),
                LocalMessageBlock.Image(
                    relativePath = ".dsh/attachments/$hash.png", mediaType = "image/png",
                    name = "模型生成.jpg", bytes = 42L, attachmentId = hash,
                    source = LocalMessageMediaSource.MODEL,
                ),
            ),
        )
        val output = workHandoffSummaryWithSelectedMessages("", "source",
            listOf(forged), listOf("untrusted"))
        assertFalse(output.contains("../private.txt"))
        assertFalse(output.contains(".dsh/attachments/$hash.png"))
        assertTrue(output.contains("缺少可验证的本地引用"))
        val tool = forged.copy(id = "tool", role = "tool", toolName = "read")
        assertTrue(workHandoffDialogueCandidates(listOf(tool), emptyList()).isEmpty())
    }

    @Test
    fun largeMessagesKeepSourceIdAndMarkExcerpt() {
        val result = workHandoffSummaryWithSelectedMessages(
            "", "chat-123", listOf(row("long", "user", "词".repeat(3000))), listOf("long"),
        )
        assertTrue(result.contains("消息ID: long"))
        assertTrue(result.contains("（节选）"))
        assertTrue(result.length < 1400)
    }
}
