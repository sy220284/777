package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_MODEL_REPLAY_KEY = "_dsh_model_replay"

enum class LocalCanonicalRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL,
}

sealed interface LocalCanonicalContent {
    data class Text(val text: String) : LocalCanonicalContent
    data class Reasoning(val text: String) : LocalCanonicalContent
    data class Image(val dataUrl: String, val detail: String? = null) : LocalCanonicalContent
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
        val rawArguments: String,
        val providerMetadata: JsonObject = JsonObject(emptyMap()),
    ) : LocalCanonicalContent
    data class ToolResult(
        val callId: String,
        val content: String,
        val isError: Boolean? = null,
    ) : LocalCanonicalContent
    data class Raw(val value: JsonObject) : LocalCanonicalContent
}

data class LocalModelReplayEnvelope(
    val adapterId: String,
    val routeFingerprint: String?,
    val payload: JsonObject,
)

data class LocalCanonicalMessage(
    val role: LocalCanonicalRole,
    val content: List<LocalCanonicalContent>,
    val replay: LocalModelReplayEnvelope? = null,
)

internal data class LocalCanonicalToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean? = null,
)

internal object LocalCanonicalModelCodec {
    private const val LEGACY_RESPONSES_OUTPUT_KEY = "_dsh_responses_output"

    fun messages(source: List<JsonObject>): List<LocalCanonicalMessage> = source.map(::message)

    fun message(source: JsonObject): LocalCanonicalMessage {
        val role = when (source["role"]?.jsonPrimitive?.contentOrNull) {
            "system" -> LocalCanonicalRole.SYSTEM
            "developer" -> LocalCanonicalRole.DEVELOPER
            "assistant" -> LocalCanonicalRole.ASSISTANT
            "tool" -> LocalCanonicalRole.TOOL
            else -> LocalCanonicalRole.USER
        }
        val blocks = mutableListOf<LocalCanonicalContent>()
        if (role == LocalCanonicalRole.TOOL) {
            source["tool_call_id"]?.jsonPrimitive?.contentOrNull?.let { callId ->
                blocks += LocalCanonicalContent.ToolResult(
                    callId = callId,
                    content = text(source["content"]),
                    isError = source["is_error"]?.jsonPrimitive?.booleanOrNull,
                )
            }
        } else {
            blocks += contentBlocks(source["content"])
            if (role == LocalCanonicalRole.ASSISTANT) {
                source["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { blocks += LocalCanonicalContent.Reasoning(it) }
                val historyCalls = mutableListOf<LocalToolCall>()
                val calls = source["tool_calls"]?.takeUnless { it == JsonNull }?.let {
                    it as? JsonArray ?: throw LocalModelException("MODEL_HISTORY_INVALID", "历史 tool_calls 必须是数组", false)
                }
                calls.orEmpty().forEach { raw ->
                    val call = raw as? JsonObject
                        ?: throw LocalModelException("MODEL_HISTORY_INVALID", "历史工具调用必须是对象", false)
                    val function = call["function"] as? JsonObject
                        ?: throw LocalModelException("MODEL_HISTORY_INVALID", "历史工具调用缺少 function 对象", false)
                    val validated = validatedModelToolCall(call["id"], function["name"], function["arguments"], "MODEL_HISTORY_INVALID")
                    historyCalls += validated
                    val metadata = buildJsonObject {
                        call.forEach { (key, value) ->
                            if (key !in setOf("id", "type", "function")) put(key, value)
                        }
                        val functionMetadata = buildJsonObject {
                            function.forEach { (key, value) ->
                                if (key !in setOf("name", "arguments")) put(key, value)
                            }
                        }
                        if (functionMetadata.isNotEmpty()) put("function_metadata", functionMetadata)
                    }
                    blocks += LocalCanonicalContent.ToolCall(
                        id = validated.id,
                        name = validated.name,
                        arguments = validated.arguments,
                        rawArguments = validated.rawArguments,
                        providerMetadata = metadata,
                    )
                }
                requireUniqueModelToolCallIds(historyCalls, "MODEL_HISTORY_INVALID")
            }
        }
        return LocalCanonicalMessage(role, blocks, replay(source))
    }

    fun tools(source: JsonArray): List<LocalCanonicalToolDefinition> = source.mapIndexed { index, raw ->
        val objectValue = raw as? JsonObject ?: throw invalidTool(index, "工具定义必须是对象")
        objectValue["type"]?.let { declared ->
            val type = declared as? JsonPrimitive
            if (type == null || !type.isString || type.content != "function") {
                throw invalidTool(index, "type 必须是 function")
            }
        }
        val function = when (val value = objectValue["function"]) {
            null -> objectValue
            is JsonObject -> value
            else -> throw invalidTool(index, "function 必须是对象")
        }
        fun string(field: String): String? {
            val value = function[field] ?: return null
            if (value == JsonNull) return null
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) throw invalidTool(index, "$field 必须是字符串")
            return primitive.content
        }
        val name = string("name")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw invalidTool(index, "name 必须是非空字符串")
        val description = string("description")?.trim()?.takeIf(String::isNotEmpty) ?: "调用 $name 工具。"
        val parameters = when (val value = function["parameters"]) {
            null, JsonNull -> buildJsonObject {
                put("type", "object"); put("properties", buildJsonObject {})
                put("required", buildJsonArray {}); put("additionalProperties", false)
            }
            is JsonObject -> value
            else -> throw invalidTool(index, "parameters 必须是对象")
        }
        val strict = when (val value = function["strict"]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeUnless { it.isString }?.booleanOrNull
                ?: throw invalidTool(index, "strict 必须是 JSON 布尔值")
            else -> throw invalidTool(index, "strict 必须是 JSON 布尔值")
        }
        LocalCanonicalToolDefinition(name, description, parameters, strict)
    }

