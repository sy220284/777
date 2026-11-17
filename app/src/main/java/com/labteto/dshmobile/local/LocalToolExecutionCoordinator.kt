package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import com.labteto.dshmobile.harness.session.SessionExecutionContext
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolBindingSnapshot
import com.labteto.dshmobile.harness.tools.DirectToolStepScope
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolInvocationContext
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.localToolFailure
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.DIRECT_TOOL_CALL
import com.labteto.dshmobile.local.tools.LocalSubagentExecutionScope
import com.labteto.dshmobile.local.tools.directToolTarget
import com.labteto.dshmobile.local.tools.withSchemaTypedNumbers
import com.labteto.dshmobile.local.tools.LocalWorkspaceReadWriteGate
import com.labteto.dshmobile.local.work.LocalWorkGoalAcceptanceRejectedException
import com.labteto.dshmobile.observability.AppLog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Owns the model-visible tool surface and the common permission/error boundary for foreground runs.
 *
 * Built-in implementations stay registered in [ToolRegistry]; this coordinator resolves
 * concrete execution, permissions, approval and audit without per-turn tool activation.
 */
internal class LocalToolExecutionCoordinator(
    private val registry: ToolRegistry,
    private val currentSessionId: () -> String,
    private val planMode: () -> Boolean,
    private val requestApproval: suspend (
        call: LocalToolCall,
        tool: HarnessTool,
        summary: String,
    ) -> Boolean,
    private val recordExecutionStarted: suspend (
        String,
        LocalToolCall,
        LocalToolExecutionIdentity,
    ) -> Unit = { _, _, _ -> },
    /** Durable per-call decision, scoped to the owning session; never a reusable grant. */
    private val recordAuthorizationDecision: suspend (
        String, LocalToolCall, LocalToolExecutionIdentity, Boolean,
    ) -> Unit = { _, _, _, _ -> },
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val networkSearchEnabled: () -> Boolean = { true },
    private val workspaceReadWriteGate: LocalWorkspaceReadWriteGate = LocalWorkspaceReadWriteGate(),
    /** Real handler receipts survive cancellation; they never authorize a continuation. */
    private val recordCancelledExecutionFinished: suspend (
        String, LocalToolCall, LocalToolExecutionIdentity,
        com.labteto.dshmobile.harness.tools.ToolResult,
    ) -> Unit = { _, _, _, _ -> },
) {
    // 前台 Lead 与所有后台成员共用此闸门，文件写入工具不得并行交错执行。

    /** Network preference is checked against the real child tool, never the gateway. */
    internal fun isNetworkSearchPermitted(name: String): Boolean =
        networkSearchEnabled() || LocalToolPolicy.canonical(name) !in NETWORK_SEARCH_TOOLS

    /** Runtime catalog is always live; there is no persisted or per-turn selection. */
    fun capabilitySummary(): String {
        val tools = registry.names().mapNotNull(registry::get)
            .filter { it.exposure != ToolExposure.INTERNAL &&
                it.name !in setOf("tool_call", "tool_search") }
        val available = tools.filter { isNetworkSearchPermitted(it.name) }
        val byFamily = available.groupingBy { it.metadata.family }.eachCount()
        return buildString {
            append("实时已注册工具：").append(available.size).append(" 个，")
            append("覆盖 ").append(byFamily.size).append(" 类能力。")
            if (byFamily.isNotEmpty()) append(" ").append(
                byFamily.entries.sortedBy { it.key }.joinToString("；") {
                    it.key + " " + it.value + " 个"
                }
            )
            if (available.size != tools.size) append("；网络搜索关闭：" + (tools.size - available.size) + " 个。")
            append(" 已知工具名通过 tool_call 直接执行；未知名称通过 tool_search 查询实时参数。")
        }
    }

    suspend fun execute(
        original: LocalToolCall,
        allowMutation: Boolean,
    ): AgentToolResult = executeScoped(
        original = original,
        sessionId = currentSessionId(),
        allowMutation = allowMutation,
        planModeEnabled = planMode(),
        approval = requestApproval,
    )

    suspend fun executeScoped(
        original: LocalToolCall,
        sessionId: String,
        allowMutation: Boolean,
        planModeEnabled: Boolean,
        approval: suspend (LocalToolCall, HarnessTool, String) -> Boolean,
        beforeExecution: suspend () -> Boolean = { true },
        observeFileVersions: (() -> Map<String, String>)? = null,
        executionAttributes: Map<String, String> = emptyMap(),
        onExecutionActive: (Boolean) -> Unit = {},
    ): AgentToolResult {
        val call = original.copy(name = LocalToolPolicy.canonical(original.name))
        // The single model tool resolves by name at execution time; no optional schema queue.
        // The real target still passes the original coordinator, approval and audit.
        if (call.name == DIRECT_TOOL_CALL) {
            val child = call.directToolTarget() ?: return localToolFailure(
                "INVALID_TOOL_ARGUMENTS",
                "tool_call 需要有效的 name 与 JSON 对象 arguments；不允许递归调用入口。",
                readOnly = false, executionStarted = false,
            ).copy(
                recoveryHint = "工具尚未执行。格式示例：tool_call({\"name\":\"bash\",\"arguments\":{\"command\":\"pwd\"}})。" +
                    "未知工具先用 tool_search 查验真实名称、参数字段和必填项，修正后再调用。",
            )
            val handler = registry.get(child.name)
                ?: return localToolFailure("UNKNOWN_TOOL", "工具未注册：" + child.name,
                    readOnly = false, executionStarted = false).copy(
                    recoveryHint = "先用 tool_search 查找实时已注册工具和参数；按返回的真实名称重新调用。本次没有进入目标工具执行器。",
                )
            if (handler.exposure == ToolExposure.INTERNAL) return localToolFailure(
                "TOOL_SCOPE_BLOCKED", "内部工具不可通过模型调用：" + child.name,
                readOnly = false, executionStarted = false,
            )
            val binding = currentCoroutineContext()[ToolBindingSnapshot]
            val delegated = child.withSchemaTypedNumbers(handler.schema).copy(id = call.id + "/direct")
            val invoke: suspend () -> AgentToolResult = {
                executeScoped(
                    original = delegated, sessionId = sessionId, allowMutation = allowMutation,
                    planModeEnabled = planModeEnabled, approval = approval,
                    beforeExecution = beforeExecution,
                    observeFileVersions = observeFileVersions,
                    executionAttributes = executionAttributes + mapOf(
                        "root_call_id" to (executionAttributes["root_call_id"] ?: call.id),
                        "direct_tool_name" to child.name,
                    ),
                    onExecutionActive = onExecutionActive,
                )
            }
            return if (binding != null) withContext(DirectToolStepScope(binding, handler)) {
                invoke()
            } else invoke()
        }
        if (!isNetworkSearchPermitted(call.name)) return AgentToolResult(
            content = "网络搜索已关闭，请先在能力中心开启网络搜索。",
            isError = true,
            errorCode = "NETWORK_SEARCH_DISABLED",
            recoveryHint = "前往能力中心开启网络搜索，或使用现有本地资料完成任务。",
        )
        val registered = registry.get(call.name)
            ?: return AgentToolResult(
                content = "未知工具：" + call.name,
                isError = true,
                errorCode = "UNKNOWN_TOOL",
                recoveryHint = "使用 tool_search 查询实时工具名称和参数。",
            )
        val childScope = currentCoroutineContext()[LocalSubagentExecutionScope]
        if (childScope != null && !childScope.permits(registered, call.arguments)) {
            return localToolFailure("SUBAGENT_TOOL_SCOPE_BLOCKED",
                "子智能体当前授权范围不包含：" + call.name,
                readOnly = false, executionStarted = false)
        }

        val inspectOnlyCodeMode = call.name == "code_mode_run" &&
            registry.isReadOnlyCodeModePlan(call.arguments)
        if (planModeEnabled && !LocalToolPolicy.allowedInPlan(call.name, registered.access, call.arguments) &&
            !inspectOnlyCodeMode
        ) {
            return AgentToolResult(
                content = "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。",
                isError = true,
                errorCode = "PLAN_MODE_BLOCKED",
                recoveryHint = "提交并批准计划后再执行修改类工具。",
            )
        }

        val readLike = inspectOnlyCodeMode ||
            LocalToolPolicy.isReadOnlyInvocation(call.name, registered.access, call.arguments)
        if (!allowMutation && !readLike) {
            return AgentToolResult(
                content = "当前子任务是只读作用域，不能执行会改变状态的工具：" + call.name,
                isError = true,
                errorCode = "MUTATION_SCOPE_BLOCKED",
                recoveryHint = "改用只读检查工具，或由父智能体在可写作用域执行该操作。",
            )
        }

        val environment = currentCoroutineContext()[com.labteto.dshmobile.harness.tools.ToolExecutionEnvironmentContext]
            ?.takeIf { registered.metadata.environmentBound }
        val executionOwner = currentCoroutineContext()[SessionExecutionContext]?.lease
        val runOwner = currentCoroutineContext()[AgentRunExecutionContext]
        val callerJob = currentCoroutineContext()[Job]
        var authorizationRecorded = false
        var approvalDenied = false
        var executionStarted = false
        val executionIdentity = LocalToolExecutionIdentity(
            executionId = executionIdFactory(),
            rootCallId = executionAttributes["root_call_id"] ?: call.id,
            parentExecutionId = executionAttributes["parent_execution_id"],
            attributes = buildMap {
                putAll(executionAttributes)
                environment?.let { put("execution_environment", it.environmentId) }
                currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]?.takeIf { environment == null }?.let {
                    put("workspace_id", it.workspaceId)
                    put("workspace_root", it.root.path)
                }
                runOwner?.let {
                    put("run_id", it.identity.runId)
                    it.parentRunId?.let { parent -> put("parent_run_id", parent) }
                }
                executionOwner?.identity?.let {
                    put("session_owner_incarnation", it.incarnation)
                    put("session_owner_epoch", it.epoch.toString())
                }
            },
            verifiedReadOnly = readLike,
        )
        var executionLease: LocalWorkspaceReadWriteGate.Lease? = null
        suspend fun requestApprovalForTool(
            tool: HarnessTool,
            hookReason: String?,
            concreteCall: LocalToolCall = call,
        ): Boolean {
            val summary = approvalSummary(concreteCall, tool) +
                hookReason?.takeIf(String::isNotBlank)?.let { "\n受信工具规则要求确认：" + it }.orEmpty()
            val granted = approval(concreteCall, tool, summary)
            // Child dispatch has an independent LocalToolCall and matching execution receipt.
            recordAuthorizationDecision(sessionId, concreteCall, executionIdentity, granted)
            authorizationRecorded = true
            if (!granted) approvalDenied = true
            return granted
        }
        // Code Mode's children are real tool calls. Dispatch them through this same coordinator
        // with new call IDs, instead of inheriting the parent's callbacks (whose approval,
        // registration check, workspace lease and durable receipt all belong to code_mode_run).
        val nestedDispatch: (suspend (String, JsonObject, String, Int) ->
            Pair<com.labteto.dshmobile.harness.tools.ToolResult, Boolean>)? =
            if (call.name == "code_mode_run") {
                { childName, childArguments, childRawArguments, stepOrdinal ->
                    val nestedCall = LocalToolCall(
                        id = "${call.id}/step-$stepOrdinal",
                        name = childName,
                        arguments = childArguments,
                        rawArguments = childRawArguments,
                    )
                    val childResult = executeScoped(
                        original = nestedCall,
                        sessionId = sessionId,
                        allowMutation = allowMutation,
                        planModeEnabled = planModeEnabled,
                        approval = approval,
                        beforeExecution = beforeExecution,
                        observeFileVersions = observeFileVersions,
                        executionAttributes = executionAttributes + mapOf(
                            "root_call_id" to executionIdentity.rootCallId,
                            "parent_execution_id" to executionIdentity.executionId,
                            "code_mode_parent_call_id" to call.id,
                        ),
                        // The parent remains active throughout the whole plan.
                        onExecutionActive = {},
                    )
                    com.labteto.dshmobile.harness.tools.ToolResult(
                        content = childResult.content,
                        isError = childResult.isError,
                        errorCode = childResult.errorCode,
                        retryable = childResult.retryable,
                        recoveryHint = childResult.recoveryHint,
                        retention = childResult.retention,
                        processExitCode = childResult.processExitCode,
                        fileVersions = childResult.fileVersions,
                        richContent = childResult.richContent,
                        structuredResult = childResult.structuredResult,
                    ) to (childResult.executionId != null)
                }
            } else null
        val invocation = try {
            executionOwner?.requireCurrent(sessionId)
            runOwner?.requireCurrent(sessionId)
            withContext(ToolInvocationContext(sessionId, call.id, executionIdentity.executionId)) {
                registry.executeTracked(
                    name = call.name,
                    input = call.arguments,
                    rawArguments = call.rawArguments,
                    context = ToolContext(
                        sessionId = sessionId,
                        allowMutation = allowMutation,
                        attributes = buildMap {
                            putAll(executionIdentity.attributes)
                            put("call_id", call.id)
                            put("execution_id", executionIdentity.executionId)
                            put("root_call_id", executionIdentity.rootCallId)
                            put("plan_mode", planModeEnabled)
                            executionIdentity.parentExecutionId?.let { put("parent_execution_id", it) }
                            currentCoroutineContext()[LocalModelRunContext]?.profile?.let { put("model_profile", it) }
                        },
                        expectedRegistration = registered,
                        dispatchNestedTool = nestedDispatch,
                        onExecutionFinished = { _, result ->
                            if (callerJob?.isCancelled == true) {
                                recordCancelledExecutionFinished(sessionId, call, executionIdentity, result)
                            }
                        },
                        approval = { tool -> requestApprovalForTool(tool, null) },
                        approvalWithReason = { tool, reason -> requestApprovalForTool(tool, reason) },
                        // Code Mode child calls enter a separate scoped coordinator execution.
                        // Preserve concrete arguments for any other nested orchestrator too.
                        approvalWithInvocation = { tool, arguments, attributes, reason ->
                            val specificCall = if (tool.name == registered.name) call else
                                call.copy(
                                    id = (attributes["call_id"] as? String).orEmpty()
                                        .ifBlank { call.id + "/nested/" + tool.name },
                                    name = tool.name,
                                    arguments = arguments,
                                    rawArguments = arguments.toString(),
                                )
                            requestApprovalForTool(tool, reason, specificCall)
                        },
                        onExecutionStarted = { admittedTool ->
                            // A new correction can arrive while awaiting approval or resource admission.
                            // Reject the stale call before entering its executor, with no side effect.
                            executionOwner?.requireCurrent(sessionId)
                            kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext]?.requireCurrent()
                            runOwner?.requireCurrent(sessionId)
                            if (!beforeExecution()) throw LocalToolSampleSuperseded()
                            // The registry calls this only after schema checks and user approval.
                            // Waiting for approval must never hold a filesystem read/write lease.
                            executionLease = if (environment == null) workspaceReadWriteGate.acquire(admittedTool) else null
                            // Admission can suspend behind another read/write operation. A correction
                            // committed during that wait must invalidate this still-unstarted call.
                            executionOwner?.requireCurrent(sessionId)
                            kotlinx.coroutines.currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext]?.requireCurrent()
                            runOwner?.requireCurrent(sessionId)
                            if (!beforeExecution()) throw LocalToolSampleSuperseded()
                            // Approval may outlive the exact plugin registration. Re-check after waiting
                            // for the shared read/write gate and before writing the started fact.
                            if (registry.get(call.name) !== admittedTool) {
                                throw LocalToolRegistrationChanged()
                            }
                            // NEVER/automatic policies still produce a decision for this exact
                            // execution. Registry admission is the authority, not UI selection.
                            if (!authorizationRecorded) {
                                recordAuthorizationDecision(sessionId, call, executionIdentity, true)
                                authorizationRecorded = true
                            }
                            recordExecutionStarted(sessionId, call, executionIdentity)
                            executionStarted = true
                            onExecutionActive(true)
                        },
                    ),
                )
            }.let { invocation ->
                // A post-use Hook can fail after the tool has already committed its side effect.
                // Record this separately; do not convert the real tool outcome into a failure.
                invocation.postHookFailures.forEach { failure ->
                    AppLog.warn("LocalToolExecution",
                        "operation=post_tool_hook_failed session_id=$sessionId call_id=${call.id} tool=${call.name} hook_id=${failure.hookId} cause=${failure.failureType}")
                }
                // Observe while the real workspace lease is still held. The caller owns
                // what to observe; this neutral coordinator never interprets goal semantics.
                if (invocation.executionStarted && executionLease != null && !invocation.result.isError) {
                    invocation.copy(result = invocation.result.copy(
                        fileVersions = invocation.result.fileVersions + observeFileVersions?.invoke().orEmpty(),
                    ))
                } else invocation
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            AppLog.info("LocalToolExecution", "operation=execute_tool_cancelled tool=${call.name} call_id=${call.id} session_id=$sessionId")
            return thrownFailure(call, registered, "TASK_CANCELLED", cancelled.message ?: "子任务自身被取消", executionStarted, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (changed: LocalToolRegistrationChanged) {
            return AgentToolResult(
                content = "工具已在执行接纳期间更新或卸载：${call.name}；旧审批与旧调用不再有效。",
                isError = true,
                errorCode = "TOOL_REGISTRATION_CHANGED",
                retryable = false,
                sideEffect = AgentToolSideEffect.NONE,
                recoveryHint = "重新发现当前工具并核对权限；此调用没有进入工具执行器。",
            )
        } catch (superseded: LocalToolSampleSuperseded) {
            return AgentToolResult(
                content = "用户已要求立即调整，本次旧工具调用尚未开始执行：" + call.name,
                isError = true,
                errorCode = "WORK_TOOL_SUPERSEDED",
                retryable = false,
                sideEffect = AgentToolSideEffect.NONE,
                recoveryHint = "读取最新已提交的用户要求，不要重复执行旧调用。",
            )
        } catch (rejected: LocalWorkGoalAcceptanceRejectedException) {
            // The Goal policy validated the files/tests before changing status.
            // Its rejection only appends an audit fact, never mutates a deliverable.
            // Treating it as POSSIBLE would permanently poison this Goal's ledger.
            return AgentToolResult(
                content = rejected.message ?: "目标尚未通过当前版本验收",
                isError = true,
                errorCode = "WORK_GOAL_ACCEPTANCE_REJECTED",
                retryable = false,
                sideEffect = AgentToolSideEffect.NONE,
                executionId = executionIdentity.executionId.takeIf { executionStarted },
                recoveryHint = "保留当前目标，完成缺少的验收并再次结算，无需新建目标。",
            )
        } catch (error: LocalWebException) {
            return thrownFailure(call, registered, error.code, error.message ?: "网页工具失败", executionStarted,
                cause = error, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (error: LocalModelException) {
            return thrownFailure(call, registered, error.code, error.message ?: "模型请求失败", executionStarted,
                cause = error, sessionId = sessionId, executionId = executionIdentity.executionId)
        } catch (error: Exception) {
            val code = when {
                error is java.io.FileNotFoundException ||
                    error is java.nio.file.NoSuchFileException -> "TOOL_NOT_FOUND"
                error is java.nio.file.AccessDeniedException || error is SecurityException ->
                    "TOOL_PERMISSION_DENIED"
                call.name.startsWith("lsp_") && error is IllegalStateException &&
                    error.message.orEmpty().contains("用户拒绝") -> "APPROVAL_DENIED"
                call.name.startsWith("lsp_") && error is IllegalStateException &&
                    error.message.orEmpty().contains("人工审批") -> "APPROVAL_REQUIRED"
                call.name.startsWith("lsp_") && error is IllegalArgumentException &&
                    error.message.orEmpty().contains("语言服务器") -> "TOOL_UNAVAILABLE"
                error is IllegalArgumentException -> "TOOL_INVALID_ARGUMENT"
                error is IllegalStateException && call.name.startsWith("lsp_") -> "TOOL_UNAVAILABLE"
                error is IllegalStateException && !executionStarted -> "TOOL_PRECONDITION_FAILED"
                error is java.io.IOException -> "TOOL_IO_ERROR"
                else -> "TOOL_ERROR"
            }
            return thrownFailure(
                call, registered, code, error.message ?: error::class.java.simpleName,
                executionStarted, error::class.java.simpleName, cause = error, sessionId = sessionId,
                executionId = executionIdentity.executionId,
            )
        } finally {
            try { executionLease?.close() }
            finally { if (executionStarted) onExecutionActive(false) }
        }
        executionStarted = executionStarted || invocation.executionStarted
        currentCoroutineContext().ensureActive()
        val result = invocation.result

        if (!result.isError) {
            return AgentToolResult(
                content = result.content,
                retention = result.retention,
                processExitCode = result.processExitCode,
                fileVersions = result.fileVersions,
                richContent = result.richContent,
                structuredResult = result.structuredResult,
                executionId = executionIdentity.executionId.takeIf { executionStarted },
                sideEffect = if (executionStarted && !readLike) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
            )
        }

        val providerCode = result.errorCode?.takeIf(String::isNotBlank)
        val timedOut = providerCode == "TOOL_TIMEOUT" ||
            (providerCode == null && result.content.startsWith("工具执行超时："))
        val errorCode = when {
            approvalDenied -> "APPROVAL_DENIED"
            providerCode != null -> providerCode
            timedOut -> "TOOL_TIMEOUT"
            else -> "TOOL_REPORTED_ERROR"
        }
        val mutationMayHaveSideEffect = executionStarted && !readLike
        val retryable = when {
            !executionStarted -> result.retryable
            readLike -> result.retryable || (providerCode == null && timedOut)
            else -> false
        }
        val recoveryHint = when {
            mutationMayHaveSideEffect -> "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
            // Registry-proven PRE-execution schema rejection can be corrected deterministically
            // using this exact registered tool. No guessed aliases, user approval or replay.
            !executionStarted && errorCode == "INVALID_TOOL_ARGUMENTS" ->
                "工具尚未进入执行器；" +
                    com.labteto.dshmobile.local.tools.toolArgumentContractSummary(registered.schema) +
                    "；仅使用本轮实际提供的工具名和参数字段，修正后重新提交。"
            else -> result.recoveryHint ?: when {
                approvalDenied -> "该工具没有获得批准；不要重复调用，改用已授权能力或等待用户调整权限。"
                timedOut && readLike -> "只读工具超时，可缩小范围后重试一次。"
                timedOut -> "工具执行超时；先检查当前状态，再决定是否重试。"
                else -> "根据工具返回内容检查前置条件；确认状态后再决定下一步。"
            }
        }
        AppLog.warn("LocalToolExecution", "failure=true operation=execute_tool session_id=$sessionId call_id=${call.id} tool=${call.name} code=$errorCode retryable=$retryable side_effect=$mutationMayHaveSideEffect")
        return AgentToolResult(
            content = result.content,
            isError = true,
            errorCode = errorCode,
            retryable = retryable,
            sideEffect = if (mutationMayHaveSideEffect) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
            recoveryHint = recoveryHint,
            retention = result.retention,
            processExitCode = result.processExitCode,
            fileVersions = result.fileVersions,
            richContent = result.richContent,
                structuredResult = result.structuredResult,
            executionId = executionIdentity.executionId.takeIf { executionStarted },
        )
    }

    private fun thrownFailure(
        call: LocalToolCall,
        tool: HarnessTool,
        code: String,
        message: String,
        executionStarted: Boolean,
        exceptionType: String? = null,
        cause: Throwable? = null,
        sessionId: String? = null,
        executionId: String? = null,
    ): AgentToolResult {
        val baseResult = localToolFailure(code, message,
            readOnly = LocalToolPolicy.isReadOnlyInvocation(call.name, tool.access, call.arguments),
            executionStarted = executionStarted)
        val result = if (code == "TOOL_INVALID_ARGUMENT") baseResult.copy(
            recoveryHint = baseResult.recoveryHint.orEmpty() + "\n" +
                com.labteto.dshmobile.local.tools.toolArgumentContractSummary(tool.schema),
        ) else baseResult
        if (cause != null) {
            AppLog.failure(
                "LocalToolExecution",
                "execute_tool tool=${call.name} code=$code started=$executionStarted retryable=${result.retryable}",
                cause,
                sessionId = sessionId,
                requestId = call.id,
            )
        } else {
            AppLog.warn("LocalToolExecution", "failure=true operation=execute_tool tool=${call.name} call_id=${call.id} code=$code started=$executionStarted retryable=${result.retryable} exception_type=${exceptionType ?: "unknown"}")
        }
        return result.copy(executionId = executionId.takeIf { executionStarted })
    }

    private fun approvalSummary(call: LocalToolCall, tool: HarnessTool): String = when (tool.name) {
        "write", "edit", "apply_patch", "download_file" ->
            tool.name + "：" + call.arguments.optionalStringForCoordinator("path").orEmpty()
        "bash", "pwsh", "shell", "run_shell", "process_exec",
        "terminal_open", "terminal_send", "terminal_write" ->
            "执行本机操作以完成当前任务"
        "lsp_start" -> "启用代码智能分析"
        else -> "执行 " + tool.name + "（权限级别：" + tool.access.name.lowercase() + "）"
    }

    private companion object {
        val NETWORK_SEARCH_TOOLS = setOf("web_search", "web_fetch")
        val MUTATING_ACCESSES = setOf(
            ToolAccess.WORKSPACE_WRITE,
            ToolAccess.SESSION_WRITE,
            ToolAccess.PROCESS,
            ToolAccess.AGENT_CONTROL,
            ToolAccess.DEVICE,
            ToolAccess.PRIVILEGED,
        )
    }
}

internal data class LocalToolExecutionIdentity(
    val executionId: String,
    val rootCallId: String,
    val parentExecutionId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val verifiedReadOnly: Boolean = false,
)

private fun JsonObject.optionalStringForCoordinator(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Work-owned revision guard rejected a stale call before it entered the registered executor. */
internal class LocalToolSampleSuperseded : IllegalStateException("Work model sample superseded by user")

/** Registry revision changed during admission/resource wait; handler was never entered. */
internal class LocalToolRegistrationChanged : IllegalStateException("Tool registration changed before execution")
