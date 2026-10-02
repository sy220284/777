package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptPressureTest {
    @Test
    fun bucketsAreMutuallyExclusiveAndSumToEstimatedInput() {
        val messages = listOf(
            buildJsonObject { put("role", "system"); put("content", "固定规则") },
            buildJsonObject { put("role", "user"); put("content", "旧问题") },
            buildJsonObject { put("role", "assistant"); put("content", "旧回复") },
            buildJsonObject { put("role", "user"); put("content", "当前问题") },
        )
        val tools = JsonArray(emptyList())

        val pressure = LocalPromptPressureMeter.measure(messages, tools, 100_000)

        assertEquals(
            pressure.estimatedInputTokens,
            pressure.systemTokens + pressure.historyTokens +
                pressure.currentUserTokens + pressure.toolDefinitionTokens,
        )
        assertTrue(pressure.currentUserTokens > 0)
        assertTrue(pressure.historyTokens > 0)
    }

    @Test
    fun unknownModelOperationalLimitIsARealGuard() {
        val limit = operationalInputLimitTokens("unknown-model", "https://example.com/v1")
        assertTrue(limit in 1..300_000)
    }

    @Test
    fun pressureStoreBoundsSessionsAndResetsGenerationPrefillAfterCompaction() {
        val store = LocalRequestPressureStore(maxSessions = 2)
        val pressure = LocalPromptPressure(
            contextChars = 100,
            estimatedInputTokens = 50,
            systemTokens = 10,
            historyTokens = 20,
            currentUserTokens = 20,
            toolDefinitionTokens = 0,
            operationalLimitTokens = 1_000,
        )

        store.record("a", pressure)
        store.recordReportedUsage("a", 48)
        assertEquals("server", store.window("a")?.prefillSource)
        assertEquals(48L, store.window("a")?.prefillTokens)

        store.advanceGeneration("a", 24)
        assertEquals(2, store.window("a")?.generation)
        assertEquals("estimated", store.window("a")?.prefillSource)
        assertEquals(24L, store.window("a")?.prefillTokens)

        store.record("b", pressure)
        store.record("c", pressure)
        assertEquals(2, store.trackedSessionCount())
        assertEquals(null, store.window("a"))
    }

}
