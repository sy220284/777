package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.SessionProjectionsBlock
import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIndexStateTest {
    @Test
    fun replacementPreservesLiveRunningAndCachedTitle() {
        val state = SessionIndexState()
        state.replaceSessions(
            listOf(
                session(
                    id = "s1",
                    running = false,
                    title = "First title",
                ),
            ),
        )
        state.setRunning("s1", true)

        state.replaceSessions(
            listOf(
                session(
                    id = "s1",
                    running = false,
                    title = "New wire title",
                ),
            ),
        )

        val row = state.session("s1")!!
        assertTrue(row.running)
        assertEquals("First title", row.title)
    }

    @Test
    fun newSessionMovesToFrontAndPendingPriorityIsStable() {
        val state = SessionIndexState()
        state.replaceSessions(listOf(session("old"), session("older")))
        state.addSession(session("new"))

        state.addPending("old", "approval")
        state.addPending("old", "plan-review")
        state.addPending("old", "question")

        assertEquals(listOf("new", "old", "older"), state.renderSessions().map { it.sessionId })
        assertEquals("question", state.renderSessions().first { it.sessionId == "old" }.pendingInteraction)

        state.removePending("old", "question")
        assertEquals("plan-review", state.renderSessions().first { it.sessionId == "old" }.pendingInteraction)

        state.removePending("old", "plan-review")
        assertEquals("approval", state.renderSessions().first { it.sessionId == "old" }.pendingInteraction)

        state.removePending("old", "approval")
        assertNull(state.renderSessions().first { it.sessionId == "old" }.pendingInteraction)
    }

    @Test
    fun workspaceOrderAndReusableBlankSessionRespectArchiveAndOrigin() {
        val state = SessionIndexState()
        state.replaceSessions(
            listOf(
                session("blank", blank = true),
                session("archived", blank = true),
                session("subagent", blank = true, origin = "subagent"),
                session("used", blank = false),
            ),
        )
        state.replaceWorkspaceBaseline(
            workspaces = listOf(
                workspace("w1", listOf("archived", "subagent", "used", "blank")),
                workspace("w2", emptyList()),
            ),
            workspaceIds = listOf("w2", "w1"),
            archivedSessionIds = listOf("archived"),
        )

        assertEquals(listOf("w2", "w1"), state.orderedWorkspaces().map { it.workspaceId })
        assertEquals("blank", state.reusableBlankSession("w1"))
        assertEquals(setOf("archived"), state.archivedIds())

        state.setArchived(listOf("archived", "blank"))
        assertNull(state.reusableBlankSession("w1"))
    }

    @Test
    fun workspaceUpsertKeepsManualOrderAndAppendsUnknownWorkspace() {
        val state = SessionIndexState()
        state.replaceWorkspaceBaseline(
            workspaces = listOf(workspace("a"), workspace("b")),
            workspaceIds = listOf("b", "a"),
            archivedSessionIds = emptyList(),
        )

        state.upsertWorkspace(workspace("a", title = "renamed"))
        state.upsertWorkspace(workspace("c"))

        val rows = state.orderedWorkspaces()
        assertEquals(listOf("b", "a", "c"), rows.map { it.workspaceId })
        assertEquals("renamed", rows.first { it.workspaceId == "a" }.title)
        assertFalse(rows.first { it.workspaceId == "c" }.sessionIds.isNotEmpty())
    }

    private fun session(
        id: String,
        running: Boolean = false,
        blank: Boolean = false,
        title: String? = null,
        origin: String? = null,
    ) = SessionSummary(
        sessionId = id,
        updatedAt = 100L,
        running = running,
        blank = blank,
        origin = origin,
        projections = title?.let {
            SessionProjectionsBlock(
                asOfSeq = 1,
                values = mapOf("title" to JsonPrimitive(it)),
            )
        },
    )

    private fun workspace(
        id: String,
        sessionIds: List<String> = emptyList(),
        title: String = id,
    ) = WorkspaceView(
        workspaceId = id,
        path = "/tmp/$id",
        title = title,
        sessionIds = sessionIds,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-02T00:00:00Z",
    )
}
