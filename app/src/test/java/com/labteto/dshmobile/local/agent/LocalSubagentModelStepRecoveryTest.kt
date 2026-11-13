package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentModelStepRecoveryTest {
    @Test
    fun minimalRecoveryKeepsInstructionsAndLatestUserOnly() {
        val history = listOf(
            message("system", "base"),
            message("developer", "policy"),
            message("user", "old"),
            message("assistant", "old answer"),
            message("user", "latest"),
        )

        assertEquals(
            listOf("base", "policy", "latest"),
            minimalSubagentRecoveryHistory(history)!!.map { it["content"].toString().trim('"') },
        )
    }

    @Test
    fun initialStructure400CanRetryOnceWithoutToolSideEffects() {
        val history = listOf(message("system", "base"), message("user", "task"))
        assertTrue(
            canRetrySubagentStructureFailure(
                LocalModelException("MODEL_HTTP_400", "bad request", false, status = 400),
                history,
            ),
        )
        assertTrue(
            canRetrySubagentStructureFailure(
                LocalModelException("MODEL_HISTORY_INVALID", "bad history", false),
                history,
            ),
        )
    }

    @Test
    fun toolHistoryBlocksMinimalReplayBecauseSideEffectsMayAlreadyExist() {
        val assistantWithCall = buildJsonObject {
            put("role", "assistant")
            put("tool_calls", JsonArray(listOf(buildJsonObject {
                put("id", "call-1")
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "write")
                    put("arguments", "{}")
                })
            })))
        }
        val history = listOf(
            message("system", "base"),
            message("user", "task"),
            assistantWithCall,
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "call-1")
                put("content", "done")
            },
        )

        assertFalse(
            canRetrySubagentStructureFailure(
                LocalModelException("MODEL_HISTORY_INVALID", "bad history", false),
                history,
            ),
        )
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
