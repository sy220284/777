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
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.work.LocalWorkState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolExecutionCoordinatorTest {
    @Test
    fun githubKeywordPreactivationCannotBypassNegationOrCredentialGate() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool(
                name = "github_status",
                access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL,
                family = "GitHub",
                keywords = setOf("github", "pr"),
            ) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        val history = listOf(buildJsonObject {
            put("role", "user")
            put("content", "检查 GitHub PR")
        })
        var credentialReads = 0
        coordinator.prepareWorkTurnCapabilities("不使用github，继续本地工作", history, { credentialReads++; true })
        assertEquals(0, credentialReads)
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())
        coordinator.prepareWorkTurnCapabilities("检查 GitHub PR", emptyList(), { false })
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())
        val detached = linkedSetOf<String>()
        coordinator.prepareWorkTurnCapabilities("检查 GitHub PR", emptyList(), { true }, detached)
        assertEquals(setOf("github_status"), detached)
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())
        // Explicit discovery remains available even without automatic pre-activation.
        assertTrue(coordinator.searchCapabilities("github").contains("github_status"))
    }

    @Test
    fun explicitlyDiscoveredToolTakesPrecedenceOverEarlierOptionalBudget() {
        val registry = ToolRegistry().apply {
            repeat(18) { index ->
                register(tool(name = "background_$index", access = ToolAccess.READ_ONLY,
                    approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                    family = "后台扩展", keywords = setOf("background")) { ToolResult("ok") })
            }
            register(tool(name = "critical_lookup", access = ToolAccess.READ_ONLY,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "精确发现", keywords = setOf("critical")) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        coordinator.enableOptionalTools((0 until 18).map { "background_$it" })
        assertFalse("critical_lookup" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))
        val response = coordinator.searchCapabilities("critical_lookup")
        assertTrue(response.contains("critical_lookup"))
        assertTrue(coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)).contains("critical_lookup"))
    }

    @Test
    fun disabledNetworkSearchBlocksDiscoverySchemasAndExecution() = runBlocking {
        var networkEnabled = false
        var executed = 0
        val registry = ToolRegistry().apply {
            for (name in listOf("web_search", "web_fetch", "http_request")) {
                register(tool(
                    name = name,
                    access = ToolAccess.NETWORK,
                    approval = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    family = "网络",
                    keywords = setOf("联网", "网页搜索"),
                ) {
                    executed++
                    ToolResult("ok")
                })
            }
        }
        val coordinator = coordinator(registry, networkSearchEnabled = { networkEnabled })
        val detached = linkedSetOf<String>()
        coordinator.prepareWorkTurnCapabilities("联网搜索", emptyList(), { false }, detached)
        assertFalse("web_search" in detached)
        assertFalse("web_fetch" in detached)
        coordinator.enableOptionalTools(listOf("web_search", "web_fetch"))
        coordinator.searchCapabilities("联网 网页搜索")
        assertFalse("web_search" in coordinator.enabledOptionalSnapshot())
        assertFalse("web_fetch" in coordinator.enabledOptionalSnapshot())
        val schemas = LocalToolSchemaProjection(registry, coordinator).modelSchemas(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = false,
            history = emptyList(),
            enabledOptional = setOf("web_search", "web_fetch", "http_request"),
        )
        assertEquals(listOf("http_request"), LocalToolSchemaProjection(registry, coordinator).names(schemas))
        for (name in listOf("web_search", "web_fetch")) {
            val result = coordinator.execute(
                LocalToolCall("test-$name", name, JsonObject(emptyMap()), "{}"),
                allowMutation = true,
            )
            assertEquals("NETWORK_SEARCH_DISABLED", result.errorCode)
        }
        assertEquals(0, executed)
        networkEnabled = true
        coordinator.enableOptionalTools(listOf("web_search", "web_fetch"))
        assertTrue("web_search" in coordinator.enabledOptionalSnapshot())
        assertTrue("web_fetch" in coordinator.enabledOptionalSnapshot())
    }

    @Test
    fun workEnablesNetworkToolsByDefaultWithoutEnablingWrites() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool(
                name = "web_search", access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "网络", keywords = setOf("联网"),
            ) { ToolResult("ok") })
            register(tool(
                name = "web_fetch", access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "网络", keywords = setOf("网页读取"),
            ) { ToolResult("ok") })
            register(tool(
                name = "write", access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.MUTATION, exposure = ToolExposure.OPTIONAL,
                keywords = setOf("写入"),
            ) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        val detached = linkedSetOf<String>()
        coordinator.prepareWorkTurnCapabilities("整理代码", emptyList(), { false }, detached)
        assertEquals(setOf("web_search", "web_fetch"), detached)
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())
        val projection = LocalToolSchemaProjection(registry, coordinator)
        val schemas = projection.modelSchemas(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = false,
            history = emptyList(),
            enabledOptional = detached,
        )
        assertEquals(setOf("web_search", "web_fetch"), projection.names(schemas).toSet())
    }

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
    fun explicitSubagentAllowlistCanActivateOptionalReadOnlyTool() {
        val registry = ToolRegistry().apply {
            register(tool(
                name = "remote_lookup",
                access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL,
                family = "网络",
                keywords = setOf("remote", "lookup"),
            ) { ToolResult("ok") })
        }
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        val disabled = projection.subagentSchemas(
            allowMutation = false,
            allowVirtualScreen = false,
            enabledOptional = emptySet(),
        )
        assertFalse("remote_lookup" in projection.names(disabled))
        val allowed = projection.subagentSchemas(
            allowMutation = false,
            allowVirtualScreen = false,
            enabledOptional = setOf("remote_lookup"),
        )
        assertTrue("remote_lookup" in projection.names(allowed))
        assertTrue("remote_lookup" !in projection.names(projection.subagentSchemas(
            allowMutation = false,
            allowVirtualScreen = false,
            enabledOptional = emptySet(),
        )))
    }

    @Test
    fun readonlyAndPlanScopesRejectBackgroundWebFetchBeforeExecution() = runBlocking {
        var executed = 0
        val registry = ToolRegistry().apply {
            register(webFetchTool {
                executed += 1
                ToolResult("ok")
            })
        }
        val arguments = buildJsonObject {
            put("url", "https://example.com")
            put("run_in_background", true)
        }
        val call = LocalToolCall(
            id = "fetch-bg",
            name = "web_fetch",
            arguments = arguments,
            rawArguments = arguments.toString(),
        )

        val readonly = coordinator(registry).executeScoped(
            original = call,
            sessionId = "readonly",
            allowMutation = false,
            planModeEnabled = false,
            approval = { _, _, _ -> true },
        )
        val planning = coordinator(registry).executeScoped(
            original = call,
            sessionId = "planning",
            allowMutation = true,
            planModeEnabled = true,
            approval = { _, _, _ -> true },
        )

        assertEquals("MUTATION_SCOPE_BLOCKED", readonly.errorCode)
        assertEquals("PLAN_MODE_BLOCKED", planning.errorCode)
        assertEquals(0, executed)
    }

    @Test
    fun executorThrowAfterAdmissionIsNeverMarkedSafeToRetry() = runBlocking {
        var executionStarts = 0
        val registry = ToolRegistry().apply {
            register(
                tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.MUTATION,
                ) {
                    throw IllegalStateException("写入后的连接中断")
                },
            )
        }
        val coordinator = coordinator(
            registry = registry,
            recordExecutionStarted = { _, _, _ -> executionStarts += 1 },
        )

        val result = coordinator.execute(
            LocalToolCall("c-throw", "write", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertTrue(result.isError)
        assertEquals("TOOL_ERROR", result.errorCode)
        assertFalse(result.retryable)
        assertEquals(AgentToolSideEffect.POSSIBLE, result.sideEffect)
        assertEquals(1, executionStarts)
        assertTrue(result.recoveryHint.orEmpty().contains("不要直接重试"))
    }

    @Test
    fun executionIdentityIsStableAcrossToolContextAndDurableStartCallback() = runBlocking {
        val observedAttributes = mutableMapOf<String, Any?>()
        var startedIdentity: LocalToolExecutionIdentity? = null
        val registry = ToolRegistry().apply {
            register(HarnessTool(
                name = "identity_probe",
                schema = functionToolSchema("identity_probe", "检查执行身份"),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
                executor = HarnessToolExecutor { context, _, _ ->
                    observedAttributes.putAll(context.attributes)
                    ToolResult("ok")
                },
            ))
        }
        val coordinator = coordinator(
            registry = registry,
            recordExecutionStarted = { _, _, identity -> startedIdentity = identity },
            executionIdFactory = { "exec-fixed" },
        )

        val result = coordinator.execute(
            LocalToolCall("call-root", "identity_probe", JsonObject(emptyMap()), "{}"),
            allowMutation = true,
        )

        assertFalse(result.isError)
        assertEquals("exec-fixed", observedAttributes["execution_id"])
        assertEquals("call-root", observedAttributes["root_call_id"])
        assertEquals(
            LocalToolExecutionIdentity(
                executionId = "exec-fixed",
                rootCallId = "call-root",
            ),
            startedIdentity,
        )
    }

    @Test
    fun deniedApprovalNeverRecordsExecutorStart() = runBlocking {
        var executionStarts = 0
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
        val coordinator = coordinator(
            registry = registry,
            recordExecutionStarted = { _, _, _ -> executionStarts += 1 },
        )

        val result = coordinator.executeScoped(
            original = LocalToolCall("c-denied", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "s1",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ -> false },
        )

        assertEquals("APPROVAL_DENIED", result.errorCode)
        assertEquals(0, executionStarts)
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
    fun readonlyAndPlanToolSchemasHideBackgroundWebFetchOption() {
        val registry = ToolRegistry().apply { register(webFetchTool { ToolResult("ok") }) }
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))

        val readonly = projection.subagentSchemas(
            allowMutation = false,
            allowVirtualScreen = false,
            enabledOptional = emptySet(),
        ).single().jsonObject
        val planning = projection.modelSchemas(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = true,
            history = emptyList(),
        ).single().jsonObject
        val writable = projection.subagentSchemas(
            allowMutation = true,
            allowVirtualScreen = false,
            enabledOptional = emptySet(),
        ).single().jsonObject

        fun properties(schema: JsonObject): JsonObject =
            schema["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject

        assertFalse("run_in_background" in properties(readonly))
        assertFalse("run_in_background" in properties(planning))
        assertTrue("run_in_background" in properties(writable))
        assertTrue(readonly["function"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("不写入工作区"))
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
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = true,
            history = emptyList(),
            enabledOptional = setOf("remote_lookup"),
        )

        assertEquals(listOf("read", "remote_lookup"), projection.names(schemas))
    }

    private fun coordinator(
        registry: ToolRegistry,
        planMode: Boolean = false,
        recordExecutionStarted: suspend (
            String,
            LocalToolCall,
            LocalToolExecutionIdentity,
        ) -> Unit = { _, _, _ -> },
        executionIdFactory: () -> String = { "exec-test" },
        networkSearchEnabled: () -> Boolean = { true },
    ) = LocalToolExecutionCoordinator(
        registry = registry,
        currentSessionId = { "s1" },
        planMode = { planMode },
        enabledOptionalTools = linkedSetOf(),
        requestApproval = { _, _, _ -> true },
        recordExecutionStarted = recordExecutionStarted,
        executionIdFactory = executionIdFactory,
        networkSearchEnabled = networkSearchEnabled,
    )

    private fun webFetchTool(execute: suspend () -> ToolResult) = HarnessTool(
        name = "web_fetch",
        schema = LocalToolCatalog.specs.first { schema ->
            schema.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "web_fetch"
        }.jsonObject,
        access = ToolAccess.NETWORK,
        approvalPolicy = ToolApprovalPolicy.NEVER,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata("网络"),
        executor = HarnessToolExecutor { _, _, _ -> execute() },
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
