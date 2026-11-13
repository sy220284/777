package com.labteto.dshmobile.interop.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The prompt belongs to one originating tool call and one server binding. */
data class McpElicitationRequest(
    val key: String,
    val message: String,
    val mode: String,
    val requestedSchema: JsonObject? = null,
    val url: String? = null,
)

/** Distinguish explicit refusal from UI dismissal; answers never reach the model transcript. */
data class McpElicitationReply(
    val action: String,
    val content: JsonObject? = null,
) {
    init {
        require(action in setOf("accept", "decline", "cancel"))
        require(action == "accept" || content == null)
    }
}

/** Apply only a server-authorized MRTR continuation, with the original request identity frozen. */
internal suspend fun mcpCallWithInputRequired(
    request: suspend (JsonObject) -> JsonObject,
    name: String,
    arguments: JsonObject,
    onElicitation: suspend (McpElicitationRequest) -> McpElicitationReply,
): JsonObject {
    var next = buildJsonObject { put("name", name); put("arguments", arguments) }
    var cancelledByUser = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = request(next)
        val kind = result["resultType"]?.jsonPrimitive?.contentOrNull ?: "complete"
        if (kind == "complete") return result
        require(kind == "input_required") { "MCP 返回未知的完成状态" }
        check(!cancelledByUser) { "MCP 服务未终止已取消的追问" }
        val inputs = result["inputRequests"] as? JsonObject ?: JsonObject(emptyMap())
        val opaqueState = result["requestState"]?.let {
            val primitive = it as? JsonPrimitive
            require(primitive != null && primitive.isString && primitive.content.length <= 65536) {
                "MCP 延续状态格式无效"
            }
            primitive.content
        }
        require(inputs.isNotEmpty() || opaqueState != null) {
            "MCP 追问缺少输入请求及延续状态"
        }
        // Detect cycles without user prompts rather than imposing an arbitrary round cap.
        check(inputs.isNotEmpty() ||
            next["requestState"]?.jsonPrimitive?.contentOrNull != opaqueState) {
            "MCP 追问没有新输入或状态变化，无法继续"
        }
        val repliesByKey = linkedMapOf<String, JsonObject>()
        // The server can issue independent prompts in one round. There is exactly
        // one user interaction at a time through the existing Work owner.
        for ((key, raw) in inputs) {
                require(key.length in 1..128) { "MCP 追问标识无效" }
                val entry = raw as? JsonObject ?: error("MCP 追问格式无效")
                require(entry["method"]?.jsonPrimitive?.content == "elicitation/create") {
                    "MCP 服务请求了当前未授权的交互能力"
                }
                val params = entry["params"] as? JsonObject ?: error("MCP 追问参数缺失")
                val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "form"
                require(mode in setOf("form", "url")) { "MCP 追问模式无效" }
                val message = params["message"]?.jsonPrimitive?.contentOrNull
                    ?: error("MCP 追问说明缺失")
                require(message.isNotBlank() && message.length <= 2000) { "MCP 追问说明格式无效" }
                val schema = if (mode == "form") {
                    (params["requestedSchema"] as? JsonObject)
                        ?: error("MCP 表单追问缺少 requestedSchema")
                } else null
                val url = if (mode == "url") {
                    params["url"]?.jsonPrimitive?.contentOrNull?.also { address ->
                        val parsed = java.net.URI(address)
                        require(parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() &&
                            parsed.userInfo == null && parsed.fragment == null) {
                            "MCP 外部追问地址必须为可信 HTTPS 链接"
                        }
                    } ?: error("MCP 外部追问缺少地址")
                } else null
                schema?.let(::validateMcpElicitationSchema)
                // Cancel is a terminal user decision for the whole round. Send a
                // cancel reply for remaining prompts without reopening the UI.
                val answer = if (cancelledByUser) McpElicitationReply("cancel")
                    else onElicitation(McpElicitationRequest(key, message, mode, schema, url))
                if (answer.action == "cancel") cancelledByUser = true
                if (answer.action == "accept") {
                    if (mode == "url") require(answer.content == null) {
                        "MCP 外部追问禁止通过客户端提交信息"
                    } else validateMcpElicitationAnswer(requireNotNull(schema), answer.content)
                }
            repliesByKey[key] = buildJsonObject {
                put("action", answer.action)
                answer.content?.let { put("content", it) }
            }
        }
        val replies = JsonObject(repliesByKey)
        next = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
            if (inputs.isNotEmpty()) put("inputResponses", replies)
            opaqueState?.let { put("requestState", it) }
        }
    }
}

/** Restricted MCP enum values; labels are UI-only and never sent to the server. */
private fun elicitationChoices(spec: JsonObject, titledKey: String): Set<String> {
    val entries = (spec["enum"] ?: spec[titledKey]) as? JsonArray
        ?: error("MCP 选择字段缺少枚举选项")
    require(entries.isNotEmpty()) { "MCP 选择字段没有选项" }
    val values = entries.map { entry ->
        val literal = if (entry is JsonObject) entry["const"] else entry
        val value = literal as? JsonPrimitive
        require(value != null && value.isString) { "MCP 选项必须为字符串" }
        value.content
    }
    require(values.distinct().size == values.size) { "MCP 表单选项重复" }
    return values.toSet()
}

