package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.memory.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMemoryToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun lineageToolRejectsGhostWritesAndRecallsOnlyContinuation() {
        val store = MemoryStore(temporary.root, Json)
        val manager = MemoryManager(store, MemoryPolicy(), MemoryConflictResolver())
        var state = LocalHarnessState(lineageId = "lineage")
        val tools = LocalMemoryTools(store, manager, { state }, { "session" })
        val input = buildJsonObject { put("scope", "lineage"); put("content", "apples decision") }
        tools.execute("memory_remember", input, true)
        assertTrue(store.listActive(setOf(MemoryScope.LINEAGE), null, "lineage").isEmpty())
        state = state.copy(conversationMode = LocalConversationMode.CONTINUATION)
        tools.execute("memory_remember", input, true)
        assertTrue(tools.execute("memory_search", buildJsonObject { put("query", "apples") }, false).contains("apples decision"))
        state = state.copy(conversationMode = LocalConversationMode.INDEPENDENT)
        assertFalse(tools.execute("memory_search", buildJsonObject { put("query", "apples") }, false).contains("apples decision"))
    }

    @Test fun listExposesStableIdAndUpdateForgetRespectVisibleScope() {
        val store = MemoryStore(temporary.root, Json)
        val manager = MemoryManager(store, MemoryPolicy(), MemoryConflictResolver())
        var state = LocalHarnessState(conversationMode = LocalConversationMode.PROJECT, projectId = "project")
        val tools = LocalMemoryTools(store, manager, { state }, { "session" })
        val remembered = tools.execute(
            "memory_remember",
            buildJsonObject {
                put("scope", "project")
                put("kind", "decision")
                put("content", "use apples")
            },
            true,
        )
        assertTrue(remembered.contains("use apples"))
        val record = store.listActive(setOf(MemoryScope.PROJECT), "project", null).single()
        assertTrue(tools.execute("memory_list", buildJsonObject { }, false).contains("id=${record.id}"))

        val updated = tools.execute(
            "memory_update",
            buildJsonObject {
                put("id", record.id.take(8))
                put("content", "use oranges")
                put("importance", 90)
            },
            true,
        )
        assertTrue(updated.contains("use oranges"))
        assertEquals("use oranges", store.listActive(setOf(MemoryScope.PROJECT), "project", null).single().content)

        state = state.copy(conversationMode = LocalConversationMode.INDEPENDENT, projectId = null)
        assertTrue(
            tools.execute("memory_forget", buildJsonObject { put("id", record.id) }, true)
                .contains("没有找到"),
        )
        state = state.copy(conversationMode = LocalConversationMode.PROJECT, projectId = "project")
        assertTrue(tools.execute("memory_forget", buildJsonObject { put("id", record.id) }, true).contains("已停用"))
        assertTrue(store.listActive(setOf(MemoryScope.PROJECT), "project", null).isEmpty())
    }
}
