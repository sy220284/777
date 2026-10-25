package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.agent.localAgentRunPolicy
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.LocalModelToolStepSurface
import com.labteto.dshmobile.local.tools.LocalToolSchemaProjection
import com.labteto.dshmobile.local.work.LocalWorkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    @Test fun teamRevocationWhileWaitingForSharedWorkspaceLeaseCannotWrite() = runBlocking {
        val gate = com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate()
        val leaseHeld = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val firstAdmission = CompletableDeferred<Unit>()
        val holder = async {
            gate.withWrite {
                leaseHeld.complete(Unit)
                releaseLease.await()
            }
        }
        leaseHeld.await()
        var taskStillAuthorized = true
        var admissionChecks = 0
        var executed = 0
        val tool = tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
            executed++
            ToolResult("should not write")
        }.copy(metadata = ToolMetadata("文件", workspaceAccess = ToolWorkspaceAccess.WRITE))
        val registry = ToolRegistry().apply { register(tool) }
        val running = async {
            coordinator(registry, workspaceGate = gate).executeScoped(
                LocalToolCall("lease-race", "write", JsonObject(emptyMap()), "{}"),
                "team-session", true, false, approval = { _, _, _ -> true },
                beforeExecution = {
                    admissionChecks++
                    if (admissionChecks == 1) firstAdmission.complete(Unit)
                    taskStillAuthorized
                },
            )
        }
        try {
            firstAdmission.await() // First check is true, then the call waits for the writer.
            taskStillAuthorized = false // Lead revokes the Task while workspace lease is held.
            releaseLease.complete(Unit)
            val result = running.await()
            assertTrue(result.isError)
            assertEquals(0, executed)
            assertTrue(admissionChecks >= 2)
        } finally {
            releaseLease.complete(Unit)
            holder.join()
            running.cancel()
        }
    }


    @Test fun teamRevocationDuringApprovalCannotReachARegisteredWriteExecutor() = runBlocking {
        var liveTask = true
        var checkedAfterApproval = 0
        var executed = 0
        val registry = ToolRegistry().apply {
            register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
                executed++
                ToolResult("unacceptable-write")
            })
        }
        val result = coordinator(registry).executeScoped(
            original = LocalToolCall(
                "late-team-write", "write", JsonObject(emptyMap()), "{}",
            ),
            sessionId = "team-session",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ ->
                liveTask = false // Simulates Lead reassigning the task while approval is open.
                true
            },
            beforeExecution = {
                checkedAfterApproval++
                liveTask
            },
        )
        assertTrue(result.isError)
        assertEquals(0, executed)
        assertTrue(checkedAfterApproval > 0)
    }


    @Test
    fun explicitlyDiscoveredCapabilitySurvivesLaterWorkStepHeuristicRefresh() {
        val registry = ToolRegistry().apply {
            register(tool(name = "process_exec", access = ToolAccess.PROCESS,
                approval = ToolApprovalPolicy.ALWAYS, exposure = ToolExposure.OPTIONAL,
                family = "运行时", keywords = setOf("执行命令")) { ToolResult("ok") })
            repeat(300) { index ->
                register(tool(name = "search_batch_$index", access = ToolAccess.READ_ONLY,
                    approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                    family = "搜索扩展", keywords = setOf("代码搜索")) { ToolResult("ok") })
            }
        }
        val coordinator = coordinator(registry)
        // User task heuristics may select many expensive optional tool definitions first.
        coordinator.enableTaskRelevantOptionalTools("代码搜索")
        assertFalse("process_exec" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))
        coordinator.searchCapabilities("process_exec")
        assertTrue("process_exec" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))

        // A later Work model step must append task-relevant registered tools without
        // moving the explicit capability_search choice behind hundreds of heuristic hits.
        coordinator.enableTaskRelevantOptionalTools("代码搜索", prioritize = false)
        val visible = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
        assertTrue("process_exec" in visible)
        assertEquals("process_exec", visible.first())
        // A new user turn deliberately gets to reorder relevance again.
        coordinator.enableTaskRelevantOptionalTools("代码搜索", prioritize = true)
        assertEquals("search_batch_0", coordinator.enabledOptionalSnapshot().first())
    }


    @Test
    fun wrongToolArgumentsGetRealSchemaGuidanceBeforeAnyExecutorRuns() = runBlocking {
        var executions = 0
        val tool = tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
            executions++
            ToolResult("should never execute")
        }.copy(schema = functionToolSchema(
            name = "read",
            description = "Read the requested workspace file.",
            properties = com.labteto.dshmobile.harness.tools.simpleToolProperties(
                mapOf("path" to "string", "lines" to "array"),
            ),
            required = setOf("path"),
        ))
        val registry = ToolRegistry().apply { register(tool) }
        val failed = coordinator(registry).executeScoped(
            original = LocalToolCall(
                "wrong-shape", "read",
                buildJsonObject { put("lines", "this is not an array") },
                """{"lines":"this is not an array"}""",
            ),
            sessionId = "work", allowMutation = false, planModeEnabled = false,
            approval = { _, _, _ -> error("invalid arguments must never reach approval") },
        )
        assertTrue(failed.isError)
        assertEquals("INVALID_TOOL_ARGUMENTS", failed.errorCode)
        assertEquals(0, executions)
        assertEquals(AgentToolSideEffect.NONE, failed.sideEffect)
        assertTrue(failed.recoveryHint.orEmpty().contains("path"))
        assertTrue(failed.recoveryHint.orEmpty().contains("lines:array"))
        assertTrue(failed.recoveryHint.orEmpty().contains("本轮实际提供"))
    }


    @Test
    fun registeredOptionalCommandBecomesExecutableAfterModelDiscovery() = runBlocking {
        var executed = 0
        val registry = ToolRegistry().apply {
            register(tool("process_exec", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS,
                exposure = ToolExposure.OPTIONAL, family = "运行时",
                keywords = setOf("运行命令")) {
                executed++
                ToolResult("real-execution-receipt")
            })
        }
        val executor = coordinator(registry)
        val projection = LocalToolSchemaProjection(registry, executor)
        val surface = LocalModelToolStepSurface()
        val policy = localAgentRunPolicy(LocalUsageMode.WORK)
        val state = com.labteto.dshmobile.local.model.LocalModelState()

        surface.capture(projection.modelSchemas(policy, state, false, emptyList()))
        assertFalse(surface.allows("process_exec"))

        val discovered = executor.searchCapabilities("process_exec")
        assertTrue(discovered.contains("process_exec"))
        surface.capture(projection.modelSchemas(
            policy, state, false, emptyList(), executor.enabledOptionalSnapshot(),
        ))
        assertTrue(surface.allows("process_exec"))
        val result = executor.executeScoped(
            LocalToolCall("dynamic-call", "process_exec", JsonObject(emptyMap()), "{}"),
            sessionId = "work", allowMutation = true, planModeEnabled = false,
            approval = { _, _, _ -> true },
        )
        assertFalse(result.isError)
        assertEquals("real-execution-receipt", result.content)
        assertEquals(1, executed)
    }


    @Test fun cancellationRetiresTheRealExecutingBody() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val changes = mutableListOf<Boolean>()
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                kotlinx.coroutines.awaitCancellation()
            })
        }
        val task = async {
            coordinator(registry).executeScoped(
                LocalToolCall("cancel", "read", JsonObject(emptyMap()), "{}"),
                "work", true, false, approval = { _, _, _ -> true },
                onExecutionActive = { changes += it; if (it) started.complete(Unit) })
        }
        started.await()
        task.cancel()
        task.join()
        assertEquals(listOf(true, false), changes)
    }
    @Test fun completedBodyReleasesCpuWhileAnotherBatchCallStillAwaitsApproval() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.ALWAYS) { ToolResult("read") })
        }
        val executor = coordinator(registry)
        val approval = CompletableDeferred<Boolean>()
        val waiting = CompletableDeferred<Unit>()
        val active = linkedSetOf<String>()
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            executor.executeScoped(LocalToolCall("first", "read", JsonObject(emptyMap()), "{}"),
                "work", true, false, approval = { _, _, _ -> true },
                onExecutionActive = { if (it) active += "first" else active -= "first" })
        }
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            executor.executeScoped(LocalToolCall("second", "read", JsonObject(emptyMap()), "{}"),
                "work", true, false, approval = { _, _, _ -> waiting.complete(Unit); approval.await() },
                onExecutionActive = { if (it) active += "second" else active -= "second" })
        }
        try {
            waiting.await()
            assertFalse(first.await().isError)
            assertFalse(second.isCompleted)
            // The Agent batch has not returned or published either ToolFinished receipt yet.
            assertTrue(active.isEmpty())
            assertFalse(com.labteto.dshmobile.local.work.workRunNeedsCpu(setOf("second"), active))
            approval.complete(true)
            assertFalse(second.await().isError)
            assertTrue(active.isEmpty())
        } finally { approval.complete(false) }
    }
    @Test fun workEvidenceReadAndCommitExcludeActualCoordinatorWorkspaceMutation() = runBlocking {
        val gate = com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate()
        val source = java.nio.file.Files.createTempFile("work-proof", ".txt").toFile()
        val commit = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        source.writeText("original")
        try {
            val registry = ToolRegistry().apply {
                register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.NEVER) {
                    order += "write"
                    source.writeText("replacement")
                    ToolResult("written")
                })
            }
            val evidence = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                gate.withRead {
                    val read = source.readText()
                    order += "read"
                    commit.await()
                    assertEquals(read, source.readText())
                    order += "commit"
                }
            }
            val write = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                coordinator(registry, workspaceGate = gate).executeScoped(
                    LocalToolCall("write-proof", "write", JsonObject(emptyMap()), "{}"),
                    "session-proof", true, false, approval = { _, _, _ -> true })
            }
            assertFalse(write.isCompleted)
            commit.complete(Unit)
            evidence.await()
            assertFalse(write.await().isError)
            assertEquals(listOf("read", "commit", "write"), order)
            assertEquals("replacement", source.readText())
        } finally { source.delete() }
    }

    @Test fun automaticReadAdmissionStillRecordsExactCallBeforeExecutorAndAuditFailureStopsIt() = runBlocking {
        var executions = 0
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                executions++
                ToolResult("read result")
            })
        }
        val decisions = mutableListOf<String>()
        val owner = coordinator(registry, recordAuthorizationDecision = { session, call, execution, approved ->
            assertTrue(approved)
            assertEquals(0, executions)
            decisions += "$session:${call.id}:${execution.executionId}"
        })
        val result = owner.executeScoped(LocalToolCall("call-read", "read", JsonObject(emptyMap()), "{}"),
            "session-read", false, false, approval = { _, _, _ -> error("read does not ask approval") })
        assertFalse(result.isError)
        assertEquals(listOf("session-read:call-read:exec-test"), decisions)
        assertEquals(1, executions)
        val blocked = coordinator(registry, recordAuthorizationDecision = { _, _, _, _ ->
            throw IllegalStateException("storage failed")
        }).executeScoped(LocalToolCall("call-blocked", "read", JsonObject(emptyMap()), "{}"),
            "session-read", false, false, approval = { _, _, _ -> true })
        assertTrue(blocked.isError)
        assertEquals(1, executions)
    }

    @Test
    fun pinnedGitHubIsLoadedAcrossTurnsAndNeverBypassesCredentialGate() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("github_list_prs", ToolAccess.NETWORK, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, family = "GitHub", keywords = setOf("github")) { ToolResult("ok") })
            register(tool("unrelated_task", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, keywords = setOf("处理")) { ToolResult("ok") })
        }
        var pinned = listOf("github")
        val coordinator = coordinator(registry, pinnedConnectorIds = { pinned })
        coordinator.prepareWorkTurnCapabilities("处理当前项目", emptyList(), { true })
        assertEquals("github_list_prs", coordinator.enabledOptionalSnapshot().first())
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", emptyList(), { true })
        assertTrue("github_list_prs" in coordinator.enabledOptionalSnapshot())
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", emptyList(), { false })
        assertFalse("github_list_prs" in coordinator.enabledOptionalSnapshot())
        pinned = emptyList()
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", emptyList(), { true })
        assertFalse("github_list_prs" in coordinator.enabledOptionalSnapshot())
    }

    @Test
    fun pinnedMcpLoadsOnlyRegisteredServiceToolsAheadOfHeuristicTools() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("mcp_figma_get_file", ToolAccess.NETWORK, ToolApprovalPolicy.ALWAYS,
                exposure = ToolExposure.OPTIONAL, family = "MCP", keywords = setOf("figma")) { ToolResult("ok") })
            register(tool("mcp_other_get_file", ToolAccess.NETWORK, ToolApprovalPolicy.ALWAYS,
                exposure = ToolExposure.OPTIONAL, family = "MCP", keywords = setOf("figma")) { ToolResult("ok") })
            register(tool("mcp_figma_fake", ToolAccess.NETWORK, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, family = "Other", keywords = setOf("fake")) { ToolResult("ok") })
            register(tool("background_job", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, keywords = setOf("设计")) { ToolResult("ok") })
        }
        var pinned = listOf("figma")
        val coordinator = coordinator(registry, pinnedConnectorIds = { pinned })
        coordinator.prepareWorkTurnCapabilities("设计", emptyList(), { false })
        val selected = coordinator.enabledOptionalSnapshot().toList()
        assertEquals("mcp_figma_get_file", selected.first())
        assertTrue("background_job" in selected)
        assertFalse("mcp_other_get_file" in selected)
        assertFalse("mcp_figma_fake" in selected)
        pinned = emptyList()
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", emptyList(), { false })
        assertFalse("mcp_figma_get_file" in coordinator.enabledOptionalSnapshot())
    }

    @Test
    fun pinnedConnectorRecoversPriorityAfterLaterModelStepHeuristics() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("mcp_figma_frame", ToolAccess.NETWORK, ToolApprovalPolicy.ALWAYS,
                exposure = ToolExposure.OPTIONAL, family = "MCP", keywords = setOf("figma")) { ToolResult("ok") })
            register(tool("autodiscovered", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, keywords = setOf("查找")) { ToolResult("ok") })
            register(tool("github_prs", ToolAccess.NETWORK, ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, family = "GitHub", keywords = setOf("github")) { ToolResult("ok") })
        }
        val c = coordinator(registry, pinnedConnectorIds = { listOf("figma", "github") })
        c.prepareWorkTurnCapabilities("继续", emptyList(), { false })
        c.enableTaskRelevantOptionalTools("查找", prioritize = true)
        assertEquals("autodiscovered", c.enabledOptionalSnapshot().first())
        c.prioritizePinnedForModelStep()
        assertEquals("mcp_figma_frame", c.enabledOptionalSnapshot().first())
        // A fixed but unauthenticated GitHub may never appear during a later model step.
        assertFalse("github_prs" in c.enabledOptionalSnapshot())
    }

    @Test
    fun dynamicallyRegisteredMcpToolsArePromotedWithoutGuessingOrCrossServerLeakage() {
        val registry = ToolRegistry().apply {
            repeat(180) { index ->
                register(tool("older_$index", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL, keywords = setOf("old")) { ToolResult("old") })
            }
        }
        val discovery = com.labteto.dshmobile.local.work.LocalWorkRunMcpDiscovery()
        assertTrue(discovery.observe(registry).isEmpty())
        registry.register(tool("mcp_x_twitter_x_post", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.OPTIONAL, family = "MCP", keywords = setOf("x-twitter")) { ToolResult("post") })
        registry.register(tool("mcp_other_x_post", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.OPTIONAL, family = "MCP", keywords = setOf("other")) { ToolResult("other") })
        registry.register(tool("mcp_fake_x_post", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.OPTIONAL, family = "Other", keywords = setOf("fake")) { ToolResult("fake") })
        val created = discovery.observe(registry)
        assertEquals(listOf("mcp_x_twitter_x_post", "mcp_other_x_post"), created)
        val coordinator = coordinator(registry)
        coordinator.enableOptionalTools((0 until 180).map { "older_$it" })
        coordinator.prioritizeRunCreatedMcpTools(created)
        val visible = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
        assertTrue("mcp_x_twitter_x_post" in visible)
        assertTrue("mcp_other_x_post" in visible)
        assertFalse("mcp_fake_x_post" in visible)
        assertEquals("mcp_x_twitter_x_post", coordinator.enabledOptionalSnapshot().first())
        assertEquals(created, discovery.observe(registry))
        registry.unregister("mcp_x_twitter_x_post")
        assertEquals(listOf("mcp_other_x_post"), discovery.observe(registry))
    }

    @Test
    fun pinnedImagegenMakesRealMediaToolsVisibleAcrossStepsWithoutAutoSubmitting() = runBlocking {
        val registry = ToolRegistry().apply {
            for (name in listOf("media_image_options", "media_image_create", "media_image_status")) {
                register(tool(
                    name, if (name == "media_image_create") ToolAccess.PRIVILEGED else ToolAccess.READ_ONLY,
                    if (name == "media_image_create") ToolApprovalPolicy.ALWAYS else ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL, family = "媒体创作",
                    keywords = setOf("生图"),
                ) { ToolResult("stub") })
            }
        }
        var selected = listOf("imagegen")
        val coordinator = coordinator(registry, pinnedSkillIds = { selected })
        coordinator.prepareWorkTurnCapabilities("继续", enableGitHub = false)
        assertTrue("media_image_create" in coordinator.enabledOptionalSnapshot())
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", enableGitHub = false)
        coordinator.prioritizePinnedForModelStep()
        assertTrue("media_image_options" in coordinator.enabledOptionalSnapshot())
        selected = emptyList()
        coordinator.clearTurnCapabilities()
        coordinator.prepareWorkTurnCapabilities("继续", enableGitHub = false)
        assertFalse("media_image_create" in coordinator.enabledOptionalSnapshot())
    }

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
            repeat(300) { index ->
                register(tool(name = "background_$index", access = ToolAccess.READ_ONLY,
                    approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                    family = "后台扩展", keywords = setOf("background")) { ToolResult("ok") })
            }
            register(tool(name = "critical_lookup", access = ToolAccess.READ_ONLY,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "精确发现", keywords = setOf("critical")) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        coordinator.enableOptionalTools((0 until 300).map { "background_$it" })
        assertFalse("critical_lookup" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))
        val response = coordinator.searchCapabilities("critical_lookup")
        assertTrue(response.contains("critical_lookup"))
        assertTrue(coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)).contains("critical_lookup"))
    }

    @Test
    fun currentTaskReordersPreviouslySelectedToolsAheadOfOlderWork() {
        val registry = ToolRegistry().apply {
            repeat(200) { index ->
                register(tool(name = "older_$index", access = ToolAccess.READ_ONLY,
                    approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                    family = "旧工具", keywords = setOf("旧任务")) { ToolResult("ok") })
            }
            register(tool(name = "current_task_tool", access = ToolAccess.READ_ONLY,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "新任务", keywords = setOf("现在处理")) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        coordinator.enableOptionalTools((0 until 200).map { "older_$it" } + "current_task_tool")
        coordinator.enableTaskRelevantOptionalTools("现在处理这个问题")
        val visible = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
        assertEquals("current_task_tool", visible.first())
    }

    @Test
    fun genericCategoryDoesNotPreactivateUnrelatedToolsInRealCoordinator() {
        val registry = ToolRegistry().apply {
            register(tool(name = "team_spawn", access = ToolAccess.AGENT_CONTROL,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "智能体", keywords = setOf("创建团队")) { ToolResult("ok") })
            register(tool(name = "team_stop", access = ToolAccess.AGENT_CONTROL,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "智能体", keywords = setOf("停止团队")) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        coordinator.enableTaskRelevantOptionalTools("了解智能体架构")
        assertTrue(coordinator.enabledOptionalSnapshot().isEmpty())

        coordinator.enableTaskRelevantOptionalTools("创建团队")
        assertEquals(setOf("team_spawn"), coordinator.enabledOptionalSnapshot())
        assertTrue(coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
            .contains("team_spawn"))
        assertTrue(coordinator.searchCapabilities("停止团队").contains("team_stop"))
        assertTrue(coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
            .contains("team_stop"))
    }

    @Test
    fun disabledNetworkToolsRemainDiscoverableButNotCallable() {
        val registry = ToolRegistry().apply {
            register(tool(name = "web_search", access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "网络", keywords = setOf("联网")) { ToolResult("ok") })
        }
        val coordinator = coordinator(registry, networkSearchEnabled = { false })
        val directory = coordinator.searchCapabilities("能力目录")
        assertTrue(directory.contains("web_search（当前设置已关闭）"))
        assertTrue(coordinator.capabilitySummary().contains("运行策略关闭：web_search"))
        assertFalse("web_search" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))
        coordinator.searchCapabilities("联网")
        assertFalse("web_search" in coordinator.enabledOptionalSnapshot())
    }

    @Test
    fun unknownCapabilityOffersAnExecutableBootstrapPathWithoutPretendingRegistration() {
        val coordinator = coordinator(ToolRegistry())
        val result = coordinator.searchCapabilities("尚未实现的 X 平台连接器")
        assertTrue(result.contains("没有精确匹配"))
        assertTrue(result.contains("GitHub"))
        assertTrue(result.contains("MCP stdio"))
        assertTrue(result.contains("下一模型步骤"))
        assertFalse(coordinator.enabledOptionalSnapshot().any { it.startsWith("mcp_") })
    }

    @Test
    fun newlyRegisteredTaskToolsTakePriorityOverOlderOptionalSchemas() {
        val registry = ToolRegistry().apply {
            repeat(300) { index ->
                register(tool(name = "old_$index", access = ToolAccess.READ_ONLY,
                    approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                    family = "后台", keywords = setOf("旧任务")) { ToolResult("ok") })
            }
        }
        val coordinator = coordinator(registry)
        coordinator.enableOptionalTools((0 until 300).map { "old_$it" })
        registry.register(tool(name = "new_capability", access = ToolAccess.READ_ONLY,
            approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
            family = "扩展", keywords = setOf("当前任务")) { ToolResult("ok") })
        coordinator.enableTaskRelevantOptionalTools("使用当前任务新能力")
        val names = coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK))
        assertTrue("new_capability" in names)
        assertEquals("new_capability", names.first())
    }

    @Test
    fun newlyRegisteredExtensionsAppearInDirectoryAndCanBeLoadedWithoutCodeChanges() {
        val registry = ToolRegistry().apply {
            register(tool(name = "local_core", access = ToolAccess.READ_ONLY,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.CORE,
                family = "文件") { ToolResult("ok") })
        }
        val coordinator = coordinator(registry)
        val initial = coordinator.searchCapabilities("能力目录")
        assertFalse(initial.contains("mcp_newly_added"))
        registry.register(tool(name = "mcp_newly_added", access = ToolAccess.PRIVILEGED,
            approval = ToolApprovalPolicy.ALWAYS, exposure = ToolExposure.OPTIONAL,
            family = "MCP", keywords = setOf("新服务", "MCP")) { ToolResult("ok") })
        val directory = coordinator.searchCapabilities("工具与能力检测")
        assertTrue(directory.contains("mcp_newly_added"))
        val fallback = coordinator.searchCapabilities("完全不在关键词中的陌生描述")
        assertTrue(fallback.contains("mcp_newly_added"))
        assertFalse("mcp_newly_added" in coordinator.enabledOptionalSnapshot())
        val result = coordinator.searchCapabilities("mcp_newly_added")
        assertTrue(result.contains("mcp_newly_added"))
        assertTrue("mcp_newly_added" in coordinator.visibleToolNames(localAgentRunPolicy(LocalUsageMode.WORK)))
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
    fun readonlyToolExceptionsKeepActionableCodesAndSafeRetries() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw java.io.FileNotFoundException("不存在的文件")
            })
            register(tool("glob", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw IllegalArgumentException("目录错误")
            })
            register(tool("lsp_diagnostics", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw IllegalArgumentException("未检测到语言服务器")
            })
            register(tool("io_probe", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw java.io.IOException("临时读取中断")
            })
            register(tool("missing_file", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw java.nio.file.NoSuchFileException("missing.txt")
            })
            register(tool("denied_file", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw java.nio.file.AccessDeniedException("private.txt")
            })
            register(tool("lsp_start_denied", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                throw IllegalStateException("用户拒绝启动代码智能进程")
            })
        }
        val coordinator = coordinator(registry)
        suspend fun result(name: String) = coordinator.execute(
            LocalToolCall("c-" + name, name, JsonObject(emptyMap()), "{}"), allowMutation = true,
        )
        assertEquals("TOOL_NOT_FOUND", result("read").errorCode)
        assertEquals("TOOL_INVALID_ARGUMENT", result("glob").errorCode)
        assertEquals("TOOL_UNAVAILABLE", result("lsp_diagnostics").errorCode)
        val io = result("io_probe")
        assertEquals("TOOL_IO_ERROR", io.errorCode)
        assertTrue(io.retryable)
        assertEquals(AgentToolSideEffect.NONE, io.sideEffect)
        assertEquals("TOOL_NOT_FOUND", result("missing_file").errorCode)
        val denied = result("denied_file")
        assertEquals("TOOL_PERMISSION_DENIED", denied.errorCode)
        assertFalse(denied.retryable)
        assertEquals("APPROVAL_DENIED", result("lsp_start_denied").errorCode)
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
        assertEquals("exec-test", result.executionId)
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
                verifiedReadOnly = true,
            ),
            startedIdentity,
        )
    }

    @Test
    fun releasedSessionOwnerCannotEnterToolAfterApprovalWait() = runBlocking {
        val owners = com.labteto.dshmobile.harness.session.SessionExecutionRegistry()
        val lease = owners.acquire("s1", com.labteto.dshmobile.harness.session.SessionExecutionOwner("run", true))
        var executed = false
        var started = false
        val registry = ToolRegistry().apply {
            register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
                executed = true
                ToolResult("ok")
            })
        }
        val c = coordinator(registry, recordExecutionStarted = { _, _, _ -> started = true })
        val result = withContext(com.labteto.dshmobile.harness.session.SessionExecutionContext(lease)) {
            c.executeScoped(
                LocalToolCall("stale", "write", JsonObject(emptyMap()), "{}"),
                sessionId = "s1", allowMutation = true, planModeEnabled = false,
                approval = { _, _, _ -> lease.close(); true },
            )
        }
        assertTrue(result.isError)
        assertFalse(executed)
        assertFalse(started)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertEquals(null, result.executionId)
    }

    @Test
    fun runHandoffDuringApprovalCannotExecuteOldTool() = runBlocking {
        val owners = com.labteto.dshmobile.harness.agent.AgentRunOwnership()
        val first = com.labteto.dshmobile.harness.agent.AgentRunIdentity("s1", "main", "old")
        val next = first.copy(runId = "new")
        owners.claim(first) { }
        var executed = false
        val registry = ToolRegistry().apply {
            register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
                executed = true
                ToolResult("ok")
            })
        }
        val result = withContext(owners.executionContext(first)) {
            coordinator(registry).executeScoped(
                LocalToolCall("call", "write", JsonObject(emptyMap()), "{}"),
                sessionId = "s1", allowMutation = true, planModeEnabled = false,
                approval = { _, _, _ -> owners.claim(next) { }; true },
            )
        }
        assertFalse(executed)
        assertEquals("TASK_CANCELLED", result.errorCode)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertTrue(owners.isCurrent(next))
    }

    @Test
    fun permissionDecisionIsExactToSessionCallAndExecutionAndCannotExecuteWithoutAudit() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("mcp_demo_write", ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS,
                exposure = ToolExposure.OPTIONAL, family = "MCP",
                keywords = setOf("demo")) { ToolResult("remote ok") })
        }
        data class Decision(val session: String, val call: String, val execution: String, val approved: Boolean)
        val decisions = mutableListOf<Decision>()
        val coordinator = coordinator(registry, recordAuthorizationDecision = { session, call, execution, granted ->
            decisions += Decision(session, call.id, execution.executionId, granted)
        })
        suspend fun run(session: String, callId: String, approved: Boolean) =
            coordinator.executeScoped(LocalToolCall(callId, "mcp_demo_write", JsonObject(emptyMap()), "{}"),
                sessionId = session, allowMutation = true, planModeEnabled = false,
                approval = { _, _, _ -> approved })
        assertFalse(run("session-a", "call-a", true).isError)
        assertEquals("APPROVAL_DENIED", run("session-b", "call-b", false).errorCode)
        assertEquals(listOf(
            Decision("session-a", "call-a", "exec-test", true),
            Decision("session-b", "call-b", "exec-test", false),
        ), decisions)
        val missingRecord = coordinator(registry, recordAuthorizationDecision = { _, _, _, _ ->
            throw IllegalStateException("EventLog unavailable")
        })
        val blocked = missingRecord.executeScoped(
            LocalToolCall("call-c", "mcp_demo_write", JsonObject(emptyMap()), "{}"),
            sessionId = "session-c", allowMutation = true, planModeEnabled = false,
            approval = { _, _, _ -> true })
        assertTrue(blocked.isError)
        assertTrue(decisions.none { it.session == "session-c" })
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
    fun correctionAfterApprovalButBeforeExecutionRejectsStaleWriteWithoutSideEffect() = runBlocking {
        var executed = false
        var corrected = false
        val registry = ToolRegistry().apply {
            register(tool(
                name = "write",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.ALWAYS,
            ) {
                executed = true
                ToolResult("should not execute")
            })
        }
        val result = coordinator(registry).executeScoped(
            original = LocalToolCall("tool-old", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "work",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ ->
                corrected = true
                true
            },
            beforeExecution = { !corrected },
        )
        assertEquals("WORK_TOOL_SUPERSEDED", result.errorCode)
        assertTrue(result.isError)
        assertFalse(result.retryable)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertFalse(executed)
    }

    @Test
    fun unchangedSamplingGenerationStillExecutesApprovedWriteOnce() = runBlocking {
        var executed = 0
        val registry = ToolRegistry().apply {
            register(tool(
                name = "write",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.ALWAYS,
            ) {
                executed++
                ToolResult("ok")
            })
        }
        val result = coordinator(registry).executeScoped(
            original = LocalToolCall("tool-current", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "work",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ -> true },
            beforeExecution = { true },
        )
        assertFalse(result.isError)
        assertEquals(1, executed)
    }


    @Test
    fun correctionWhileOldWriteWaitsForWorkspaceLeaseRejectsItBeforeExecution() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val reachedAdmission = CompletableDeferred<Unit>()
        var corrected = false
        var guardChecks = 0
        var staleExecutions = 0
        val registry = ToolRegistry().apply {
            register(tool(
                name = "write",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.NEVER,
            ) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                ToolResult("first done")
            })
            register(tool(
                name = "edit",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.NEVER,
            ) {
                staleExecutions++
                ToolResult("must not execute")
            })
        }
        val coordinator = coordinator(registry)
        val active = async {
            coordinator.executeScoped(
                original = LocalToolCall("active", "write", JsonObject(emptyMap()), "{}"),
                sessionId = "work",
                allowMutation = true,
                planModeEnabled = false,
                approval = { _, _, _ -> true },
            )
        }
        firstStarted.await()
        val waiting = async {
            coordinator.executeScoped(
                original = LocalToolCall("stale", "edit", JsonObject(emptyMap()), "{}"),
                sessionId = "work",
                allowMutation = true,
                planModeEnabled = false,
                approval = { _, _, _ -> true },
                beforeExecution = {
                    guardChecks++
                    reachedAdmission.complete(Unit)
                    !corrected
                },
            )
        }
        reachedAdmission.await()
        corrected = true
        releaseFirst.complete(Unit)
        assertFalse(active.await().isError)
        val rejected = waiting.await()
        assertEquals("WORK_TOOL_SUPERSEDED", rejected.errorCode)
        assertEquals(AgentToolSideEffect.NONE, rejected.sideEffect)
        assertEquals(0, staleExecutions)
        assertEquals(2, guardChecks)
    }

    @Test
    fun pluginReplacedDuringApprovalDoesNotExecuteUnapprovedNewHandler() = runBlocking {
        var oldExecuted = 0
        var newExecuted = 0
        val registry = ToolRegistry().apply {
            register(tool(
                name = "write",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.ALWAYS,
            ) {
                oldExecuted++
                ToolResult("old handler")
            })
        }
        val result = coordinator(registry).executeScoped(
            original = LocalToolCall("stale-reg", "write", JsonObject(emptyMap()), "{}"),
            sessionId = "work",
            allowMutation = true,
            planModeEnabled = false,
            approval = { _, _, _ ->
                registry.register(tool(
                    name = "write",
                    access = ToolAccess.WORKSPACE_WRITE,
                    approval = ToolApprovalPolicy.ALWAYS,
                ) {
                    newExecuted++
                    ToolResult("new handler")
                }, replace = true)
                true
            },
        )
        assertEquals("TOOL_REGISTRATION_CHANGED", result.errorCode)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertFalse(result.retryable)
        assertEquals(0, oldExecuted)
        assertEquals(0, newExecuted)
    }

    @Test
    fun pluginUninstalledWhileWaitingForWorkspaceResourceDoesNotRunAfterLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondWaiting = CompletableDeferred<Unit>()
        var oldExecuted = 0
        val registry = ToolRegistry().apply {
            register(tool(
                name = "write",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.NEVER,
            ) {
                entered.complete(Unit)
                release.await()
                ToolResult("first completed")
            })
            register(tool(
                name = "edit",
                access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.NEVER,
            ) {
                oldExecuted++
                ToolResult("stale handler")
            })
        }
        val coordinator = coordinator(registry)
        val first = async {
            coordinator.executeScoped(
                original = LocalToolCall("first", "write", JsonObject(emptyMap()), "{}"),
                sessionId = "work", allowMutation = true, planModeEnabled = false,
                approval = { _, _, _ -> true },
            )
        }
        entered.await()
        val second = async {
            coordinator.executeScoped(
                original = LocalToolCall("second", "edit", JsonObject(emptyMap()), "{}"),
                sessionId = "work", allowMutation = true, planModeEnabled = false,
                approval = { _, _, _ -> true },
                beforeExecution = {
                    secondWaiting.complete(Unit)
                    true
                },
            )
        }
        secondWaiting.await()
        registry.unregister("edit")
        release.complete(Unit)
        assertFalse(first.await().isError)
        val rejected = second.await()
        assertEquals("TOOL_REGISTRATION_CHANGED", rejected.errorCode)
        assertEquals(AgentToolSideEffect.NONE, rejected.sideEffect)
        assertEquals(0, oldExecuted)
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

    @Test
    fun existingFileDeliveryCanBeObservedUnderReadLeaseWithoutInventingWrites() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { ToolResult("existing document") }
                .copy(metadata = ToolMetadata("files", workspaceAccess = com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess.READ)))
            register(tool("remote_lookup", ToolAccess.NETWORK, ToolApprovalPolicy.NEVER) { ToolResult("remote data") })
        }
        val coordinator = coordinator(registry)
        var observations = 0
        val read = coordinator.executeScoped(LocalToolCall("read-existing", "read", JsonObject(emptyMap()), "{}"),
            "s1", false, false, approval = { _, _, _ -> true }, observeFileVersions = {
                observations++
                mapOf("README.md" to "current-version")
            })
        assertFalse(read.isError)
        assertEquals(mapOf("README.md" to "current-version"), read.fileVersions)
        assertEquals(AgentToolSideEffect.NONE, read.sideEffect)
        assertEquals(1, observations)
        val remote = coordinator.executeScoped(LocalToolCall("remote", "remote_lookup", JsonObject(emptyMap()), "{}"),
            "s1", true, false, approval = { _, _, _ -> true }, observeFileVersions = {
                observations++
                mapOf("README.md" to "unsafe-outside-lease")
            })
        assertFalse(remote.isError)
        assertTrue(remote.fileVersions.isEmpty())
        assertEquals(1, observations)
    }

    @Test
    fun modelSelectionDiagnosesAbsentOrPolicyExcludedToolsWithoutExposingSchemas() {
        val registry = ToolRegistry().apply {
            register(tool(name = "web_search", access = ToolAccess.NETWORK,
                approval = ToolApprovalPolicy.NEVER, exposure = ToolExposure.OPTIONAL,
                family = "网络", keywords = setOf("联网")) { ToolResult("ok") })
            register(tool(name = "workspace_write", access = ToolAccess.WORKSPACE_WRITE,
                approval = ToolApprovalPolicy.MUTATION, exposure = ToolExposure.OPTIONAL,
                family = "文件", keywords = setOf("写入")) { ToolResult("ok") })
        }
        val execution = coordinator(registry, networkSearchEnabled = { false })
        val projection = LocalToolSchemaProjection(registry, execution)
        val selection = projection.modelSelection(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = true,
            history = emptyList(),
            enabledOptional = linkedSetOf("web_search", "workspace_write", "uninstalled_extension"),
        )
        assertFalse("web_search" in projection.names(selection.schemas))
        assertFalse("workspace_write" in projection.names(selection.schemas))
        assertFalse("uninstalled_extension" in projection.names(selection.schemas))
        assertTrue(selection.omittedReasons["web_search"].orEmpty().contains("关闭"))
        assertTrue(selection.omittedReasons["workspace_write"].orEmpty().contains("规划模式"))
        assertTrue(selection.omittedReasons["uninstalled_extension"].orEmpty().contains("未注册"))

        // The public model-facing API must not drift from the diagnostic selection.
        assertEquals(selection.schemas, projection.modelSchemas(
            policy = localAgentRunPolicy(LocalUsageMode.WORK),
            modelState = com.labteto.dshmobile.local.model.LocalModelState(),
            planModeEnabled = true,
            history = emptyList(),
            enabledOptional = linkedSetOf("web_search", "workspace_write", "uninstalled_extension"),
        ))
    }

    private fun coordinator(
        registry: ToolRegistry,
        planMode: Boolean = false,
        recordExecutionStarted: suspend (
            String,
            LocalToolCall,
            LocalToolExecutionIdentity,
        ) -> Unit = { _, _, _ -> },
        recordAuthorizationDecision: suspend (
            String, LocalToolCall, LocalToolExecutionIdentity, Boolean,
        ) -> Unit = { _, _, _, _ -> },
        executionIdFactory: () -> String = { "exec-test" },
        networkSearchEnabled: () -> Boolean = { true },
        pinnedConnectorIds: () -> List<String> = { emptyList() },
        pinnedSkillIds: () -> List<String> = { emptyList() },
        workspaceGate: com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate =
            com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate(),
    ) = LocalToolExecutionCoordinator(
        registry = registry,
        currentSessionId = { "s1" },
        planMode = { planMode },
        enabledOptionalTools = linkedSetOf(),
        requestApproval = { _, _, _ -> true },
        recordExecutionStarted = recordExecutionStarted,
        recordAuthorizationDecision = recordAuthorizationDecision,
        executionIdFactory = executionIdFactory,
        networkSearchEnabled = networkSearchEnabled,
        pinnedConnectorIds = pinnedConnectorIds,
        pinnedSkillIds = pinnedSkillIds,
        workspaceReadWriteGate = workspaceGate,
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
