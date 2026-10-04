package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalToolSchemaContinuityTest {
    private fun tool(name: String, description: String = name) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
        })
    }

    private fun names(tools: JsonArray): List<String> = tools.map {
        it.toString().substringAfter("\"name\":\"").substringBefore('"')
    }

    @Test
    fun additionsAppendWithoutReorderingExistingTools() {
        val previous = JsonArray(listOf(tool("a"), tool("b")))
        val current = JsonArray(listOf(tool("b"), tool("a"), tool("c")))

        val merged = appendOnlyToolSchemas(previous, current)

        assertEquals(listOf("a", "b", "c"), names(merged))
        assertEquals(previous[0], merged[0])
        assertEquals(previous[1], merged[1])
    }

    @Test
    fun deepSeekConsumesAppendOnlyCapabilityWhileUnknownRouteKeepsReplacement() {
        val previous = JsonArray(listOf(tool("a"), tool("b")))
        val reordered = JsonArray(listOf(tool("b"), tool("a"), tool("c")))

        val deepSeek = stableRunToolSchemas(previous, reordered, LocalHarnessState())
        val unknown = stableRunToolSchemas(
            previous,
            reordered,
            LocalHarnessState(model = "custom", baseUrl = "https://proxy.example/v1"),
        )

        assertEquals(listOf("a", "b", "c"), names(deepSeek))
        assertEquals(reordered, unknown)
    }

    @Test
    fun removalOrSchemaMutationBreaksAppendOnlySeriesImmediately() {
        val previous = JsonArray(listOf(tool("a"), tool("b")))
        val removed = JsonArray(listOf(tool("a")))
        val mutated = JsonArray(listOf(tool("a", "changed"), tool("b")))

        assertEquals(removed, appendOnlyToolSchemas(previous, removed))
        assertEquals(mutated, appendOnlyToolSchemas(previous, mutated))
    }
    @Test
    fun frozenRunSurfaceKeepsToolUpdateModeStableAcrossLaterStateChanges() {
        val surface = LocalModelProfile(
            id = "deepseek-run",
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com",
            provider = "DeepSeek",
        ).toRunModelSurface()
        val runTools = LocalRunToolSurface(surface)

        assertEquals(
            listOf("a", "b"),
            names(runTools.next(JsonArray(listOf(tool("a"), tool("b"))))),
        )
        assertEquals(
            listOf("a", "b", "c"),
            names(runTools.next(JsonArray(listOf(tool("b"), tool("a"), tool("c"))))),
        )
    }

}
