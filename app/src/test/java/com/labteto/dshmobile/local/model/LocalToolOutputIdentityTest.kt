package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.*
import kotlinx.serialization.json.put
import org.junit.Test

class LocalToolOutputIdentityTest {
    @Test fun wrappedErrorAndStaleHistoryKeepTheImmutableOutputReference() {
        val projected = projectRecoverableToolResult("body".repeat(10_000), ToolResultRetention.DURABLE,
            LocalUsageMode.WORK, callId = "same", spill = { _, _ -> true })
        val wrapped = com.labteto.dshmobile.harness.agent.AgentToolResult(
            projected.text, isError = true).let { result ->
                kotlinx.serialization.json.buildJsonObject {
                    put("status", "error")
                    put("content", result.content)
                }.toString()
            }
        assertEquals(retainedToolOutputReference(projected.text), retainedToolOutputReference(wrapped))
    }

    @Test fun reusedProviderCallIdCannotReplaceTheOriginalOutputReference() {
        val saved = linkedMapOf<String, String>()
        fun project(value: String) = projectRecoverableToolResult(value, ToolResultRetention.DURABLE,
            LocalUsageMode.WORK, callId = "same", spill = { reference, body -> saved[reference] = body; true })
        val first = project("first original")
        val second = project("second original")
        val firstRef = retainedToolOutputReference(first.text)!!
        val secondRef = retainedToolOutputReference(second.text)!!
        assertNotEquals(firstRef, secondRef)
        assertEquals("first original", saved[firstRef])
        assertEquals("second original", saved[secondRef])
        assertEquals(firstRef, retainedToolOutputReference(project("first original").text))
    }
}
