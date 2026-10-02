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
}
