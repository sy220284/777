package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun transportProtocolFor(
    baseUrl: String,
    requested: AgentModelProtocol? = null,
): AgentModelProtocol {
    requested?.let { return it }
    val route = resolveLocalModelRoute(baseUrl, "transport-probe")
    return route.protocol
}

internal fun deepSeekAnthropicEndpoint(baseUrl: String): String {
    val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
    return when {
        clean.endsWith("/anthropic/v1/messages") -> clean
        clean.endsWith("/anthropic") -> "$clean/v1/messages"
        else -> "$clean/anthropic/v1/messages"
    }
}

internal fun deepSeekSupportsInHistorySystem(model: String): Boolean =
    model.trim().equals("deepseek-flash", ignoreCase = true)

internal fun deepSeekSupportsAdditionOnlyTools(model: String): Boolean =
    model.trim().equals("deepseek-flash", ignoreCase = true)

internal fun deepSeekAnthropicPayload(
    model: String,
    messages: List<JsonObject>,
    tools: JsonArray,
    stream: Boolean,
): JsonObject {
    val inHistory = deepSeekSupportsInHistorySystem(model)
    val leadingSystem = if (inHistory) {
        messages.takeWhile { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
            .mapNotNull { messageText(it["content"]) }
            .lastOrNull(String::isNotBlank)
            .orEmpty()
    } else {
        messages
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
            .mapNotNull { messageText(it["content"]) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }
    val deferredTools = if (deepSeekSupportsAdditionOnlyTools(model)) {
        developerToolAdditions(messages)
    } else {
        emptySet()
    }

    return buildJsonObject {
        put("model", model)
        put("max_tokens", 65_536)
        if (leadingSystem.isNotBlank()) put("system", leadingSystem)
        put("messages", anthropicMessages(messages, inHistory))
        if (tools.isNotEmpty()) {
            put("tools", anthropicTools(tools, deferredTools))
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }
        put("stream", stream)
    }
}

internal fun deepSeekAnthropicHasToolChanges(payload: JsonObject): Boolean =
    (payload["messages"] as? JsonArray).orEmpty().any { raw ->
        val message = raw as? JsonObject ?: return@any false
        (message["content"] as? JsonArray).orEmpty().any { block ->
            val type = (block as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            type == "tool_addition" || type == "tool_removal"
        }
    }

private fun anthropicMessages(
    messages: List<JsonObject>,
    inHistorySystem: Boolean,
): JsonArray {
    data class Row(val role: String, val blocks: MutableList<JsonElement>)
    val rows = mutableListOf<Row>()
    val pendingSystemUpdates = mutableListOf<JsonElement>()
    var sawConversation = false

    fun append(role: String, blocks: List<JsonElement>) {
        if (blocks.isEmpty()) return
        val last = rows.lastOrNull()
        if (last?.role == role) last.blocks += blocks
        else rows += Row(role, blocks.toMutableList())
    }

    fun flushUpdates() {
        if (pendingSystemUpdates.isEmpty()) return
        if (rows.lastOrNull()?.role != "user") {
            error("DeepSeek Messages system/tool update lacks preceding user or tool-result turn")
        }
        rows += Row("system", pendingSystemUpdates.toMutableList())
        pendingSystemUpdates.clear()
    }

    messages.forEach { message ->
        when (message["role"]?.jsonPrimitive?.contentOrNull) {
            "developer" -> {
                val blocks = message["content"] as? JsonArray ?: return@forEach
                blocks.forEach { raw ->
                    val block = raw as? JsonObject ?: return@forEach
                    when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "tool-addition" -> {
                            val name = block["toolName"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            pendingSystemUpdates += buildJsonObject {
                                put("type", "tool_addition")
                                put("tool", buildJsonObject {
                                    put("type", "tool_reference")
                                    put("name", name)
                                })
                            }
                        }
                        "tool-removal" -> {
                            val name = block["toolName"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            pendingSystemUpdates += buildJsonObject {
                                put("type", "tool_removal")
                                put("tool", buildJsonObject {
                                    put("type", "tool_reference")
                                    put("name", name)
                                })
                            }
                        }
                        "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf(String::isNotBlank)
                            ?.let { text ->
                                pendingSystemUpdates += buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            }
                    }
                }
            }
            "system" -> {
                val text = messageText(message["content"]).orEmpty()
                if (inHistorySystem && sawConversation && text.isNotBlank()) {
                    pendingSystemUpdates += buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }
            "user" -> {
                append("user", anthropicContentBlocks(message["content"]))
                sawConversation = true
            }
            "assistant" -> {
                flushUpdates()
                val blocks = mutableListOf<JsonElement>()
                message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { reasoning ->
                        blocks += buildJsonObject {
                            put("type", "thinking")
                            put("thinking", reasoning)
                        }
                    }
                blocks += anthropicContentBlocks(message["content"])
                message["tool_calls"]?.jsonArray.orEmpty().forEach { element ->
                    val call = element as? JsonObject ?: return@forEach
                    val fn = call["function"] as? JsonObject ?: return@forEach
                    val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val raw = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    val input = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
                        .getOrNull() ?: JsonObject(emptyMap())
                    blocks += buildJsonObject {
                        put("type", "tool_use")
                        put("id", id)
                        put("name", name)
                        put("input", input)
                    }
                }
                append("assistant", blocks)
                sawConversation = true
            }
            "tool" -> {
                val id = message["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                append(
                    "user",
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", id)
                            put("content", messageText(message["content"]).orEmpty())
                            put("is_error", modelToolResultIsError(message["content"]))
                        },
                    ),
                )
                sawConversation = true
            }
        }
    }
    if (pendingSystemUpdates.isNotEmpty()) flushUpdates()
    return JsonArray(rows.map { row ->
        buildJsonObject {
            put("role", row.role)
            put("content", JsonArray(row.blocks))
        }
    })
}

private fun developerToolAdditions(messages: List<JsonObject>): Set<String> =
    messages.asSequence()
        .filter { it["role"]?.jsonPrimitive?.contentOrNull == "developer" }
        .flatMap { message -> ((message["content"] as? JsonArray).orEmpty()).asSequence() }
        .mapNotNull { raw ->
            val block = raw as? JsonObject ?: return@mapNotNull null
            if (block["type"]?.jsonPrimitive?.contentOrNull != "tool-addition") return@mapNotNull null
            block["toolName"]?.jsonPrimitive?.contentOrNull
        }
        .toSet()

private fun anthropicContentBlocks(content: JsonElement?): List<JsonElement> = when (content) {
    null, JsonNull -> emptyList()
    is JsonPrimitive -> content.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let { text -> listOf(buildJsonObject { put("type", "text"); put("text", text) }) }
        .orEmpty()
    is JsonArray -> content.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        when (part["type"]?.jsonPrimitive?.contentOrNull) {
            "text", "output_text" -> part["text"]?.jsonPrimitive?.contentOrNull
                ?.let { text -> buildJsonObject { put("type", "text"); put("text", text) } }
            "image" -> part
            "image_url" -> {
                val url = when (val imageUrl = part["image_url"]) {
                    is JsonPrimitive -> imageUrl.contentOrNull
                    is JsonObject -> imageUrl["url"]?.jsonPrimitive?.contentOrNull
                    else -> null
                } ?: return@mapNotNull null
                dataUrlToAnthropicImage(url)
            }
            else -> null
        }
    }
    else -> emptyList()
}

private fun dataUrlToAnthropicImage(url: String): JsonObject? {
    val match = Regex("^data:([^;]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(url)
        ?: return null
    return buildJsonObject {
        put("type", "image")
        put("source", buildJsonObject {
            put("type", "base64")
            put("media_type", match.groupValues[1])
            put("data", match.groupValues[2])
        })
    }
}

private fun anthropicTools(
    tools: JsonArray,
    deferredNames: Set<String>,
): JsonArray = buildJsonArray {
    tools.forEach { element ->
        val fn = (element as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
        val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        add(buildJsonObject {
            put("name", name)
            fn["description"]?.jsonPrimitive?.contentOrNull?.let { put("description", it) }
            put("input_schema", fn["parameters"] as? JsonObject ?: JsonObject(emptyMap()))
            if (name in deferredNames) put("defer_loading", true)
        })
    }
}

private fun modelToolResultIsError(content: JsonElement?): Boolean {
    val raw = messageText(content)?.trim().orEmpty()
    if (!raw.startsWith("{")) return false
    return runCatching {
        val obj = Json.parseToJsonElement(raw) as? JsonObject
        obj?.get("status")?.jsonPrimitive?.contentOrNull == "error"
    }.getOrDefault(false)
}

private fun messageText(content: JsonElement?): String? = when (content) {
    is JsonPrimitive -> content.contentOrNull
    is JsonArray -> content.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "text", "output_text" -> item["text"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }.joinToString("\n").takeIf(String::isNotBlank)
    else -> null
}

internal fun parseDeepSeekAnthropicReply(root: JsonObject): LocalModelReply {
    val blocks = root["content"]?.jsonArray.orEmpty()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val calls = mutableListOf<LocalToolCall>()

    blocks.forEach { element ->
        val block = element as? JsonObject ?: return@forEach
        when (block["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.let(text::append)
            "thinking" -> block["thinking"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
            "tool_use" -> {
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                calls += LocalToolCall(
                    id = id,
                    name = name,
                    arguments = input,
                    rawArguments = input.toString(),
                )
            }
        }
    }
    return anthropicReply(
        text = text.toString(),
        reasoning = reasoning.toString(),
        calls = calls,
        usage = parseDeepSeekAnthropicUsage(root),
    )
}

private fun anthropicReply(
    text: String,
    reasoning: String,
    calls: List<LocalToolCall>,
    usage: DeepSeekTokenUsage,
): LocalModelReply {
    val message = buildJsonObject {
        put("role", "assistant")
        put("content", text)
        if (reasoning.isNotBlank()) put("reasoning_content", reasoning)
        if (calls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.rawArguments)
                        })
                    })
                }
            })
        }
    }
    return LocalModelReply(
        message = message,
        content = text.takeIf(String::isNotBlank),
        reasoning = reasoning.takeIf(String::isNotBlank),
        toolCalls = calls,
        usage = usage,
    )
}

