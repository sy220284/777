package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.scenes.ZxTimelineEntry
import com.labteto.dshmobile.uipack.zhaoxi.scenes.projectZxReplyTimeline
import com.labteto.dshmobile.uipack.zhaoxi.scenes.uncommittedReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiReplyTimelineTest {
    private fun msg(id: String, role: UiMessageRole, text: String = id) =
        UiMessageSnapshot(id, role, text, 0L)

    @Test fun liveReplyRetainsTheSameKeyAcrossStagesAndCommit() {
        val user = msg("user-1", UiMessageRole.USER)
        val empty = projectZxReplyTimeline(listOf(user), running = true)
            .last() as ZxTimelineEntry.Reply
        val stage = msg("step-1", UiMessageRole.ASSISTANT, "正在检查")
        val inProgress = projectZxReplyTimeline(listOf(user, stage), true)
            .last() as ZxTimelineEntry.Reply
        val final = msg("answer", UiMessageRole.ASSISTANT, "最终结果")
        val committed = projectZxReplyTimeline(listOf(user, stage, final), false)
            .last() as ZxTimelineEntry.Reply

        assertEquals(empty.key, inProgress.key)
        assertEquals(inProgress.key, committed.key)
        assertEquals(listOf("step-1", "answer"), committed.stages.map { it.id })
        assertTrue(committed.containsMessage("answer"))
    }

    @Test fun oldAndNewTurnsKeepDistinctReadingAnchors() {
        val list = projectZxReplyTimeline(listOf(
            msg("u1", UiMessageRole.USER),
            msg("a1", UiMessageRole.ASSISTANT),
            msg("u2", UiMessageRole.USER),
            msg("a2", UiMessageRole.ASSISTANT),
        ), running = false)
        val replies = list.filterIsInstance<ZxTimelineEntry.Reply>()
        assertEquals(2, replies.size)
        assertFalse(replies[0].key == replies[1].key)
        assertTrue(replies[0].containsMessage("a1"))
        assertTrue(replies[1].containsMessage("a2"))
    }

    @Test fun rawToolPayloadIsNotTurnedIntoProse() {
        val list = projectZxReplyTimeline(listOf(
            msg("user", UiMessageRole.USER),
            msg("tool-raw", UiMessageRole.TOOL, "dangerous raw arguments"),
            msg("result", UiMessageRole.ASSISTANT, "已核查实际回执"),
        ), running = false)
        assertFalse(list.any { it.containsMessage("tool-raw") })
        assertEquals("已核查实际回执",
            (list.last() as ZxTimelineEntry.Reply).stages.single().text)
    }
    @Test fun cumulativePreviewOnlyShowsUncommittedTail() {
        val stages = listOf(
            msg("step1", UiMessageRole.ASSISTANT, "已找到文件"),
            msg("step2", UiMessageRole.ASSISTANT, "已完成分析"),
        )
        val cumulative = listOf("已找到文件", "已完成分析", "进一步核对").joinToString("\n\n")
        assertEquals("进一步核对", uncommittedReplyPreview(cumulative, stages))
        assertEquals(null, uncommittedReplyPreview("已完成分析", stages))
        assertEquals(null, uncommittedReplyPreview("已完成分析的前缀".take(5), stages))
    }

    @Test fun freshDeltaIsPreservedAndLatestStagePrefixIsStripped() {
        val stages = listOf(msg("step", UiMessageRole.ASSISTANT, "已完成核查"))
        assertEquals("下一步", uncommittedReplyPreview("下一步", stages))
        assertEquals("；继续验证", uncommittedReplyPreview("已完成核查；继续验证", stages))
        assertEquals(null, uncommittedReplyPreview("  ", stages))
    }

}
