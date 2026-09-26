package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentContinuationTest {
    @Test
    fun checkpointRoundTripsBoundedHistoryAndOmitsImagePayloads() {
        val hugeImage = "data:image/png;base64," + "A".repeat(80_000)
        val history = listOf(
            message("system", "子代理系统"),
            buildJsonObject {
                put("role", "user")
                put("content", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "请分析")
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", hugeImage)
                    })
                })
            },
            message("assistant", "结论：" + "x".repeat(2_000)),
        )

        val encoded = encodeSubagentContinuationCheckpoint(
            agentId = "job-a",
            model = "deepseek-flash",
            maxSteps = 32,
            allowMutation = false,
            virtualScreen = false,
            history = history,
            compactor = LocalHistoryCompactor(),
        )
        val decoded = decodeSubagentContinuationCheckpoint(encoded)
            ?: error("expected checkpoint")

        assertEquals("job-a", decoded.agentId)
        assertEquals("deepseek-flash", decoded.model)
        assertFalse(decoded.allowMutation)
        assertTrue(decoded.history.isNotEmpty())
        assertFalse(encoded.toString().contains(hugeImage))
        assertTrue(encoded.toString().contains("图片内容已从子智能体续接检查点省略"))
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
