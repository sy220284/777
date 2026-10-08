package com.labteto.dshmobile.local.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVaultProjectionTest {
    private fun record(
        id: String,
        scope: MemoryScope,
        project: String? = null,
        active: Boolean = true,
        pinned: Boolean = false,
    ) = MemoryRecord(
        id = id, scope = scope, kind = MemoryKind.FACT, content = id,
        projectId = project, createdAt = 1L, updatedAt = id.length.toLong(),
        active = active, pinned = pinned,
    )

    @Test fun projectScopesAndArchivedRecordsRemainIsolated() {
        val folders = memoryVaultFolders(listOf(
            record("global", MemoryScope.GLOBAL),
            record("one", MemoryScope.PROJECT, "p1"),
            record("two", MemoryScope.PROJECT, "p2"),
            record("old", MemoryScope.PROJECT, "p1", active = false),
        ))
        assertEquals(3, folders.size)
        assertEquals(setOf("one"), folders.first { it.ownerId == "p1" }.records.map { it.id }.toSet())
        assertTrue(folders.none { folder -> folder.records.any { !it.active } })
    }

    @Test fun pinnedMemoriesAppearFirstWithinOneFolder() {
        val folder = memoryVaultFolders(listOf(
            record("plain", MemoryScope.GLOBAL),
            record("pinned", MemoryScope.GLOBAL, pinned = true),
        )).single()
        assertEquals("pinned", folder.records.first().id)
    }
}
