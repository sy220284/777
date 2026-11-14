package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.registry.RegistryEntries
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ToolAccess {
    READ_ONLY,
    WORKSPACE_WRITE,
    SESSION_WRITE,
    PROCESS,
    AGENT_CONTROL,
    NETWORK,
    DEVICE,
    PRIVILEGED,
}

enum class ToolApprovalPolicy {
    NEVER,
    MUTATION,
    ALWAYS,
}

data class ToolContext(
    val sessionId: String? = null,
    val allowMutation: Boolean = true,
    val attributes: Map<String, Any?> = emptyMap(),
    val approval: (suspend (HarnessTool) -> Boolean)? = null,
    /** Trusted Hook can request more restrictive approval without authorizing execution. */
    val approvalWithReason: (suspend (HarnessTool, String) -> Boolean)? = null,
    val onExecutionStarted: (suspend (HarnessTool) -> Unit)? = null,
    /** Optional exact tool identity captured with the model-visible permission/tool surface. */
    val expectedRegistration: HarnessTool? = null,
    /** Execution receipt, persisted before returning to a possibly cancelled caller. */
    val onExecutionFinished: (suspend (HarnessTool, ToolResult) -> Unit)? = null,
    /**
     * Trusted orchestration dispatch into the application's normal tool coordinator.
     * The parent callbacks/approval capture its own tool identity and must not be reused
     * for child invocations. Each child gets a fresh identity, authorization and receipt.
     * Parameters: tool name, resolved arguments, raw arguments, 1-based step number.
     */
    val dispatchNestedTool: (suspend (String, JsonObject, String, Int) -> Pair<ToolResult, Boolean>)? = null,
)

@kotlinx.serialization.Serializable
enum class ToolResultRetention {
    DURABLE,
    EPHEMERAL,
}

@kotlinx.serialization.Serializable
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val recoveryHint: String? = null,
    val retention: ToolResultRetention = ToolResultRetention.DURABLE,
    /** Captured by the process executor, never parsed from stdout. */
    val processExitCode: Int? = null,
    val fileVersions: Map<String, String> = emptyMap(),
    val richContent: com.labteto.dshmobile.harness.tools.ToolRichContent? = null,
)

data class ToolPostHookFailure(
    val hookId: String,
    val failureType: String,
)

data class ToolInvocationResult internal constructor(
    val result: ToolResult,
    /** False only when ToolRegistry proves the executor was never entered. */
    val executionStarted: Boolean,
    /** Post-use diagnostics never overwrite a completed tool's actual result. */
    val postHookFailures: List<ToolPostHookFailure> = emptyList(),
)

fun interface HarnessToolExecutor {
    suspend fun execute(context: ToolContext, input: JsonObject, rawArguments: String): ToolResult
}

data class HarnessTool(
    val name: String,
    val schema: JsonObject,
    val access: ToolAccess,
    val approvalPolicy: ToolApprovalPolicy,
    val exposure: ToolExposure,
    val metadata: ToolMetadata,
    val timeoutMillis: Long? = null,
    val executor: HarnessToolExecutor,
)

