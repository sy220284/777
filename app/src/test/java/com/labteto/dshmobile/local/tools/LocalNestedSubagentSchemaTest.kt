package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.filterLocalSubagentSchemas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNestedSubagentSchemaTest {
    @Test fun filteredToolAllowlistCannotGrowAcrossDepths() {
        val raw = JsonArray(listOf(
            JsonObject(mapOf("function" to JsonObject(mapOf("name" to JsonPrimitive("read"))))),
            JsonObject(mapOf("function" to JsonObject(mapOf("name" to JsonPrimitive("subagent"))))),
            JsonObject(mapOf("function" to JsonObject(mapOf("name" to JsonPrimitive("write"))))),
        ))
        val readonly = LocalSubagentCapabilities(
            allowMutation = false, depth = 2, maxDepth = 3,
            toolAllowlist = setOf("read", "subagent"),
        )
        val result = filterLocalSubagentSchemas(raw, readonly)
        assertEquals(2, result.size)
        assertTrue(result.none { it.toString().contains("write") })
    }
}
