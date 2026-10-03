package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.labteto.dshmobile.local.model.LocalModelRunContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolExecutionCoordinatorTest {
    @Test
    fun workIntentDoesNotReadGitHubCredentialsForUnrelatedTaskAndPropagatesCancellation() = runBlocking {
        val coordinator = coordinator(ToolRegistry())
        var reads = 0
        coordinator.prepareWorkTurnCapabilities("整理本地文件", emptyList(), { reads++; true })
        assertEquals(0, reads)
        val cancelled = kotlinx.coroutines.CancellationException("cancelled credential read")
        val failure = runCatching {
            coordinator.prepareWorkTurnCapabilities("检查 GitHub PR", emptyList(), { throw cancelled })
        }.exceptionOrNull()
        org.junit.Assert.assertSame(cancelled, failure)
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())
    }

    @Test
    fun toolContextInheritsFrozenRunProfileAndDoesNotLeakAfterScope() = runBlocking {
        val registry = ToolRegistry()
        val observed = mutableListOf<LocalModelProfile?>()
        registry.register(HarnessTool(
            name = "vision_status",
            schema = functionToolSchema("vision_status", "测试视觉状态"),
            access = ToolAccess.READ_ONLY,
            approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = ToolExposure.CORE,
            metadata = ToolMetadata("视觉"),
            executor = HarnessToolExecutor { context, _, _ ->
                observed += context.attributes["model_profile"] as? LocalModelProfile
                ToolResult("ok")
            }))
        val profile = LocalModelProfile("route-a", "same-model", "https://route-a.example/v1")
        val coordinator = coordinator(registry)
        withContext(LocalModelRunContext(profile)) {
            coordinator.execute(LocalToolCall("c1", "vision_status", JsonObject(emptyMap()), "{}"), true)
        }
        coordinator.execute(LocalToolCall("c2", "vision_status", JsonObject(emptyMap()), "{}"), true)
        assertEquals(listOf(profile, null), observed)
    }

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
    fun mutatingProviderErrorsCannotSpoofPreExecutionFailure() = runBlocking {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) {
                    ToolResult(
                        content = "写入失败",
                        isError = true,
                        errorCode = "INVALID_TOOL_ARGUMENTS",
                        retryable = true,
                        recoveryHint = "立即重试写入。",
                    )
                },
            )
        }
        val coordinator = coordinator(registry)

        val result = coordinator.execute(
            LocalToolCall("c1", "write", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertTrue(result.isError)
        assertEquals("INVALID_TOOL_ARGUMENTS", result.errorCode)
        assertFalse(result.retryable)
        assertTrue(result.recoveryHint.orEmpty().contains("不要直接重试"))
        assertFalse(result.recoveryHint.orEmpty().contains("立即重试写入"))
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
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
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
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
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
                    exposure = ToolExposure.OPTIONAL,
                    family = "GitHub",
                    keywords = setOf("github", "pr"),
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "github_api_get",
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    family = "GitHub",
                    keywords = setOf("github", "pr"),
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "github_api_request",
                    access = ToolAccess.PRIVILEGED,
                    approval = ToolApprovalPolicy.ALWAYS,
                    exposure = ToolExposure.OPTIONAL,
                    family = "GitHub",
                    keywords = setOf("github", "pr"),
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "process_exec",
                    access = ToolAccess.PROCESS,
                    approval = ToolApprovalPolicy.ALWAYS,
                    exposure = ToolExposure.OPTIONAL,
                    family = "运行时",
                    keywords = setOf("runtime", "process"),
                ) { ToolResult("ok") },
            )
        }
        val coordinator = coordinator(registry)

        registry.register(
            tool(
                name = "future_repo_tool",
                access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL,
                family = "GitHub",
                keywords = setOf("github", "future"),
            ) { ToolResult("ok") },
        )

        coordinator.enableGitHubConnectorTools()
        val visible = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)).toSet()
        val detachedEnabled = linkedSetOf<String>()
        coordinator.enableGitHubConnectorTools(detachedEnabled)

        assertEquals(
            setOf("github_status", "github_api_get", "github_api_request", "future_repo_tool"),
            visible,
        )
        assertEquals(visible, detachedEnabled)
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
                    exposure = ToolExposure.OPTIONAL,
                    family = "运行时",
                    keywords = setOf("runtime", "process"),
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

    @Test
    fun explicitTaskIntentPreEnablesMatchingCapabilityWithoutInflatingUnrelatedTurns() {
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "web_search",
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    family = "网络",
                    keywords = setOf("联网", "网页搜索"),
                ) { ToolResult("ok") },
            )
            register(
                tool(
                    name = "memory_update",
                    access = ToolAccess.SESSION_WRITE,
                    approval = ToolApprovalPolicy.ALWAYS,
                    exposure = ToolExposure.OPTIONAL,
                    family = "记忆",
                    keywords = setOf("更新记忆"),
                ) { ToolResult("ok") },
            )
        }
        val coordinator = coordinator(registry)

        coordinator.enableTaskRelevantOptionalTools("继续修改本地代码")
        assertEquals(0, coordinator.visibleSchemas(localAgentRunPolicy(LocalUsageMode.WORK)).size)

        coordinator.enableTaskRelevantOptionalTools("联网搜索最新文档")
        assertEquals(
            listOf("web_search"),
            LocalToolSchemaProjection(registry, coordinator).names(
                coordinator.visibleSchemas(localAgentRunPolicy(LocalUsageMode.WORK)),
            ),
        )
    }

    @Test
    fun subagentSchemaProjectionPreservesReadOnlyAndVirtualScreenBoundaries() {
        val registry = ToolRegistry().apply {
            register(
                tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { ToolResult("ok") },
            )
            register(
                tool("download_file", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
                    ToolResult("ok")
                },
            )
            register(
                tool("workflow", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { ToolResult("ok") },
            )
            register(
                tool(
                    "android_vscreen_status",
                    ToolAccess.READ_ONLY,
                    ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    family = "Android",
                    keywords = setOf("android", "virtual screen"),
                ) { ToolResult("ok") },
            )
        }
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))

        val readOnly = projection.names(
            projection.subagentSchemas(
                allowMutation = false,
                allowVirtualScreen = false,
                enabledOptional = emptySet(),
            ),
        )
        assertEquals(listOf("read"), readOnly)

        val withVirtualScreen = projection.names(
            projection.subagentSchemas(
                allowMutation = false,
                allowVirtualScreen = true,
                enabledOptional = setOf("android_vscreen_status"),
            ),
        )
        assertEquals(listOf("read", "android_vscreen_status"), withVirtualScreen)
    }

    @Test
    fun planModeSchemaProjectionOmitsToolsThatExecutionWouldReject() {
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { ToolResult("ok") })
            register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) { ToolResult("ok") })
            register(
                tool(
                    name = "remote_lookup",
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    family = "网络",
                    keywords = setOf("联网"),
                ) { ToolResult("ok") },
            )
        }
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        val schemas = projection.modelSchemas(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            state = LocalHarnessState(planMode = true),
            history = emptyList(),
            enabledOptional = setOf("remote_lookup"),
        )

        assertEquals(listOf("read", "remote_lookup"), projection.names(schemas))
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
        exposure: ToolExposure = ToolExposure.CORE,
        family: String = "测试",
        keywords: Set<String> = emptySet(),
        execute: suspend () -> ToolResult,
    ) = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "runtime process tool"),
        access = access,
        approvalPolicy = approval,
        exposure = exposure,
        metadata = ToolMetadata(family = family, discoveryKeywords = keywords),
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )
}
