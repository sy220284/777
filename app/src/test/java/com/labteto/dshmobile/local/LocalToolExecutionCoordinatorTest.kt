package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolExecutionCoordinatorTest {
    @Test
    fun unknownToolReturnsStructuredFailure() = runBlocking {
        val coordinator = coordinator(ToolRegistry())

        val result = coordinator.execute(
            LocalToolCall("c1", "missing", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertTrue(result.isError)
        assertEquals("UNKNOWN_TOOL", result.errorCode)
        assertTrue(result.recoveryHint.orEmpty().contains("capability_search"))
    }

    @Test
    fun planModeBlocksMutationBeforeExecutorRuns() = runBlocking {
        var executed = false
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) {
                    executed = true
                    ToolResult("ok")
                },
            )
        }
        val coordinator = coordinator(registry, planMode = true)

        val result = coordinator.execute(
            LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertTrue(result.isError)
        assertEquals("PLAN_MODE_BLOCKED", result.errorCode)
        assertFalse(executed)
    }

    @Test
    fun successfulMutationPreservesPossibleSideEffectForRecovery() = runBlocking {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) { ToolResult("ok") },
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.execute(
            LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertFalse(result.isError)
        assertEquals(AgentToolSideEffect.POSSIBLE, result.sideEffect)
    }

    @Test
    fun registryErrorsPreservePossibleSideEffect() = runBlocking {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) { ToolResult("写入失败", isError = true) },
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.execute(
            LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertTrue(result.isError)
        assertEquals("TOOL_REPORTED_ERROR", result.errorCode)
        assertEquals(AgentToolSideEffect.POSSIBLE, result.sideEffect)
    }


    @Test
    fun readOnlyScopeBlocksMutationBeforeApprovalOrExecution() = runBlocking {
        var executed = false
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) {
                    executed = true
                    ToolResult("ok")
                },
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.executeScoped(
            original = LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "readonly",
            allowMutation = false,
            planModeEnabled = false,
            approval = { _, _, _ -> true },
        )

        assertTrue(result.isError)
        assertEquals("MUTATION_SCOPE_BLOCKED", result.errorCode)
        assertFalse(executed)
    }

    @Test
    fun deniedApprovalIsReportedDistinctly() = runBlocking {
        var executed = false
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.ALWAYS,
                ) {
                    executed = true
                    ToolResult("ok")
                },
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.executeScoped(
            original = LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "s1",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ -> false },
        )

        assertTrue(result.isError)
        assertEquals("APPROVAL_DENIED", result.errorCode)
        assertFalse(executed)
        assertTrue(result.recoveryHint.orEmpty().contains("不要重复调用"))
    }

    @Test
    fun scopedExecutionUsesOwningSessionInsteadOfForegroundSession() = runBlocking {
        var observedSessionId = ""
        val registry = ToolRegistry().apply {
            register(
                HarnessTool(
                    name = "session_probe",
                    schema = buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "session_probe")
                            put("description", "probe")
                            put("parameters", buildJsonObject { put("type", "object") })
                        })
                    },
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    executor = HarnessToolExecutor { context, _, _ ->
                        observedSessionId = context.sessionId.orEmpty()
                        ToolResult("ok")
                    },
                ),
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.executeScoped(
            original = LocalToolCall("c1", "session_probe", JsonObject(emptyMap()), "{}"),
            sessionId = "detached-session",
            allowMutation = false,
            planModeEnabled = false,
            approval = { _, _, _ -> true },
        )

        assertFalse(result.isError)
        assertEquals("detached-session", observedSessionId)
    }

    @Test
    fun configuredConnectorCanPreEnableRegisteredOptionalToolsForFirstModelStep() {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "github_status",
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "github_api_get",
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "github_api_request",
                    access = ToolAccess.PRIVILEGED,
                    approval = ToolApprovalPolicy.ALWAYS,
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "process_exec",
                    access = ToolAccess.PROCESS,
                    approval = ToolApprovalPolicy.ALWAYS,
                ) { ToolResult("ok") },
            )
        }
        val coordinator = coordinator(registry)

        coordinator.enableOptionalTools(
            setOf("github_status", "github_api_get", "github_api_request", "missing_tool"),
        )
        val visible = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)).toSet()

        assertEquals(
            setOf("github_status", "github_api_get", "github_api_request"),
            visible,
        )
        assertFalse("process_exec" in visible)
    }

    @Test
    fun capabilitySearchEnablesOptionalToolForCurrentTurn() {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "process_exec",
                    access = ToolAccess.PROCESS,
                    approval = ToolApprovalPolicy.ALWAYS,
                ) { ToolResult("ok") },
            )
        }
        val coordinator = coordinator(registry)

        val before = coordinator.visibleSchemas(localAgentRunPolicy(LocalUsageMode.WORK))
        val result = coordinator.searchCapabilities("runtime process")
        val after = coordinator.visibleSchemas(localAgentRunPolicy(LocalUsageMode.WORK))

        assertEquals(0, before.size)
        assertTrue(result.contains("process_exec"))
        assertEquals(1, after.size)
        coordinator.clearTurnCapabilities()
        assertEquals(0, coordinator.visibleSchemas(localAgentRunPolicy(LocalUsageMode.WORK)).size)
    }

    private fun coordinator(
        registry: ToolRegistry,
        planMode: Boolean = false,
    ) = LocalToolExecutionCoordinator(
        registry = registry,
        currentSessionId = { "s1" },
        planMode = { planMode },
        enabledOptionalTools = linkedSetOf(),
        requestApproval = { _, _, _ -> true },
    )

    private fun tool(
        name: String,
        access: ToolAccess,
        approval: ToolApprovalPolicy,
        execute: suspend () -> ToolResult,
    ) = HarnessTool(
        name = name,
        schema = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", "runtime process tool")
                put("parameters", buildJsonObject { put("type", "object") })
            })
        },
        access = access,
        approvalPolicy = approval,
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )
}
