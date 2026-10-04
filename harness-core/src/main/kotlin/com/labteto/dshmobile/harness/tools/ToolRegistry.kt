package com.labteto.dshmobile.harness.tools

import com.labteto.dshmobile.harness.registry.RegistryEntries
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    val onExecutionStarted: (suspend (HarnessTool) -> Unit)? = null,
)

enum class ToolResultRetention {
    DURABLE,
    EPHEMERAL,
}

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val recoveryHint: String? = null,
    val retention: ToolResultRetention = ToolResultRetention.DURABLE,
)

data class ToolInvocationResult internal constructor(
    val result: ToolResult,
    /** False only when ToolRegistry proves the executor was never entered. */
    val executionStarted: Boolean,
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
) {
    constructor() : this(RegistryEntries(), ToolLifecycleAdmission())
    fun register(tool: HarnessTool, replace: Boolean = false) {
        validateToolRegistration(tool)
        val normalized = tool.withContractDescription()
        tools.register(normalized.name, normalized, replace)
    }
    fun unregister(name: String): HarnessTool? = tools.remove(name)
    fun get(name: String): HarnessTool? = tools.get(name)
    fun names(): List<String> = tools.snapshot().keys.toList()
    internal fun schemas(): JsonArray = JsonArray(
        tools.snapshot().values
            .filter { it.exposure == ToolExposure.CORE }
            .map(HarnessTool::schema),
    )
    internal fun snapshot(): Map<String, HarnessTool> = tools.snapshot()
    internal fun restore(snapshot: Map<String, HarnessTool>) = tools.restore(snapshot)
    internal fun fork(): ToolRegistry = ToolRegistry(tools.fork(), admission)
    internal fun publishTo(destination: ToolRegistry) = tools.publishTo(destination.tools)
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
        if (!context.allowMutation && tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
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
        val needsApproval = when (tool.approvalPolicy) {
            ToolApprovalPolicy.NEVER -> false
            ToolApprovalPolicy.ALWAYS -> true
            ToolApprovalPolicy.MUTATION -> tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        }
        if (needsApproval) {
            val approval = context.approval
                ?: return ToolInvocationResult(
                    result = ToolResult(
                        content = "工具需要人工审批：$name",
                        isError = true,
                        errorCode = "APPROVAL_REQUIRED",
                        recoveryHint = "等待用户审批后再执行。",
                    ),
                    executionStarted = false,
                )
            if (!approval(tool)) return ToolInvocationResult(
                result = ToolResult(
                    content = "用户拒绝执行工具：$name",
                    isError = true,
                    errorCode = "APPROVAL_DENIED",
                    recoveryHint = "不要重复调用；改用已授权能力或等待用户调整权限。",
                ),
                executionStarted = false,
            )
        }

        context.onExecutionStarted?.invoke(tool)
        val timeoutMillis = tool.timeoutMillis
        if (timeoutMillis == null) {
            return ToolInvocationResult(
                result = tool.executor.execute(context, input, rawArguments),
                executionStarted = true,
            )
        }
        require(timeoutMillis > 0L) { "工具超时必须大于 0：$name" }
        val result = withTimeoutOrNull(timeoutMillis) {
            tool.executor.execute(context, input, rawArguments)
        }
        return ToolInvocationResult(
            result = result ?: ToolResult(
                content = "工具执行超时：$name（${timeoutMillis} ms）",
                isError = true,
                errorCode = "TOOL_TIMEOUT",
                retryable = tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK),
                recoveryHint = if (tool.access in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
                    "只读工具超时，可缩小范围后重试一次。"
                } else {
                    "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
                },
            ),
            executionStarted = true,
        )
    }

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
    }

    private fun validateToolInput(tool: HarnessTool, input: JsonObject): String? {
        val function = tool.schema["function"] as? JsonObject ?: return null
        val parameters = function["parameters"] as? JsonObject ?: return null
        return validateJsonValue(input, parameters, "$")
    }

    private fun validateJsonValue(
        value: JsonElement,
        schema: JsonObject,
        path: String,
    ): String? {
        val expectedType = schema["type"]?.let { (it as? JsonPrimitive)?.content }
        when (expectedType) {
            "object" -> {
                val obj = value as? JsonObject ?: return "$path 必须是对象"
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content }
                required.firstOrNull { it !in obj }?.let { return "$path 缺少必填字段 $it" }

                val additional = schema["additionalProperties"]
                if ((additional as? JsonPrimitive)?.booleanOrNull == false) {
                    obj.keys.firstOrNull { it !in properties }?.let {
                        return "$path 包含未声明字段 $it"
                    }
                }

                obj.forEach { (key, child) ->
                    val childSchema = properties[key] as? JsonObject
                    if (childSchema != null) {
                        validateJsonValue(child, childSchema, "$path.$key")?.let { return it }
                    } else if (additional is JsonObject) {
                        validateJsonValue(child, additional, "$path.$key")?.let { return it }
                    }
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: return "$path 必须是数组"
                schema["minItems"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (array.size < minimum) return "$path 至少需要 $minimum 项"
                }
                schema["maxItems"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (array.size > maximum) return "$path 最多允许 $maximum 项"
                }
                val itemSchema = schema["items"] as? JsonObject
                if (itemSchema != null) {
                    array.forEachIndexed { index, child ->
                        validateJsonValue(child, itemSchema, "$path[$index]")?.let { return it }
                    }
                }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) return "$path 必须是字符串"
                schema["minLength"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (primitive.content.length < minimum) return "$path 长度不能小于 $minimum"
                }
                schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (primitive.content.length > maximum) return "$path 长度不能大于 $maximum"
                }
            }
            "integer" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.longOrNull
                    ?: return "$path 必须是整数"
                schema["minimum"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 $minimum"
                }
                schema["maximum"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 $maximum"
                }
            }
            "number" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.doubleOrNull
                if (number == null || !number.isFinite()) return "$path 必须是有限数字"
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 $minimum"
                }
                schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 $maximum"
                }
            }
            "boolean" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                    return "$path 必须是布尔值"
                }
            }
        }

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && enumValues.none { it == value }) {
            return "$path 不在允许枚举值中"
        }
        return null
    }


    private companion object {
        val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