private fun validateMcpElicitationSchema(schema: JsonObject) {
    require(schema["type"]?.jsonPrimitive?.content == "object") {
        "MCP 表单仅允许扁平对象"
    }
    val properties = schema["properties"] as? JsonObject
        ?: error("MCP 表单缺少字段定义")
    val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
        ?: emptySet()
    require(required.all { it in properties }) { "MCP 必填字段与表单不一致" }
    for ((name, raw) in properties) {
        require(name.isNotBlank() && name.length <= 100) { "MCP 表单字段名称无效" }
        val property = raw as? JsonObject ?: error("MCP 表单字段格式无效")
        val type = property["type"]?.jsonPrimitive?.content ?: error("MCP 字段缺少类型")
        require(type in setOf("string", "integer", "number", "boolean", "array")) {
            "MCP 表单字段类型暂未支持：$type"
        }
        if (type == "array") {
            val items = property["items"] as? JsonObject
                ?: error("MCP 多选字段缺少 items")
            require(items["type"]?.jsonPrimitive?.contentOrNull == "string" ||
                items["anyOf"] is JsonArray) { "MCP 多选仅支持字符串选项" }
            elicitationChoices(items, "anyOf")
            val minItems = property["minItems"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val maxItems = property["maxItems"]?.jsonPrimitive?.content?.toIntOrNull()
            require(minItems >= 0 && (maxItems == null || maxItems >= minItems)) {
                "MCP 多选数量约束无效"
            }
        }
        if (type == "string" && property["oneOf"] != null) {
            elicitationChoices(property, "oneOf")
        }
        // Passwords and API credentials belong in URL-mode elicitation, never form-mode.
        val lower = (name + " " + (property["format"]?.jsonPrimitive?.contentOrNull ?: "")).lowercase()
        require(listOf("password", "api_key", "apikey", "access_token", "secret", "credit_card").none { it in lower }) {
            "MCP 敏感数据必须通过外部安全页面采集"
        }
    }
}

private fun validateMcpElicitationAnswer(schema: JsonObject, content: JsonObject?) {
    val answer = requireNotNull(content) { "MCP 表单确认缺少内容" }
    val properties = schema.getValue("properties").jsonObject
    val required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
        ?: emptySet()
    require(required.all { it in answer }) { "MCP 必填字段未提供" }
    require(answer.keys.all { it in properties }) { "MCP 答复包含未请求字段" }
    for ((key, value) in answer) {
        val property = properties.getValue(key).jsonObject
        val type = property["type"]?.jsonPrimitive?.content
        if (type == "array") {
            val selected = value as? JsonArray ?: error("MCP 多选字段格式无效")
            val items = property["items"]!!.jsonObject
            val allowed = elicitationChoices(items, "anyOf")
            require(selected.all { item ->
                val literal = item as? JsonPrimitive
                literal != null && literal.isString && literal.content in allowed
            }) { "MCP 多选包含未授权的选项" }
            val minimum = property["minItems"]?.jsonPrimitive?.content?.toIntOrNull()
            val maximum = property["maxItems"]?.jsonPrimitive?.content?.toIntOrNull()
            require((minimum == null || selected.size >= minimum) &&
                (maximum == null || selected.size <= maximum)) { "MCP 多选数量不符合要求" }
            continue
        }
        val primitive = value as? JsonPrimitive ?: error("MCP 表单字段值无效")
        when (type) {
            "string" -> {
                require(primitive.isString) { "MCP 字符串字段格式无效" }
                val str = primitive.content
                val max = property["maxLength"]?.jsonPrimitive?.content?.toIntOrNull()
                val min = property["minLength"]?.jsonPrimitive?.content?.toIntOrNull()
                require((min == null || str.length >= min) && (max == null || str.length <= max)) {
                    "MCP 字段长度不符合要求"
                }
                if (property["oneOf"] != null) {
                    require(str in elicitationChoices(property, "oneOf")) {
                        "MCP 答复不在可选项中"
                    }
                } else (property["enum"] as? JsonArray)?.let { list ->
                    require(list.any { it == primitive }) { "MCP 答复不在可选项中" }
                }
            }
            "integer", "number" -> {
                val number = if (type == "integer") {
                    require(!primitive.isString && primitive.content.toLongOrNull() != null) {
                        "MCP 整数字段格式无效"
                    }
                    primitive.content.toLong().toDouble()
                } else {
                    require(!primitive.isString) { "MCP 数字字段格式无效" }
                    primitive.content.toDoubleOrNull()
                        ?: error("MCP 数字字段格式无效")
                }
                require(number.isFinite()) { "MCP 数字字段必须为有限值" }
                val minimum = property["minimum"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val maximum = property["maximum"]?.jsonPrimitive?.content?.toDoubleOrNull()
                require((minimum == null || number >= minimum) &&
                    (maximum == null || number <= maximum)) { "MCP 数字超出允许范围" }
            }
            "boolean" -> require(!primitive.isString && primitive.content in setOf("true","false")) {
                "MCP 布尔字段格式无效"
            }
        }
    }
}
