package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentTreeProjectionTest {
    private fun checkpoint(seq: Long, run: String, parent: String? = null,
        status: String = "running", kind: String = "subagent"): LocalSessionEventLog.Event =
        LocalSessionEventLog.Event(
            sequence = seq, type = if (kind == "foreground") LOCAL_AGENT_RUN_CHECKPOINT_EVENT
                else LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT,
            createdAt = seq,
            data = buildJsonObject {
                put("run_id", run)
                put("run_kind", kind)
                put("status", status)
                parent?.let { put("parent_run_id", it) }
            },
        )

    @Test fun treeUsesLatestCommittedStatusNotOlderRunningCheckpoint() {
        val view = projectLocalAgentTree(listOf(
            checkpoint(1, "root", kind = "foreground"),
            checkpoint(2, "child", "root"),
            checkpoint(3, "child", "root", "completed"),
        ))
        assertFalse(view.partial)
        assertEquals(2, view.nodes.size)
        assertEquals("completed", view.nodes.single { it.runId == "child" }.status)
        assertTrue(view.render().contains("parent=root"))
    }

    @Test fun truncatedHistoryExposesMissingParentAndPartialFlag() {
        val view = projectLocalAgentTree(
            listOf(checkpoint(2, "child", "root")),
            historyIncomplete = true,
        )
        assertTrue(view.partial)
        assertTrue(view.render().contains("parent_outside_window"))
        assertTrue(view.render().contains("partial=true"))
    }

    @Test fun nodeLimitNeverClaimsFullHistory() {
        val view = projectLocalAgentTree((1L..5L).map { checkpoint(it, "run-$it") }, maxNodes = 2)
        assertEquals(2, view.nodes.size)
        assertTrue(view.partial)
        assertTrue(view.nodes.any { it.runId == "run-5" })
    }

    @Test fun corruptedOrCyclicParentLinksCannotHangRendering() {
        val view = projectLocalAgentTree(listOf(
            checkpoint(1, "a", "b"), checkpoint(2, "b", "a"),
        ))
        assertEquals(2, view.nodes.size)
        assertTrue(view.render().length < 2_000)
        assertTrue(runCatching { projectLocalAgentTree(emptyList(), maxNodes = 257) }.isFailure)
    }
}
