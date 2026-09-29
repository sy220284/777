package com.labteto.dshmobile.harness.tools

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
)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
)

fun interface HarnessToolExecutor {
    suspend fun execute(context: ToolContext, input: JsonObject, rawArguments: String): ToolResult
}

data class HarnessTool(
    val name: String,
    val schema: JsonObject,
    val access: ToolAccess = ToolAccess.READ_ONLY,
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
    val timeoutMillis: Long? = null,
    val executor: HarnessToolExecutor,
)

class ToolRegistry {
    private val tools = linkedMapOf<String, HarnessTool>()

    @Synchronized
    fun register(tool: HarnessTool, replace: Boolean = false) {
        require(tool.name.isNotBlank()) { "工具名不能为空" }
        if (!replace) require(tool.name !in tools) { "工具已注册：${tool.name}" }
        tools[tool.name] = tool
    }

    @Synchronized
    fun unregister(name: String): HarnessTool? = tools.remove(name)

    @Synchronized
    fun get(name: String): HarnessTool? = tools[name]

    @Synchronized
    fun names(): List<String> = tools.keys.toList()

    @Synchronized
    fun schemas(): JsonArray = JsonArray(tools.values.map(HarnessTool::schema))

    suspend fun execute(
        name: String,
        input: JsonObject,
        rawArguments: String = input.toString(),
        context: ToolContext = ToolContext(),
    ): ToolResult {
        val tool = synchronized(this) { tools[name] } ?: return ToolResult(
            content = "未知工具：$name",
            isError = true,
        )
        validateToolInput(tool, input)?.let { problem ->
            return ToolResult(
                content = "工具参数无效：$name：$problem",
                isError = true,
            )
        }
        if (!context.allowMutation && tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
            return ToolResult("当前作用域禁止执行会改变状态的工具：$name", isError = true)
        }
        val needsApproval = when (tool.approvalPolicy) {
            ToolApprovalPolicy.NEVER -> false
            ToolApprovalPolicy.ALWAYS -> true
            ToolApprovalPolicy.MUTATION -> tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        }
        if (needsApproval) {
            val approval = context.approval
                ?: return ToolResult("工具需要人工审批：$name", isError = true)
            if (!approval(tool)) return ToolResult("用户拒绝执行工具：$name", isError = true)
        }
        val timeoutMillis = tool.timeoutMillis
        if (timeoutMillis == null) return tool.executor.execute(context, input, rawArguments)
        require(timeoutMillis > 0L) { "工具超时必须大于 0：$name" }
        return withTimeoutOrNull(timeoutMillis) {
            tool.executor.execute(context, input, rawArguments)
        } ?: ToolResult(
            content = "工具执行超时：$name（${timeoutMillis} ms）",
            isError = true,
        )
    }

    @Synchronized
    internal fun snapshot(): Map<String, HarnessTool> = LinkedHashMap(tools)

    @Synchronized
    internal fun restore(snapshot: Map<String, HarnessTool>) {
        tools.clear()
        tools.putAll(snapshot)
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

}
