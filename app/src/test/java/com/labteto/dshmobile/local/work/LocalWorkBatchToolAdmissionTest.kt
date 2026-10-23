package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkBatchToolAdmissionTest {
    private fun surface() = LocalModelToolStepSurface().apply {
        capture(JsonArray(listOf(functionToolSchema("read", "Read an existing workspace file"))))
    }

    private fun call(id: String, name: String) =
        LocalToolCall(id, name, JsonObject(emptyMap()), "{}")

    @Test fun hiddenInstalledToolCannotBypassModelSurfaceInBatch() = runBlocking {
        val surface = surface()
        var executed = 0
        val blocked = executeAdmittedWorkBatchCall(
            call("hidden", "process_exec"), { false },
            { name -> if (surface.allows(name)) null else surface.hiddenCallResult(name) },
        ) {
            executed++
            AgentToolResult("should not run")
        }
        assertTrue(blocked.isError)
        assertEquals("TOOL_NOT_EXPOSED", blocked.errorCode)
        assertEquals(0, executed)
    }

    @Test fun parallelMixedVisibleAndHiddenCallsExecuteOnlyExposedOnes() = runBlocking {
        val surface = surface()
        val results = listOf(call("visible", "read"), call("hidden", "process_exec"))
            .map { next ->
                async {
                    executeAdmittedWorkBatchCall(next, { false }, {
                        if (surface.allows(it)) null else surface.hiddenCallResult(it)
                    }) { AgentToolResult("actually executed " + next.name) }
                }
            }.awaitAll()
        assertFalse(results[0].isError)
        assertEquals("actually executed read", results[0].content)
        assertTrue(results[1].isError)
        assertEquals("TOOL_NOT_EXPOSED", results[1].errorCode)
    }

    @Test fun explicitlyRegisteredAliasUsesVisibleCanonicalSchema() = runBlocking {
        val surface = surface()
        val result = executeAdmittedWorkBatchCall(call("legacy", "read_file"), { false }, {
            if (surface.allows(it)) null else surface.hiddenCallResult(it)
        }) { AgentToolResult("executed through canonical read") }
        assertFalse(result.isError)
        assertEquals("executed through canonical read", result.content)
    }

    @Test fun supersededBatchCallDoesNotReachAdmissionOrExecution() = runBlocking {
        var checked = false
        var executed = false
        val result = executeAdmittedWorkBatchCall(call("stale", "read"), { true }, {
            checked = true
            null
        }) {
            executed = true
            AgentToolResult("unexpected")
        }
        assertTrue(result.isError)
        assertEquals("WORK_TOOL_SUPERSEDED", result.errorCode)
        assertFalse(checked)
        assertFalse(executed)
    }
}
