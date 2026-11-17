package com.labteto.dshmobile.interop.mcp

import java.math.BigDecimal
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request

/** The actual property route, not a JSON Schema reference or a rewritten argument. */
internal data class McpHttpParameterHeader(
    val path: List<String>,
    val suffix: String,
    val type: String,
)

/**
 * SEP-2243: compile header declarations from an untrusted tools/list schema once.
 * Only a chain of properties is statically reachable. Visit every other schema
 * branch to reject misplaced annotations rather than accidentally ignoring them.
 */
internal object McpHttpToolHeaders {
    private val token = Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")
    private val integerLimit = BigDecimal("9007199254740991")

    /** Same independent per-tool admission in the host and the real Android HTTP worker. */
    fun admit(definitions: List<McpToolDefinition>):
        Pair<McpToolDiscovery, Map<String, List<McpHttpParameterHeader>>> {
        val accepted = mutableListOf<McpToolDefinition>()
        val rejected = linkedMapOf<String, String>()
        val rules = linkedMapOf<String, List<McpHttpParameterHeader>>()
        definitions.forEach { definition ->
            try {
                val fields = compile(definition.inputSchema)
                accepted += definition
                rules[definition.name] = fields
            } catch (error: IllegalArgumentException) {
                rejected[definition.name] = error.message ?: "x-mcp-header 注解无效"
            }
        }
        return McpToolDiscovery(accepted, rejected) to rules.toMap()
    }

    fun compile(inputSchema: JsonObject): List<McpHttpParameterHeader> {
        val headers = mutableListOf<McpHttpParameterHeader>()
        val seen = mutableSetOf<String>()

        fun inspect(value: JsonElement, path: List<String>?, location: String) {
            when (value) {
                is JsonArray -> value.forEachIndexed { index, item ->
                    inspect(item, null, "$location/$index")
                }
                is JsonObject -> {
                    value["x-mcp-header"]?.let { field ->
                        require(path != null && path.isNotEmpty()) {
                            "$location: x-mcp-header 只允许位于根 properties 链可到达的字段"
                        }
                        val suffix = (field as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                        require(suffix != null && suffix.isNotEmpty() && token.matches(suffix)) {
                            "$location: x-mcp-header 必须是有效的非空 HTTP 字段名片段"
                        }
                        require(seen.add(suffix.lowercase(Locale.ROOT))) {
                            "$location: x-mcp-header 名称不区分大小写，不能重复：$suffix"
                        }
                        val declared = when (val type = value["type"]) {
                            is JsonPrimitive -> listOf(type.takeIf { it.isString }?.content)
                            is JsonArray -> type.map { (it as? JsonPrimitive)
                                ?.takeIf { primitive -> primitive.isString }?.content }
                            else -> emptyList()
                        }
                        val primitive = declared.filterNotNull().filter { it != "null" }
                        require(primitive.size == 1 && primitive.single() in
                            setOf("string", "integer", "boolean") &&
                            declared.size == primitive.size + declared.count { it == "null" }) {
                            "$location: x-mcp-header 必须指定 string、integer 或 boolean（可包含 null）"
                        }
                        headers += McpHttpParameterHeader(path, suffix, primitive.single())
                    }
                    value.forEach { (keyword, child) ->
                        if (keyword == "properties" && child is JsonObject) {
                            child.forEach { (key, schema) ->
                                inspect(schema, path?.plus(key), "$location/properties/$key")
                            }
                        } else {
                            inspect(child, null, "$location/$keyword")
                        }
                    }
                }
                else -> Unit
            }
        }

        inspect(inputSchema, emptyList(), "$")
        return headers.toList()
    }

    fun apply(
        builder: Request.Builder,
        headers: List<McpHttpParameterHeader>,
        arguments: JsonObject,
    ) {
        headers.forEach { binding ->
            val value = binding.path.fold(arguments as JsonElement) { current, key ->
                (current as? JsonObject)?.get(key) ?: JsonNull
            }
            if (value == JsonNull) return@forEach
            val primitive = value as? JsonPrimitive
                ?: throw IllegalArgumentException("MCP 参数头 ${binding.suffix} 的值必须是基本类型")
            val text = when (binding.type) {
                "string" -> {
                    require(primitive.isString) { "MCP 参数头 ${binding.suffix} 必须为字符串" }
                    primitive.content
                }
                "boolean" -> {
                    require(!primitive.isString && primitive.content in setOf("true", "false")) {
                        "MCP 参数头 ${binding.suffix} 必须为布尔值"
                    }
                    primitive.content
                }
                "integer" -> {
                    require(!primitive.isString) { "MCP 参数头 ${binding.suffix} 必须为整数" }
                    val number = primitive.content.toBigDecimalOrNull()
                    require(number != null && number.abs() <= integerLimit) {
                        "MCP 参数头 ${binding.suffix} 整数超出 IEEE754 安全范围"
                    }
                    try {
                        number.toBigIntegerExact().toString()
                    } catch (error: ArithmeticException) {
                        throw IllegalArgumentException("MCP 参数头 ${binding.suffix} 必须为整数", error)
                    }
                }
                else -> error("未知的 MCP 参数头类型：${binding.type}")
            }
            builder.header("Mcp-Param-${binding.suffix}", encodeHeaderValue(text))
        }
    }
}
