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
import com.labteto.dshmobile.harness.tools.CodeModePlugin
import com.labteto.dshmobile.harness.plugin.HarnessContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
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

    @Test fun failedGoalAcceptanceDoesNotPoisonSubsequentSettlement() = runBlocking {
        val registry = ToolRegistry()
        registry.register(tool("update_goal", ToolAccess.SESSION_WRITE,
            ToolApprovalPolicy.NEVER) {
            throw com.labteto.dshmobile.local.work.LocalWorkGoalAcceptanceRejectedException(
                "成果版本已变化，需要重新验收")
        })
        val run = coordinator(registry)
        val result = run.executeScoped(
            LocalToolCall("goal-rejected", "update_goal", buildJsonObject {}, "{}"),
            "s1", true, false,
            approval = { _, _, _ -> true },
        )
        assertTrue(result.isError)
        assertEquals("WORK_GOAL_ACCEPTANCE_REJECTED", result.errorCode)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertTrue(result.executionId != null)
        assertTrue(result.recoveryHint.orEmpty().contains("无需新建目标"))
    }


    @Test fun codeModeRunsRealChildStepsThroughFreshAuthorizationAndExecutionIdentities() = runBlocking {
        val registry = ToolRegistry()
        val invoked = mutableListOf<String>()
        registry.register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
            invoked += "read"
            ToolResult("read-result")
        })
        registry.register(tool("bash", ToolAccess.PROCESS, ToolApprovalPolicy.ALWAYS) {
            invoked += "bash"
            ToolResult("bash-result")
        })
        CodeModePlugin(registry).install(HarnessContext(registry))
        val started = mutableListOf<String>()
        val lineage = mutableListOf<Triple<String, String, String?>>()
        val authorized = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        val run = coordinator(
            registry,
            recordExecutionStarted = { _, call, identity ->
                started += call.id + ":" + call.name
                lineage += Triple(identity.rootCallId, identity.executionId, identity.parentExecutionId)
            },
            recordAuthorizationDecision = { _, call, _, _ -> authorized += call.id + ":" + call.name },
            executionIdFactory = object : () -> String {
                private var counter = 0
                override fun invoke(): String = "exec-" + (++counter)
            },
        )
        val plan = buildJsonObject {
            put("steps", buildJsonArray {
                listOf("read", "bash").forEach { name ->
                    add(buildJsonObject {
                        put("tool", name)
                        put("args", buildJsonObject {})
                    })
                }
            })
        }
        val selection = registry.captureBindings(JsonArray(listOf(registry.get("code_mode_run")!!.schema)))
        val result = withContext(selection) {
            run.executeScoped(
                LocalToolCall("script-1", "code_mode_run", plan, plan.toString()),
                "s1", true, false,
                approval = { call, tool, _ ->
                    assertEquals(call.name, tool.name)
                    approvals += call.id + ":" + tool.name
                    true
                },
            )
        }
        assertFalse(result.isError)
        assertTrue(result.content.contains("\"completed_steps\":2"))
        assertEquals(listOf("read", "bash"), invoked)
        assertEquals(listOf("script-1:code_mode_run", "script-1/step-1:read",
            "script-1/step-2:bash"), started)
        assertEquals(started, authorized)
        assertEquals(listOf("script-1", "script-1", "script-1"), lineage.map { it.first })
        assertEquals(null, lineage[0].third)
        assertEquals("exec-1", lineage[1].third)
        assertEquals("exec-1", lineage[2].third)
        assertEquals(listOf("script-1/step-2:bash"), approvals) // no redundant wrapper prompt
    }

    @Test fun planModeAdvertisesStableDirectCallGateway() {
        val registry = ToolRegistry()
        registerGateways(registry)
        registry.register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { ToolResult("preview") })
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        val selected = projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK))
        assertEquals(listOf("tool_search", "tool_call"), projection.names(selected))
        assertFalse("read" in projection.names(selected))
    }

    @Test fun planModeRunsReadOnlyCodeModeWithoutExtraPromptsAndRejectsMixedScript() = runBlocking {
        val registry = ToolRegistry()
        var reads = 0
        var writes = 0
        var prompts = 0
        registry.register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
            reads++
            ToolResult("inspection finished")
        })
        registry.register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
            writes++
            ToolResult("write committed")
        })
        CodeModePlugin(registry).install(HarnessContext(registry))
        val coordinator = coordinator(registry)
        fun plan(vararg tools: String) = buildJsonObject {
            put("steps", buildJsonArray {
                tools.forEach { name ->
                    add(buildJsonObject {
                        put("tool", name)
                        put("args", buildJsonObject {})
                    })
                }
            })
        }
        val visible = registry.captureBindings(JsonArray(listOf(registry.get("code_mode_run")!!.schema)))
        val allowed = plan("read", "read")
        val result = withContext(visible) {
            coordinator.executeScoped(
                LocalToolCall("plan-read", "code_mode_run", allowed, allowed.toString()),
                "s1", allowMutation = false, planModeEnabled = true,
                approval = { _, _, _ -> prompts++; true },
            )
        }
        assertFalse(result.isError)
        assertEquals(2, reads)
        assertEquals(0, writes)
        assertEquals(0, prompts)
        assertTrue(result.content.contains("\"completed_steps\":2"))

        val mixed = plan("read", "write")
        val blocked = withContext(visible) {
            coordinator.executeScoped(
                LocalToolCall("plan-mixed", "code_mode_run", mixed, mixed.toString()),
                "s1", allowMutation = false, planModeEnabled = true,
                approval = { _, _, _ -> prompts++; true },
            )
        }
        assertTrue(blocked.isError)
        assertEquals("PLAN_MODE_BLOCKED", blocked.errorCode)
        assertEquals(2, reads)
        assertEquals(0, writes)
        assertEquals(0, prompts)
    }

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
    fun codeModeNestedApprovalShowsActualToolArgumentsAndChildCallIdentity() = runBlocking {
        val registry = ToolRegistry().apply {
            register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) {
                ToolResult("file updated")
            }.copy(schema = functionToolSchema(
                "write", "写入工作区文件",
                com.labteto.dshmobile.harness.tools.simpleToolProperties(
                    mapOf("path" to "string")),
            )))
        }
        com.labteto.dshmobile.harness.tools.CodeModePlugin(registry)
            .install(com.labteto.dshmobile.harness.plugin.HarnessContext(registry))
        val steps = buildJsonObject {
            put("steps", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("tool", "write")
                    put("args", buildJsonObject { put("path", "notes.md") })
                })
            })
        }
        val prompts = mutableListOf<Pair<String, String>>()
        val durableCallIds = mutableListOf<String>()
        val result = coordinator(registry, recordAuthorizationDecision = { _, decisionCall, _, _ ->
            durableCallIds += decisionCall.id
        }).executeScoped(
            original = LocalToolCall("batch-call", "code_mode_run", steps, steps.toString()),
            sessionId = "work", allowMutation = true, planModeEnabled = false,
            approval = { nestedCall, _, summary ->
                prompts += nestedCall.id to (nestedCall.name + ": " + summary)
                true
            },
        )
        assertFalse(result.isError)
        assertEquals(1, prompts.size)
        assertEquals("batch-call/step-1", prompts.single().first)
        assertTrue(prompts.single().second.contains("write：notes.md"))
        // Parent and child both retain independently auditable authorization identities.
        assertEquals(listOf("batch-call", "batch-call/step-1"), durableCallIds)
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
        assertTrue(result.recoveryHint.orEmpty().contains("tool_search"))
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

    @Test fun subagentSurfaceStaysStableAcrossCapabilityModes() {
        val registry = ToolRegistry()
        registerGateways(registry)
        registry.register(tool("download_file", ToolAccess.WORKSPACE_WRITE,
            ToolApprovalPolicy.ALWAYS, ToolExposure.OPTIONAL) { ToolResult("ok") })
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        for (mutation in listOf(false, true)) for (virtual in listOf(false, true)) {
            assertEquals(listOf("tool_search", "tool_call"), projection.names(
                projection.subagentSchemas()))
        }
    }

    @Test fun readonlyAndPlanDoNotAdvertiseInnerWebFetchSchema() {
        val registry = ToolRegistry()
        registerGateways(registry)
        registry.register(webFetchTool { ToolResult("ok") })
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        val work = projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK))
        val child = projection.subagentSchemas()
        assertEquals(work, child)
        assertEquals(listOf("tool_search", "tool_call"), projection.names(work))
        assertFalse("web_fetch" in projection.names(work))
    }

    @Test fun planModeStillUsesConstantGatewayWithoutExposingWrites() {
        val registry = ToolRegistry()
        registerGateways(registry)
        registry.register(tool("write", ToolAccess.WORKSPACE_WRITE, ToolApprovalPolicy.ALWAYS) { ToolResult("ok") })
        val projection = LocalToolSchemaProjection(registry, coordinator(registry))
        val surface = projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK))
        assertEquals(listOf("tool_search", "tool_call"), projection.names(surface))
    }

    @Test fun unsupportedRemoteFileAndShellToolsCannotFallBackToPhoneEffects() = runBlocking {
        var localEffects = 0
        var remoteRequests = 0
        val registry = ToolRegistry()
        val names = listOf("list_files", "grep", "apply_patch", "bash", "job_kill", "present", "download_file", "json_query")
        for (name in names) registry.register(tool(name,
            com.labteto.dshmobile.local.tools.LocalToolPolicy.access(name), ToolApprovalPolicy.ALWAYS) {
            localEffects++; ToolResult("phone effect")
        }.copy(metadata = com.labteto.dshmobile.local.tools.LocalToolPolicy.metadata(name)))
        val selected = com.labteto.dshmobile.harness.tools.ToolExecutionEnvironmentContext { _, _, _, _ ->
            remoteRequests++
            ToolResult("远端环境暂不支持", isError = true, errorCode = "REMOTE_TOOL_UNSUPPORTED")
        }
        withContext(selected) {
            for (name in names) {
                val result = coordinator(registry).executeScoped(
                    LocalToolCall("remote-$name", name, JsonObject(emptyMap()), "{}"),
                    "s1", true, false, approval = { _, _, _ -> true })
                assertTrue(result.isError)
                assertEquals("REMOTE_TOOL_UNSUPPORTED", result.errorCode)
            }
        }
        assertEquals(names.size, remoteRequests)
        assertEquals(0, localEffects)
    }

    @Test fun remoteWorkspaceReceiptCannotBorrowLocalLeaseOrFileVersion() = runBlocking {
        val gate = com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate()
        val registry = ToolRegistry().apply {
            register(tool("read", ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) { error("host filesystem") }
                .copy(metadata = com.labteto.dshmobile.local.tools.LocalToolPolicy.metadata("read")))
        }
        var recordedEnvironment: String? = null
        val execution = coordinator(registry, workspaceGate = gate, recordExecutionStarted = { _, _, identity ->
            recordedEnvironment = identity.attributes["execution_environment"]
        })
        val selected = com.labteto.dshmobile.harness.tools.ToolExecutionEnvironmentContext(environmentId = "remote:test") { _, _, _, _ ->
            ToolResult("remote document", fileVersions = mapOf("README.md" to "remote-version"))
        }
        val result = kotlinx.coroutines.withTimeout(1000) {
            gate.withWrite {
                withContext(selected) {
                    execution.executeScoped(LocalToolCall("remote-read", "read", JsonObject(emptyMap()), "{}"),
                        "s1", false, false, approval = { _, _, _ -> true },
                        observeFileVersions = { error("remote effect must not observe the phone filesystem") })
                }
            }
        }
        assertFalse(result.isError)
        assertEquals(mapOf("README.md" to "remote-version"), result.fileVersions)
        assertEquals("remote:test", recordedEnvironment)
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

    @Test fun modelSelectionIsIndependentOfOptionalBudgetAndNetworkState() {
        val registry = ToolRegistry()
        registerGateways(registry)
        registry.register(tool("web_search", ToolAccess.NETWORK, ToolApprovalPolicy.NEVER,
            ToolExposure.OPTIONAL) { ToolResult("ok") })
        val projection = LocalToolSchemaProjection(registry, coordinator(registry,
            networkSearchEnabled = { false }))
        val actual = projection.modelSchemas(localAgentRunPolicy(LocalUsageMode.WORK))
        assertEquals(listOf("tool_search", "tool_call"), projection.names(actual))
    }

    private fun directArgs(name: String) = buildJsonObject {
        put("name", name)
        put("arguments", buildJsonObject {})
    }

    @Test fun optionalToolExecutesImmediatelyThroughGatewayWithoutSamplingItsSchema() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        var invocations = 0
        registry.register(tool("mcp_direct_lookup", ToolAccess.READ_ONLY,
            ToolApprovalPolicy.NEVER, ToolExposure.OPTIONAL) {
            invocations++
            ToolResult("direct-success")
        })
        val sampled = registry.captureBindings(JsonArray(listOf(
            requireNotNull(registry.get("tool_call")).schema,
            requireNotNull(registry.get("tool_search")).schema,
        )))
        val result = withContext(sampled) {
            val args = directArgs("mcp_direct_lookup")
            coordinator(registry).executeScoped(
                LocalToolCall("gateway-1", "tool_call", args, args.toString()),
                "s1", true, false, approval = { _, _, _ -> true },
            )
        }
        assertFalse(result.isError)
        assertEquals("direct-success", result.content)
        assertEquals(1, invocations)
    }

    @Test fun runtimeCanCallNewlyRegisteredToolEvenWhenModelSchemaIsUnchanged() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        val sampled = registry.captureBindings(JsonArray(listOf(
            requireNotNull(registry.get("tool_call")).schema,
        )))
        var invoked = 0
        registry.register(tool("later_mcp", ToolAccess.READ_ONLY,
            ToolApprovalPolicy.NEVER, ToolExposure.OPTIONAL) {
            invoked++
            ToolResult("dynamic")
        })
        val result = withContext(sampled) {
            val args = directArgs("later_mcp")
            coordinator(registry).executeScoped(
                LocalToolCall("dynamic", "tool_call", args, args.toString()),
                "s1", true, false, approval = { _, _, _ -> true },
            )
        }
        assertEquals("dynamic", result.content)
        assertFalse(result.isError)
        assertEquals(1, invoked)
    }

    @Test fun directGatewayPreservesRealChildMutationAndApprovalAdmission() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        var writes = 0
        var approvals = 0
        registry.register(tool("mutating_optional", ToolAccess.WORKSPACE_WRITE,
            ToolApprovalPolicy.ALWAYS, ToolExposure.OPTIONAL) {
            writes++
            ToolResult("wrote")
        })
        val args = directArgs("mutating_optional")
        val call = LocalToolCall("write-direct", "tool_call", args, args.toString())
        val execution = coordinator(registry)
        val planBlocked = execution.executeScoped(call, "s1", true, true,
            approval = { _, _, _ -> approvals++; true })
        assertEquals("PLAN_MODE_BLOCKED", planBlocked.errorCode)
        val readonlyBlocked = execution.executeScoped(call, "s1", false, false,
            approval = { _, _, _ -> approvals++; true })
        assertEquals("MUTATION_SCOPE_BLOCKED", readonlyBlocked.errorCode)
        val denied = execution.executeScoped(call, "s1", true, false,
            approval = { _, _, _ -> approvals++; false })
        assertEquals("APPROVAL_DENIED", denied.errorCode)
        assertEquals(0, writes)
        assertEquals(1, approvals)
        val success = execution.executeScoped(call, "s1", true, false,
            approval = { _, _, _ -> approvals++; true })
        assertFalse(success.isError)
        assertEquals(1, writes)
        assertEquals(2, approvals)
    }


    @Test fun disabledNetworkSearchBlocksDirectInvocationWithoutAPluginExecutor() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        var called = 0
        registry.register(tool("web_search", ToolAccess.NETWORK,
            ToolApprovalPolicy.NEVER, ToolExposure.OPTIONAL) {
            called++
            ToolResult("unexpected")
        })
        val args = directArgs("web_search")
        val result = coordinator(registry, networkSearchEnabled = { false }).executeScoped(
            LocalToolCall("network-direct", "tool_call", args, args.toString()),
            "s1", true, false, approval = { _, _, _ -> true })
        assertTrue(result.isError)
        assertEquals("NETWORK_SEARCH_DISABLED", result.errorCode)
        assertEquals(0, called)
    }

    @Test fun directCallChecksSubagentAllowlistAgainstTheActualChildTool() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        var calls = 0
        registry.register(tool("private_write", ToolAccess.WORKSPACE_WRITE,
            ToolApprovalPolicy.ALWAYS, ToolExposure.OPTIONAL) {
            calls++
            ToolResult("wrote")
        })
        val args = directArgs("private_write")
        val call = LocalToolCall("child-direct", "tool_call", args, args.toString())
        val scope = com.labteto.dshmobile.local.tools.LocalSubagentExecutionScope(
            com.labteto.dshmobile.local.agent.LocalSubagentCapabilities(
                allowMutation = true, toolAllowlist = setOf("read")),
            null)
        val result = withContext(scope) {
            coordinator(registry).executeScoped(call, "s1", true, false,
                approval = { _, _, _ -> true })
        }
        assertEquals("SUBAGENT_TOOL_SCOPE_BLOCKED", result.errorCode)
        assertEquals(0, calls)
    }

    @Test fun directCallPreservesTargetIdentityAndRejectsInternalRegistration() = runBlocking {
        val registry = ToolRegistry()
        registerGateways(registry)
        val started = mutableListOf<String>()
        var calls = 0
        registry.register(tool("internal_secret", ToolAccess.READ_ONLY,
            ToolApprovalPolicy.NEVER, ToolExposure.INTERNAL) {
            calls++
            ToolResult("secret")
        })
        registry.register(tool("mcp_info", ToolAccess.READ_ONLY,
            ToolApprovalPolicy.NEVER, ToolExposure.OPTIONAL) {
            calls++
            ToolResult("safe")
        })
        val executor = coordinator(registry, recordExecutionStarted = { _, call, _ ->
            started += call.name
        })
        fun invoke(name: String): LocalToolCall {
            val args = directArgs(name)
            return LocalToolCall("direct-" + name, "tool_call", args, args.toString())
        }
        val hidden = executor.executeScoped(invoke("internal_secret"),
            "s1", true, false, approval = { _, _, _ -> true })
        assertEquals("TOOL_SCOPE_BLOCKED", hidden.errorCode)
        val visible = executor.executeScoped(invoke("mcp_info"),
            "s1", true, false, approval = { _, _, _ -> true })
        assertFalse(visible.isError)
        assertEquals("safe", visible.content)
        assertEquals(listOf("mcp_info"), started)
        assertEquals(1, calls)
    }

    private fun registerGateways(registry: ToolRegistry) {
        for (name in listOf("tool_search", "tool_call")) {
            registry.register(tool(name, ToolAccess.READ_ONLY, ToolApprovalPolicy.NEVER) {
                ToolResult("gateway")
            })
        }
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
        workspaceGate: com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate =
            com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate(),
    ) = LocalToolExecutionCoordinator(
        registry = registry,
        currentSessionId = { "s1" },
        planMode = { planMode },
        requestApproval = { _, _, _ -> true },
        recordExecutionStarted = recordExecutionStarted,
        recordAuthorizationDecision = recordAuthorizationDecision,
        executionIdFactory = executionIdFactory,
        networkSearchEnabled = networkSearchEnabled,
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
        metadata = ToolMetadata(family = family, discoveryKeywords = if (exposure == ToolExposure.OPTIONAL && keywords.isEmpty()) setOf(name) else keywords),
        executor = HarnessToolExecutor { _, _, _ -> execute() },
    )
}
