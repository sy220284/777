package com.labteto.dshmobile.local.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProjectCatalogTest {
    @Test fun legacyProjectIdRemainsStable() {
        val state = LocalProjectCatalogState()
        assertEquals("local-workspace", state.activeId)
        assertTrue(state.projects.any { it.id == state.activeId })
    }

    @Test fun instructionsAreScopedToTheirOwner() {
        val first = LocalProject("first", "One", "Check references")
        val second = LocalProject("second", "Two")
        val state = LocalProjectCatalogState(listOf(first, second), "second")
        assertEquals("Check references", state.projects.first().instructions)
        assertEquals("", state.projects.last().instructions)
        assertFalse(state.activeId == first.id)
    }
}