class ToolRegistry private constructor(
    private val tools: RegistryEntries<HarnessTool>,
    private val admission: ToolLifecycleAdmission,
    private val hooks: ToolHookRegistry,
) {
    constructor() : this(RegistryEntries(), ToolLifecycleAdmission(), ToolHookRegistry())
    fun register(tool: HarnessTool, replace: Boolean = false) {
        validateToolRegistration(tool)
        val normalized = tool.withContractDescription()
        tools.register(normalized.name, normalized, replace)
    }
    fun unregister(name: String): HarnessTool? = tools.remove(name)
    fun get(name: String): HarnessTool? = tools.get(name)
    fun names(): List<String> = tools.snapshot().keys.toList()

    /**
     * Permit a read-only Code Mode plan to run in plan/inspect scopes. The decision is based on
     * the live registry contracts of *every* child, never on a caller-supplied "readOnly" flag.
     * The actual children still go through their ordinary schema, Hook and approval checks.
     */
    fun isReadOnlyCodeModePlan(plan: JsonObject): Boolean {
        val steps = plan["steps"] as? JsonArray ?: return false
        if (steps.isEmpty()) return false
        val registered = tools.snapshot()
        return steps.all { element ->
            val entry = element as? JsonObject ?: return@all false
            val name = (entry["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@all false
            val handler = registered[name] ?: return@all false
            name != "code_mode_run" &&
                handler.exposure == ToolExposure.CORE &&
                handler.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        }
    }

    fun registerHook(subscription: ToolHookSubscription) = hooks.register(subscription)
    fun unregisterHook(id: String): Boolean = hooks.unregister(id)
    fun hookIds(): List<String> = hooks.ids()
    /** Capture once before sending schemas, using one coherent registry view. */
    fun captureBindings(schemas: JsonArray): ToolBindingSnapshot {
        val registered = tools.snapshot()
        val names = schemas.mapNotNull { schema ->
            val function = (schema as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.content
        }.toSet()
        val codeModeChildren = if ("code_mode_run" in names && registered["code_mode_run"] != null) {
            registered.filter { (name, tool) ->
                name != "code_mode_run" && tool.exposure == ToolExposure.CORE
            }
        } else emptyMap()
        return ToolBindingSnapshot(registered.filterKeys { it in names }, codeModeChildren)
    }
    internal fun schemas(): JsonArray = JsonArray(
        tools.snapshot().values
            .filter { it.exposure == ToolExposure.CORE }
            .map(HarnessTool::schema),
    )
    internal fun snapshot(): Map<String, HarnessTool> = tools.snapshot()
    internal fun restore(snapshot: Map<String, HarnessTool>) = tools.restore(snapshot)
    internal fun snapshotHookSubscriptions(): List<ToolHookSubscription> = hooks.subscriptions()
    internal fun restoreHookSubscriptions(snapshot: List<ToolHookSubscription>) = hooks.restore(snapshot)
    internal fun fork(): ToolRegistry = ToolRegistry(tools.fork(), admission, hooks.fork())
    internal fun publishTo(destination: ToolRegistry) {
        tools.publishTo(destination.tools)
        hooks.publishTo(destination.hooks)
    }
    internal fun markLifecycleSafe(safe: Boolean) = admission.markSafe(safe)
    internal suspend fun <T> lifecycleTransition(block: suspend () -> T): T = admission.transition(block)

    suspend fun execute(
        name: String,
        input: JsonObject,
        rawArguments: String = input.toString(),
        context: ToolContext = ToolContext(),
    ): ToolResult = executeTracked(name, input, rawArguments, context).result

    /**
     * Same execution contract as [execute], with registry-owned provenance indicating whether the
     * tool executor was actually entered. Callers may use this to guard retry/side-effect recovery;
     * providers cannot forge the provenance because [ToolInvocationResult] is registry-constructed.
     */
    suspend fun executeTracked(
        name: String,
        input: JsonObject,
        rawArguments: String = input.toString(),
        context: ToolContext = ToolContext(),
    ): ToolInvocationResult {
        if (!admission.beginCall()) return ToolInvocationResult(
            result = ToolResult(
                content = "插件生命周期切换中或资源状态异常，请稍后重试或停用异常插件：$name",
                isError = true,
                errorCode = "TOOL_LIFECYCLE_UNAVAILABLE",
                retryable = true,
                recoveryHint = "等待插件生命周期切换完成后重试一次；持续失败时停用异常插件。",
            ),
            executionStarted = false,
        )
        return try {
            executeAdmittedTracked(name, input, rawArguments, context)
        } finally {
            admission.endCall()
        }
    }

    private suspend fun executeAdmittedTracked(
        name: String,
        input: JsonObject,
        rawArguments: String,
        context: ToolContext,
    ): ToolInvocationResult {
        val tool = tools.get(name) ?: return ToolInvocationResult(
            result = ToolResult(
                content = "未知工具：$name",
                isError = true,
                errorCode = "UNKNOWN_TOOL",
                recoveryHint = "检查工具名称或重新发现当前可用能力。",
            ),
            executionStarted = false,
        )
        if (context.expectedRegistration != null && context.expectedRegistration !== tool) {
            return registrationChanged(name)
        }
        val sampled = currentCoroutineContext()[ToolBindingSnapshot]
        if (sampled != null && sampled.registration(name) !== tool) {
            val child = currentCoroutineContext()[CodeModeStepScope]
            if (child == null || child.snapshot !== sampled ||
                child.registration !== tool || sampled.codeModeChild(name) !== tool) {
                return registrationChanged(name)
            }
        }
        validateToolInput(tool, input)?.let { problem ->
            return ToolInvocationResult(
                result = ToolResult(
                    content = "工具参数无效：$name：$problem",
                    isError = true,
                    errorCode = "INVALID_TOOL_ARGUMENTS",
                    recoveryHint = "按工具 schema 修正参数后再调用。",
                ),
                executionStarted = false,
            )
        }
        val readOnlyCodeMode = name == "code_mode_run" && isReadOnlyCodeModePlan(input)
        if (!context.allowMutation &&
            tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK) &&
            !readOnlyCodeMode
        ) {
            return ToolInvocationResult(
                result = ToolResult(
                    content = "当前作用域禁止执行会改变状态的工具：$name",
                    isError = true,
                    errorCode = "MUTATION_SCOPE_BLOCKED",
                    recoveryHint = "改用只读能力，或回到允许修改的父任务执行。",
                ),
                executionStarted = false,
            )
        }
        // Hooks run after contract validation and mutation scope admission; they cannot grant
        // permissions or replace an approval. Every denial occurs before any executor entry.
        val hookIdentity = ToolHookInvocationIdentity(
            callId = context.attributes["call_id"] as? String,
            executionId = context.attributes["execution_id"] as? String,
            runId = context.attributes["run_id"] as? String,
        )
        val hookSnapshot = hooks.snapshot(name)
        var hookApprovalReason: String? = null
        for (subscription in hookSnapshot.subscriptions) {
            val decision = try {
                withTimeoutOrNull(5_000L) {
                    subscription.hook.beforeUse(ToolPreUseRequest(context.sessionId, tool, input, hookIdentity))
                } ?: ToolPreUseDecision.Deny("Hook 超时：" + subscription.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                return ToolInvocationResult(
                    result = ToolResult(
                        content = "工具前置 Hook 执行失败：" + subscription.id,
                        isError = true,
                        errorCode = "TOOL_PRE_HOOK_FAILED",
                        recoveryHint = "检查此 Hook 的配置与运行日志后再尝试。",
                    ),
                    executionStarted = false,
                )
            }
            if (decision is ToolPreUseDecision.Ask && hookApprovalReason == null) {
                hookApprovalReason = decision.reason
            }
            if (decision is ToolPreUseDecision.Deny) {
                return ToolInvocationResult(
                    result = ToolResult(
                        content = "工具被安全策略阻止：" + decision.reason,
                        isError = true,
                        errorCode = "TOOL_HOOK_BLOCKED",
                        recoveryHint = "根据 Hook 拒绝原因调整本次操作或取得授权。",
                    ),
                    executionStarted = false,
                )
            }
        }
        val needsApproval = hookApprovalReason != null || when (tool.approvalPolicy) {
            ToolApprovalPolicy.NEVER -> false
            ToolApprovalPolicy.ALWAYS -> true
            ToolApprovalPolicy.MUTATION -> tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        }
        if (needsApproval) {
            if (context.approval == null && context.approvalWithReason == null) {
                return ToolInvocationResult(
                    result = ToolResult(
                        content = "工具需要人工审批：$name",
                        isError = true,
                        errorCode = "APPROVAL_REQUIRED",
                        recoveryHint = "等待用户审批后再执行。",
                    ),
                    executionStarted = false,
                )
            }
            val granted = context.approvalWithReason?.invoke(tool, hookApprovalReason.orEmpty())
                ?: requireNotNull(context.approval).invoke(tool)
            if (!granted) return ToolInvocationResult(
                result = ToolResult(
                    content = "用户拒绝执行工具：$name",
                    isError = true,
                    errorCode = "APPROVAL_DENIED",
                    recoveryHint = "不要重复调用；改用已授权能力或等待用户调整权限。",
                ),
                executionStarted = false,
            )
        }

        // A plugin may be replaced or uninstalled while the user is reading its approval prompt.
        // Never execute a handler other than the one whose contract was admitted.
        if (tools.get(name) !== tool) return registrationChanged(name)
        if (!hooks.isCurrent(hookSnapshot)) {
            return ToolInvocationResult(
                result = ToolResult(
                    content = "工具执行规则在审批期间已更新，本次调用未执行。",
                    isError = true,
                    errorCode = "TOOL_HOOKS_CHANGED",
                    recoveryHint = "重新检查当前安全策略与工具权限后再调用。",
                ),
                executionStarted = false,
            )
        }
        currentCoroutineContext().ensureActive()
        context.onExecutionStarted?.invoke(tool)
        currentCoroutineContext().ensureActive()
        // Approval/resource admission stays cancellable. Only an explicitly audited handler
        // owns a finish-on-cancellation region, including its terminal receipt. Other tools
        // retain cooperative cancellation and unknown-effect recovery.
        return if (tool.metadata.finishesOnCancellation) {
            withContext(NonCancellable) { executeHandler(tool, input, rawArguments, context, hookSnapshot, hookIdentity) }
        } else executeHandler(tool, input, rawArguments, context, hookSnapshot, hookIdentity)
    }

    private suspend fun executeHandler(
        tool: HarnessTool,
        input: JsonObject,
        rawArguments: String,
        context: ToolContext,
        hookSnapshot: ToolHookSnapshot,
        hookIdentity: ToolHookInvocationIdentity,
    ): ToolInvocationResult {
        val environment = currentCoroutineContext()[ToolExecutionEnvironmentContext]
        suspend fun executeActual(): ToolResult =
            if (tool.metadata.environmentBound && environment != null) {
                environment.execute(tool, context, input, rawArguments)
            } else tool.executor.execute(context, input, rawArguments)

        val postHookFailures = mutableListOf<ToolPostHookFailure>()
        suspend fun deliverReceiptAndHooks(result: ToolResult) {
            // The executor's outcome is durable before best-effort post-use notifications.
            // Each Hook failure is independent: do not misreport an already completed mutation.
            context.onExecutionFinished?.invoke(tool, result)
            for (subscription in hookSnapshot.subscriptions) {
                try {
                    val completed = withTimeoutOrNull(5_000L) {
                        subscription.hook.afterUse(ToolPostUseEvent(context.sessionId, tool, result, hookIdentity))
                        true
                    } ?: false
                    if (!completed) {
                        postHookFailures += ToolPostHookFailure(subscription.id, "timeout")
                    }
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    postHookFailures += ToolPostHookFailure(subscription.id, "cancelled")
                } catch (failure: Exception) {
                    // Avoid exposing plugin exception messages, which may include private data.
                    postHookFailures += ToolPostHookFailure(subscription.id, failure.javaClass.simpleName)
                }
            }
        }
        val timeoutMillis = tool.timeoutMillis
        if (timeoutMillis == null) {
            val result = executeActual()
            deliverReceiptAndHooks(result)
            return ToolInvocationResult(
                result = result,
                executionStarted = true,
                postHookFailures = postHookFailures.toList(),
            )
        }
        require(timeoutMillis > 0L) { "工具超时必须大于 0：${tool.name}" }
        val result = withTimeoutOrNull(timeoutMillis) {
            executeActual()
        }
        val settled = result ?: ToolResult(
                content = "工具执行超时：${tool.name}（${timeoutMillis} ms）",
                isError = true,
                errorCode = "TOOL_TIMEOUT",
                retryable = tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK),
                recoveryHint = if (tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
                    "只读工具超时，可缩小范围后重试一次。"
                } else {
                    "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
                },
            )
        deliverReceiptAndHooks(settled)
        return ToolInvocationResult(
            result = settled,
            executionStarted = true,
            postHookFailures = postHookFailures.toList(),
        )
    }

    private fun registrationChanged(name: String): ToolInvocationResult = ToolInvocationResult(
        result = ToolResult(
            content = "工具 $name 已在本轮接纳或审批期间更新/卸载，旧操作没有执行。",
            isError = true,
            errorCode = "TOOL_REGISTRATION_CHANGED",
            retryable = false,
            recoveryHint = "刷新工具列表与权限后，按当前任务重新核实并调用；不要沿用旧的审批结果。",
        ),
        executionStarted = false,
    )

    private fun validateToolRegistration(tool: HarnessTool) {
        require(TOOL_NAME.matches(tool.name)) {
            "工具名称非法：${tool.name}"
        }
        require(tool.timeoutMillis == null || tool.timeoutMillis > 0L) {
            "工具超时必须大于 0：${tool.name}"
        }
        if (tool.exposure == ToolExposure.OPTIONAL) {
            require(tool.metadata.discoveryKeywords.isNotEmpty()) {
                "可选工具必须声明发现关键词：${tool.name}"
            }
        }

        val rootType = (tool.schema["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        require(rootType == "function") {
            "工具 schema 根类型必须是 function：${tool.name}"
        }
        val function = tool.schema["function"] as? JsonObject
            ?: error("工具 schema 缺少 function：${tool.name}")
        val schemaName = (function["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        require(schemaName == tool.name) {
            "工具名称与 schema 不一致：registry=${tool.name}, schema=${schemaName.orEmpty()}"
        }
        val description = (function["description"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        require(!description.isNullOrEmpty()) {
            "工具 description 不能为空：${tool.name}"
        }
        val parameters = function["parameters"] as? JsonObject
            ?: error("工具 parameters 必须是对象 schema：${tool.name}")
        val parameterType = (parameters["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        require(parameterType == "object") {
            "工具 parameters 根类型必须是 object：${tool.name}"
        }
        JsonSchemaValidator.validateSchema(
            schema = parameters,
            requireObjectRoot = true,
        )?.let { problem ->
            throw IllegalArgumentException(
                "工具 parameters schema 非法：${tool.name}：$problem"
            )
        }
    }

    private fun validateToolInput(tool: HarnessTool, input: JsonObject): String? {
        val function = tool.schema["function"] as? JsonObject ?: return null
        val parameters = function["parameters"] as? JsonObject ?: return null
        return JsonSchemaValidator.validateTrusted(input, parameters)
    }



    private companion object {
        val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
