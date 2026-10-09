package com.labteto.dshmobile.local.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    @Test fun validCatalogRetainsProjectAssociationAfterSerialization() {
        val json = Json
        val state = LocalProjectCatalogState(
            projects = listOf(LocalProject("a", "甲", "指令A"), LocalProject("b", "乙", "指令B")),
            activeId = "b",
        )
        val restored = decodeLocalProjectCatalog(json.encodeToString(state), json)
        assertEquals("b", restored.activeId)
        assertEquals("指令B", restored.projects.single { it.id == "b" }.instructions)
    }

    @Test fun invalidCatalogsFailValidationBeforePublication() {
        val json = Json
        val broken = listOf(
            "{broken",
            json.encodeToString(LocalProjectCatalogState(projects = emptyList())),
            json.encodeToString(LocalProjectCatalogState(listOf(LocalProject("x", "A"), LocalProject("x", "B")), "x")),
            json.encodeToString(LocalProjectCatalogState(listOf(LocalProject("x", "A")), "missing")),
            json.encodeToString(LocalProjectCatalogState(listOf(LocalProject("x", "A", "x".repeat(8_001))), "x")),
        )
        broken.forEach { raw -> assertTrue(runCatching { decodeLocalProjectCatalog(raw, json) }.isFailure) }
    }

    @Test fun unavailableProjectCannotBeConsumedAsEmptyRules() {
        val catalog = LocalProjectCatalogState(listOf(LocalProject("bound", "项目", "保留原文件")), "bound")
        assertEquals("保留原文件", resolveLocalProjectInstructions(catalog, null, "bound"))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            resolveLocalProjectInstructions(LocalProjectCatalogState(), "目录损坏", "bound")
        }
        assertEquals("", resolveLocalProjectInstructions(catalog, "目录损坏", null))
        assertEquals("", resolveLocalProjectInstructions(LocalProjectCatalogState(), null, DEFAULT_PROJECT_ID))
    }

}
