package com.labteto.dshmobile.local.memory

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryManagementQueryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fact(i: Int, time: Long = i.toLong()) = MemoryRecord("id-%04d".format(i), MemoryScope.GLOBAL,
        MemoryKind.FACT, "fact-$i", createdAt = time, updatedAt = time)
    private val query = MemoryManagementQuery(setOf(MemoryScope.GLOBAL))

    @Test fun searchFindsOldestBeyond100AndStoreCanCorrectItAfterRestart() {
        val root = temp.newFolder()
        val records = (0..150).map(::fact)
        MemoryDocumentStore(root, Json).write(MemoryDocument(records = records))
        val store = MemoryStore(root, Json)
        assertEquals(151, store.managementPage(query).totalMatching)
        val old = store.managementPage(query.copy(text = "fact-0")).records.single()
        assertEquals("id-0000", old.id)
        val changed = store.update(old.id, content = "corrected oldest", pinned = false, expectedUpdatedAt = old.updatedAt)
        assertEquals("corrected oldest", MemoryStore(root, Json).managementPage(query.copy(text = "corrected oldest")).records.single().content)
        assertTrue(store.forget(changed.id, changed.updatedAt))
        assertEquals(0, MemoryStore(root, Json).managementPage(query.copy(text = "corrected oldest")).totalMatching)
    }

    @Test fun pagesUseStableTiebreakerWithoutDuplicatesAndSearchRunsBeforeCursor() {
        val records = (0..150).map { fact(it, 1L) }
        var cursor: MemoryManagementCursor? = null
        val ids = mutableListOf<String>()
        do {
            val page = queryManagedMemories(records, query.copy(cursor = cursor))
            assertEquals(151, page.totalMatching)
            assertTrue(page.records.size <= 20)
            ids += page.records.map { it.id }
            cursor = page.next
        } while (cursor != null)
        assertEquals(records.map { it.id }, ids)
        assertEquals(151, ids.toSet().size)
        assertEquals(listOf("id-0150"), queryManagedMemories(records, query.copy(text = "fact-150")).records.map { it.id })
    }

    @Test fun scopesKindsAndExactSourceRemainIsolatedAcrossPages() {
        val global = fact(0)
        val project = fact(1).copy(scope = MemoryScope.PROJECT, projectId = "private", kind = MemoryKind.PREFERENCE)
        val lineage = fact(2).copy(scope = MemoryScope.LINEAGE, lineageId = "other")
        val inactive = fact(3).copy(active = false)
        val all = listOf(global, project, lineage, inactive)
        assertEquals(listOf(global), queryManagedMemories(all, query).records)
        val p = queryManagedMemories(all, query.copy(scopes = MemoryScope.entries.toSet(), projectId = "private", kinds = setOf(MemoryKind.PREFERENCE)))
        assertEquals(listOf(project), p.records)
        assertEquals(mapOf(MemoryKind.FACT to 1, MemoryKind.PREFERENCE to 1), p.kindCounts)
        val sourced = project.copy(sourceMessages = listOf(MemorySourceRef("session", "message")))
        assertEquals(listOf(sourced), queryManagedMemories(listOf(sourced), query.copy(sourceSessionId = "session", sourceMessageId = "message")).records)
        assertTrue(queryManagedMemories(listOf(sourced), query.copy(sourceSessionId = "session", sourceMessageId = "another")).records.isEmpty())
    }
}