    private fun invalidTool(index: Int, detail: String) = LocalModelException(
        code = "MODEL_TOOL_SCHEMA_INVALID",
        message = "模型工具定义第 ${index + 1} 项无效：$detail",
        retryable = false,
    )

    fun toLegacyTools(source: List<LocalCanonicalToolDefinition>): JsonArray = buildJsonArray {
        source.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                    tool.strict?.let { put("strict", it) }
                })
            })
        }
    }

    fun toLegacyMessages(
        source: List<LocalCanonicalMessage>,
        adapterId: String,
        routeFingerprint: String,
    ): List<JsonObject> = source.filterNot(::isEmptyCanonicalAssistant).map { toLegacyMessage(it, adapterId, routeFingerprint) }

    fun toHistoryMessage(message: LocalCanonicalMessage): JsonObject =
        toLegacyMessage(message, adapterId = "", routeFingerprint = "")

    fun canonicalToolCalls(message: JsonObject): List<LocalCanonicalContent.ToolCall> =
        this.message(message).content.filterIsInstance<LocalCanonicalContent.ToolCall>()

    /** Summary diagnostics tolerate old partial metadata; this never creates executable calls. */
    fun diagnosticToolNames(message: JsonObject): List<String> {
        val generic = message["model_tool_calls"] as? JsonArray
        val calls = generic ?: message["tool_calls"] as? JsonArray ?: return emptyList()
        return calls.mapNotNull { raw ->
            val call = raw as? JsonObject ?: return@mapNotNull null
            val name = if (generic != null) call["name"] else (call["function"] as? JsonObject)?.get("name")
            (name as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
        }
    }

    fun hasToolCalls(message: JsonObject): Boolean = canonicalToolCalls(message).isNotEmpty()

    fun compatibleReplay(
        message: LocalCanonicalMessage,
        adapterId: String,
        routeFingerprint: String,
    ): LocalModelReplayEnvelope? =
        message.replay?.takeIf { replayCompatible(it, adapterId, routeFingerprint) }

    fun canonicalizeReply(
        reply: LocalModelReply,
        adapterId: String,
        routeFingerprint: String,
    ): LocalModelReply {
        val parsed = message(reply.message)
        if (isEmptyCanonicalAssistant(parsed)) {
            throw LocalModelException(
                "MODEL_EMPTY_RESPONSE", "模型没有返回正文或工具调用，本轮未写入会话历史；请稍后重试。", false,
                requestId = reply.requestId,
            )
        }
        val replay = parsed.replay?.let {
            it.copy(
                adapterId = adapterId,
                routeFingerprint = routeFingerprint,
            )
        } ?: if (adapterId == LocalModelAdapterIds.OPENAI_CHAT) {
            chatPrivateReplay(parsed, routeFingerprint)
        } else {
            null
        }
        val canonical = parsed.copy(replay = replay)
        return reply.copy(
            message = toHistoryMessage(canonical),
            canonicalMessage = canonical,
        )
    }

    private fun chatPrivateReplay(
        message: LocalCanonicalMessage,
        routeFingerprint: String,
    ): LocalModelReplayEnvelope? {
        val reasoning = message.content.filterIsInstance<LocalCanonicalContent.Reasoning>()
            .joinToString("") { it.text }
            .takeIf(String::isNotBlank)
        val metadata = buildJsonObject {
            message.content.filterIsInstance<LocalCanonicalContent.ToolCall>().forEach { call ->
                if (call.providerMetadata.isNotEmpty()) put(call.id, call.providerMetadata)
            }
        }
        if (reasoning == null && metadata.isEmpty()) return null
        return LocalModelReplayEnvelope(
            adapterId = LocalModelAdapterIds.OPENAI_CHAT,
            routeFingerprint = routeFingerprint,
            payload = buildJsonObject {
                reasoning?.let { put("reasoning_content", it) }
                if (metadata.isNotEmpty()) put("tool_metadata", metadata)
            },
        )
    }

    private fun toLegacyMessage(
        message: LocalCanonicalMessage,
        adapterId: String,
        routeFingerprint: String,
    ): JsonObject = buildJsonObject {
        put("role", when (message.role) {
            LocalCanonicalRole.SYSTEM -> "system"
            LocalCanonicalRole.DEVELOPER -> "developer"
            LocalCanonicalRole.USER -> "user"
            LocalCanonicalRole.ASSISTANT -> "assistant"
            LocalCanonicalRole.TOOL -> "tool"
        })
        val textBlocks = message.content.filterIsInstance<LocalCanonicalContent.Text>()
        val imageBlocks = message.content.filterIsInstance<LocalCanonicalContent.Image>()
        val rawBlocks = message.content.filterIsInstance<LocalCanonicalContent.Raw>()
        if (message.role == LocalCanonicalRole.TOOL) {
            message.content.filterIsInstance<LocalCanonicalContent.ToolResult>().firstOrNull()?.let { result ->
                put("tool_call_id", result.callId)
                put("content", result.content)
                result.isError?.let { put("is_error", it) }
            }
        } else if (imageBlocks.isEmpty() && rawBlocks.isEmpty()) {
            val joined = textBlocks.joinToString("") { it.text }
            if (joined.isNotEmpty() || message.role != LocalCanonicalRole.ASSISTANT) put("content", joined)
        } else {
            put("content", buildJsonArray {
                message.content.forEach { block ->
                    when (block) {
                        is LocalCanonicalContent.Text -> add(buildJsonObject {
                            put("type", "text"); put("text", block.text)
                        })
                        is LocalCanonicalContent.Image -> add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", block.dataUrl)
                                block.detail?.let { put("detail", it) }
                            })
                        })
                        is LocalCanonicalContent.Raw -> add(block.value)
                        else -> Unit
                    }
                }
            })
        }
        val compatibleReplay = message.replay?.takeIf {
            adapterId.isNotEmpty() && replayCompatible(it, adapterId, routeFingerprint)
        }
        val reasoning = message.content.filterIsInstance<LocalCanonicalContent.Reasoning>()
            .joinToString("") { it.text }
            .takeIf(String::isNotBlank)
        val mayProjectLegacyChatPrivateState =
            adapterId == LocalModelAdapterIds.OPENAI_CHAT && message.replay == null
        if (
            reasoning != null &&
            (
                adapterId.isEmpty() ||
                    mayProjectLegacyChatPrivateState ||
                    compatibleReplay?.adapterId == LocalModelAdapterIds.OPENAI_CHAT
                )
        ) {
            put("reasoning_content", reasoning)
        }
        val calls = message.content.filterIsInstance<LocalCanonicalContent.ToolCall>()
        if (calls.isNotEmpty()) {
            val replayToolMetadata = compatibleReplay
                ?.takeIf { it.adapterId == LocalModelAdapterIds.OPENAI_CHAT }
                ?.payload
                ?.get("tool_metadata") as? JsonObject
            put("tool_calls", buildJsonArray {
                calls.forEach { call ->
                    val metadata = when {
                        adapterId.isEmpty() && message.replay != null -> JsonObject(emptyMap())
                        adapterId.isEmpty() -> call.providerMetadata
                        adapterId == LocalModelAdapterIds.OPENAI_CHAT -> {
                            (replayToolMetadata?.get(call.id) as? JsonObject)
                                ?: call.providerMetadata.takeIf { message.replay == null }
                                ?: JsonObject(emptyMap())
                        }
                        else -> JsonObject(emptyMap())
                    }
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        metadata.forEach { (key, value) ->
                            if (key != "function_metadata") put(key, value)
                        }
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.rawArguments)
                            (metadata["function_metadata"] as? JsonObject)?.forEach { (key, value) ->
                                put(key, value)
                            }
                        })
                    })
                }
            })
        }
        message.replay?.let { replay ->
            if (adapterId.isEmpty()) {
                // Provider-neutral replay is durable local metadata. It must never be emitted as
                // an arbitrary wire-message field to third-party providers.
                put(LOCAL_MODEL_REPLAY_KEY, encodeReplay(replay))
            } else if (replayCompatible(replay, adapterId, routeFingerprint)) {
                if (adapterId == LocalModelAdapterIds.OPENAI_RESPONSES) {
                    (replay.payload["output"] as? JsonArray)?.let {
                        put(LEGACY_RESPONSES_OUTPUT_KEY, it)
                    }
                }
            }
        }
    }

    private fun contentBlocks(content: JsonElement?): List<LocalCanonicalContent> {
        if (content == null || content is JsonNull) return emptyList()
        if (content is JsonPrimitive) {
            return content.contentOrNull?.let { listOf(LocalCanonicalContent.Text(it)) }.orEmpty()
        }
        if (content !is JsonArray) return emptyList()
        return content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "input_text", "output_text" ->
                    obj["text"]?.jsonPrimitive?.contentOrNull?.let(LocalCanonicalContent::Text)
                "image_url", "input_image" -> {
                    val image = obj["image_url"]
                    val url = when (image) {
                        is JsonPrimitive -> image.contentOrNull
                        is JsonObject -> image["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    url?.let {
                        LocalCanonicalContent.Image(
                            dataUrl = it,
                            detail = (image as? JsonObject)?.get("detail")?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
                else -> LocalCanonicalContent.Raw(obj)
            }
        }
    }

    private fun text(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        else -> ""
    }

    private fun replay(source: JsonObject): LocalModelReplayEnvelope? {
        (source[LOCAL_MODEL_REPLAY_KEY] as? JsonObject)?.let { encoded ->
            val adapter = encoded["adapter_id"]?.jsonPrimitive?.contentOrNull ?: return@let
            val payload = encoded["payload"] as? JsonObject ?: return@let
            return LocalModelReplayEnvelope(
                adapterId = adapter,
                routeFingerprint = encoded["route_fingerprint"]?.jsonPrimitive?.contentOrNull,
                payload = payload,
            )
        }
        val responses = source[LEGACY_RESPONSES_OUTPUT_KEY] as? JsonArray ?: return null
        return LocalModelReplayEnvelope(
            adapterId = LocalModelAdapterIds.OPENAI_RESPONSES,
            routeFingerprint = null,
            payload = buildJsonObject { put("output", responses) },
        )
    }

    private fun encodeReplay(replay: LocalModelReplayEnvelope): JsonObject = buildJsonObject {
        put("adapter_id", replay.adapterId)
        replay.routeFingerprint?.let { put("route_fingerprint", it) }
        put("payload", replay.payload)
    }

    private fun replayCompatible(
        replay: LocalModelReplayEnvelope,
        adapterId: String,
        routeFingerprint: String,
    ): Boolean =
        replay.adapterId == adapterId &&
            (replay.routeFingerprint == null || replay.routeFingerprint == routeFingerprint)
}

internal object LocalModelAdapterIds {
    const val OPENAI_CHAT = "openai-compatible-chat"
    const val OPENAI_RESPONSES = "openai-responses"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
}

internal fun LocalCanonicalContent.ToolCall.toLocalToolCall(): LocalToolCall =
    LocalToolCall(id, name, arguments, rawArguments)
