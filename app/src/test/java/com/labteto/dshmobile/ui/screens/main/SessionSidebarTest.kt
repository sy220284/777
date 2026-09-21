package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSidebarTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        blank: Boolean = false,
    ) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = blank,
        parentSessionId = null,
        origin = null,
        cwd = null,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    @Test
    fun `current comes from the existing session record list`() {
        val current = session("current", updatedAt = 20)
        val history = session("history", updatedAt = 10)

        val sections = drawerSessionSections(
            sessions = listOf(history, current),
            archivedIds = emptySet(),
            currentSessionId = "current",
            sortByRecency = false,
        )

        assertEquals(current, sections.current)
        assertEquals(listOf(history), sections.history)
    }

    @Test
    fun `history is the remaining existing records newest first when requested`() {
        val sections = drawerSessionSections(
            sessions = listOf(
                session("old", updatedAt = 10),
                session("current", updatedAt = 20),
                session("new", updatedAt = 30),
            ),
            archivedIds = emptySet(),
            currentSessionId = "current",
            sortByRecency = true,
        )

        assertEquals(listOf("new", "old"), sections.history.map { it.sessionId })
    }

    @Test
    fun `selected blank record is still shown but blank scratch history is hidden`() {
        val current = session("current", blank = true)

        val sections = drawerSessionSections(
            sessions = listOf(current, session("scratch", blank = true), session("saved")),
            archivedIds = emptySet(),
            currentSessionId = "current",
            sortByRecency = false,
        )

        assertEquals(current, sections.current)
        assertEquals(listOf("saved"), sections.history.map { it.sessionId })
    }

    @Test
    fun `archived records stay out of current and history sections`() {
        val sections = drawerSessionSections(
            sessions = listOf(session("archived"), session("saved")),
            archivedIds = setOf("archived"),
            currentSessionId = "archived",
            sortByRecency = false,
        )

        assertNull(sections.current)
        assertEquals(listOf("saved"), sections.history.map { it.sessionId })
    }
}
