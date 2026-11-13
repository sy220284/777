package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalSessionSection
import com.labteto.dshmobile.local.session.LocalSessionSectionSnapshot
import com.labteto.dshmobile.local.session.LocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDrawerSessionGroupingTest {
    private val sectionA = LocalSessionSection("a", "第一组")
    private val sectionB = LocalSessionSection("b", "第二组")
    private val a = LocalSessionSummary("session-a", "A", 3)
    private val b = LocalSessionSummary("session-b", "B", 2)
    private val c = LocalSessionSummary("session-c", "C", 1)

    @Test fun assignedSessionsRenderOnceInCatalogOrderAndUnassignedRemainRecent() {
        val result = localDrawerGroupSessions(
            listOf(a, b, c),
            LocalSessionSectionSnapshot(listOf(sectionB, sectionA), mapOf(a.id to "a", b.id to "b")),
            includeEmptyGroups = true,
        )
        assertEquals(listOf(sectionB, sectionA), result.groups.map { it.first })
        assertEquals(listOf(b.id, a.id), result.groups.flatMap { it.second }.map { it.id })
        assertEquals(listOf(c.id), result.ungrouped.map { it.id })
        assertEquals(3, result.groups.sumOf { it.second.size } + result.ungrouped.size)
    }

    @Test fun searchHidesEmptyGroupsAndPreservesUnknownSectionMembershipAsRecent() {
        val result = localDrawerGroupSessions(
            listOf(a, b),
            LocalSessionSectionSnapshot(listOf(sectionA, sectionB), mapOf(a.id to "removed")),
            includeEmptyGroups = false,
        )
        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(a, b), result.ungrouped)
    }

    @Test fun pinnedConversationsAreNeverDuplicatedInTheGroupedRecentProjection() {
        val result = localDrawerGroupSessions(
            unpinnedRecent = listOf(b, c),
            catalog = LocalSessionSectionSnapshot(
                listOf(sectionA), mapOf(a.id to sectionA.id, b.id to sectionA.id),
            ),
            includeEmptyGroups = true,
        )
        assertEquals(listOf(b), result.groups.single().second)
        assertEquals(listOf(c), result.ungrouped)
    }
}
