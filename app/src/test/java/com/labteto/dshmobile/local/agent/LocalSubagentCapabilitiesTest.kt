package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubagentCapabilitiesTest {
    @Test
    fun acceptsReadonlyContinuableIsolatedLaunch() {
        val spec = LocalSubagentLaunchSpec(
            task = "继续审计",
            modelOverride = null,
            maxSteps = 20,
            backgroundJobId = "job-1",
            capabilities = LocalSubagentCapabilities(
                allowMutation = false,
                continuable = true,
                virtualScreen = true,
                historyMode = LocalSubagentHistoryMode.ISOLATED,
                maxDepth = 1,
            ),
        )

        assertEquals(spec, validateLocalSubagentLaunchSpec(spec))
    }

    @Test
    fun rejectsOversizedStableInstructionsBeforeExecution() {
        val spec = LocalSubagentLaunchSpec(
            task = "任务",
            instructions = "x".repeat(16_385),
            modelOverride = null,
            maxSteps = 20,
        )

        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(spec) }
                .contains("SUBAGENT_INSTRUCTIONS_TOO_LARGE"),
        )
    }

    @Test
    fun rejectsContinuableMutationAndInheritedHistory() {
        val mutable = launch(
            LocalSubagentCapabilities(
                allowMutation = true,
                continuable = true,
            ),
        )
        val inherited = launch(
            LocalSubagentCapabilities(
                continuable = true,
                historyMode = LocalSubagentHistoryMode.INHERIT_PARENT,
            ),
        )

        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(mutable) }
                .contains("SUBAGENT_CONTINUATION_MUTATION_BLOCKED"),
        )
        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(inherited) }
                .contains("SUBAGENT_CONTINUATION_HISTORY_BLOCKED"),
        )
    }

    @Test
    fun rejectsUnsupportedDepthAndStructuredOutputBeforeExecution() {
        val deep = LocalSubagentLaunchSpec(
            task = "深层代理",
            modelOverride = null,
            maxSteps = 20,
            capabilities = LocalSubagentCapabilities(maxDepth = 2),
        )
        val structured = LocalSubagentLaunchSpec(
            task = "结构化结果",
            modelOverride = null,
            maxSteps = 20,
            capabilities = LocalSubagentCapabilities(
                outputSchema = buildJsonObject {
                    put("type", "object")
                },
            ),
        )

        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(deep) }
                .contains("SUBAGENT_DEPTH_NOT_SUPPORTED"),
        )
        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(structured) }
                .contains("SUBAGENT_OUTPUT_SCHEMA_NOT_SUPPORTED"),
        )
    }

    @Test
    fun toolAllowlistDirectlyFiltersModelVisibleSchemas() {
        val schemas = JsonArray(
            listOf(
                functionSchema("read"),
                functionSchema("grep"),
                functionSchema("web_search"),
            ),
        )
        val capabilities = LocalSubagentCapabilities(
            toolAllowlist = setOf("read", "web_search"),
        )

        val filtered = filterLocalSubagentSchemas(schemas, capabilities)

        assertEquals(
            listOf("read", "web_search"),
            filtered.map {
                it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            },
        )
    }

    @Test
    fun unavailableToolAllowlistFailsClosedBeforeExecution() {
        val schemas = JsonArray(
            listOf(
                functionSchema("read"),
                functionSchema("grep"),
            ),
        )
        val capabilities = LocalSubagentCapabilities(
            toolAllowlist = setOf("read", "missing_tool"),
        )

        val message = requireFailure {
            validateLocalSubagentToolAllowlist(schemas, capabilities)
        }

        assertTrue(message.contains("SUBAGENT_TOOL_FILTER_UNAVAILABLE"))
        assertTrue(message.contains("missing_tool"))
    }

    @Test
    fun versionTwoCapabilitiesRoundTripIncludingFutureOutputSchema() {
        val capabilities = LocalSubagentCapabilities(
            allowMutation = false,
            continuable = true,
            virtualScreen = true,
            historyMode = LocalSubagentHistoryMode.ISOLATED,
            maxDepth = 1,
            toolAllowlist = setOf("read", "grep"),
            outputSchema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
            },
        )
        val payload = buildJsonObject {
            put("capabilities", encodeLocalSubagentCapabilities(capabilities))
        }

        assertEquals(
            capabilities,
            decodeLocalSubagentCapabilities(payload, version = 2),
        )
    }

    @Test
    fun versionOnePersistentPayloadMapsToSafeReadonlyCapabilities() {
        val payload = buildJsonObject {
            put("virtual_screen", true)
        }

        val decoded = decodeLocalSubagentCapabilities(payload, version = 1)

        assertEquals(false, decoded.allowMutation)
        assertEquals(true, decoded.continuable)
        assertEquals(true, decoded.virtualScreen)
        assertEquals(LocalSubagentHistoryMode.ISOLATED, decoded.historyMode)
        assertEquals(1, decoded.maxDepth)
        assertEquals(null, decoded.toolAllowlist)
        assertEquals(null, decoded.outputSchema)
    }

    @Test
    fun backgroundLaunchRequiresExplicitContinuation() {
        val spec = LocalSubagentLaunchSpec(
            task = "后台任务",
            modelOverride = null,
            maxSteps = 20,
            backgroundJobId = "job-1",
            capabilities = LocalSubagentCapabilities(continuable = false),
        )

        assertTrue(
            requireFailure { validateLocalSubagentLaunchSpec(spec) }
                .contains("SUBAGENT_BACKGROUND_REQUIRES_CONTINUATION"),
        )
    }

    private fun launch(capabilities: LocalSubagentCapabilities) =
        LocalSubagentLaunchSpec(
            task = "任务",
            modelOverride = null,
            maxSteps = 20,
            backgroundJobId = "job-1",
            capabilities = capabilities,
        )

    private fun functionSchema(name: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
        })
    }

    private fun requireFailure(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        return error?.message.orEmpty()
    }
}