internal class DeepSeekAnthropicStreamAccumulator(
    private val json: Json,
    private val onDelta: (LocalModelDelta) -> Unit,
) {
    private data class Block(
        var type: String = "",
        var id: String? = null,
        var name: String? = null,
        val text: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        val input: StringBuilder = StringBuilder(),
    )

    private val blocks = linkedMapOf<Int, Block>()
    private var inputTokens = 0L
    private var cacheReadTokens = 0L
    private var cacheCreationTokens = 0L
    private var outputTokens = 0L

    fun accept(root: JsonObject) {
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "message_start" -> {
                val usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
                mergeUsage(usage)
            }
            "content_block_start" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                val started = root["content_block"] as? JsonObject ?: return
                val block = blocks.getOrPut(index) { Block() }
                block.type = started["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                block.id = started["id"]?.jsonPrimitive?.contentOrNull
                block.name = started["name"]?.jsonPrimitive?.contentOrNull
                if (block.type == "text") started["text"]?.jsonPrimitive?.contentOrNull?.let(block.text::append)
                if (block.type == "thinking") started["thinking"]?.jsonPrimitive?.contentOrNull?.let(block.reasoning::append)
                val initialInput = started["input"] as? JsonObject
                if (block.type == "tool_use" && initialInput != null && initialInput.isNotEmpty()) {
                    block.input.append(initialInput.toString())
                }
            }
            "content_block_delta" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                val delta = root["delta"] as? JsonObject ?: return
                val block = blocks.getOrPut(index) { Block() }
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { value ->
                        block.type = "text"
                        block.text.append(value)
                        onDelta(LocalModelDelta(content = value))
                    }
                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { value ->
                        block.type = "thinking"
                        block.reasoning.append(value)
                        onDelta(LocalModelDelta(reasoning = value))
                    }
                    "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let { value ->
                        block.type = "tool_use"
                        block.input.append(value)
                    }
                }
            }
            "message_delta" -> mergeUsage(root["usage"] as? JsonObject)
        }
    }

    private fun mergeUsage(usage: JsonObject?) {
        if (usage == null) return
        inputTokens = maxOf(inputTokens, usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L)
        cacheReadTokens = maxOf(cacheReadTokens, usage["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L)
        cacheCreationTokens = maxOf(
            cacheCreationTokens,
            usage["cache_creation_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
        outputTokens = maxOf(outputTokens, usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    fun result(): LocalModelReply {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<LocalToolCall>()
        blocks.toSortedMap().values.forEach { block ->
            when (block.type) {
                "text" -> text.append(block.text)
                "thinking" -> reasoning.append(block.reasoning)
                "tool_use" -> {
                    val id = block.id ?: error("Anthropic 工具调用缺少 id")
                    val name = block.name ?: error("Anthropic 工具调用缺少 name")
                    val raw = block.input.toString().ifBlank { "{}" }
                    val input = runCatching { json.parseToJsonElement(raw) as? JsonObject }
                        .getOrNull() ?: error("Anthropic 工具参数不是合法对象")
                    calls += LocalToolCall(id, name, input, raw)
                }
            }
        }
        val usageRoot = buildJsonObject {
            put("usage", buildJsonObject {
                put("input_tokens", inputTokens)
                put("cache_read_input_tokens", cacheReadTokens)
                put("cache_creation_input_tokens", cacheCreationTokens)
                put("output_tokens", outputTokens)
            })
        }
        return anthropicReply(
            text = text.toString(),
            reasoning = reasoning.toString(),
            calls = calls,
            usage = parseDeepSeekAnthropicUsage(usageRoot),
        )
    }
}
