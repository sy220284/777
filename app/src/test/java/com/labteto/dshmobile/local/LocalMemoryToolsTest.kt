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
}
