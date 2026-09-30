package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalToolCall
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import com.labteto.dshmobile.local.normalizeModelBaseUrl
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESPONSES_URL
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class OpenAiResponsesClient @Inject constructor(
    http: OkHttpClient,
    private val json: Json,
) {
    private val modelHttp = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    suspend fun completeStreaming(
        accessToken: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        planSharing: Boolean,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val openAiContract = usesOpenAiResponsesContract(baseUrl, planSharing)
        val payload = buildPayload(
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            planSharing = planSharing,
            includeEncryptedReasoning = openAiContract,
            enforceOpenAiToolSchema = openAiContract,
        )
        val request = Request.Builder()
            .url(responsesEndpoint(baseUrl, planSharing))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            runInterruptible { modelHttp.newCall(request).execute() }.use { response ->
                val requestId = response.header("x-request-id")
                    ?: response.header("openai-request-id")
                val retryAfterMs = parseRetryAfterMillis(response.header("Retry-After"))
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(ERROR_BODY_LIMIT)
                    throw httpError(
                        status = response.code,
                        body = body,
                        planSharing = planSharing,
                        requestId = requestId,
                        retryAfterMs = retryAfterMs,
                    )
                }
                val responseBody = response.body ?: throw LocalModelException(
                    code = "RESPONSES_STREAM_INCOMPLETE",
                    message = "Responses API 返回了空响应",
                    retryable = true,
                )
                var completedResponse: JsonObject? = null
                val streamedContent = StringBuilder()
                val streamedReasoning = StringBuilder()
                var totalBytes = 0
                responseBody.charStream().buffered().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        totalBytes += line.toByteArray(Charsets.UTF_8).size + 1
                        if (totalBytes > MAX_STREAM_BYTES) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = "Responses API 流式响应超过本机安全上限",
                                retryable = false,
                            )
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank() || data == "[DONE]") continue
                        val event = runCatching { json.parseToJsonElement(data).jsonObject }
                            .getOrElse { cause ->
                                throw LocalModelException(
                                    code = "RESPONSES_PROTOCOL_ERROR",
                                    message = "Responses API 返回了无法解析的数据帧",
                                    retryable = true,
                                    cause = cause,
                                )
                            }
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "response.output_text.delta" -> {
                                event["delta"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { delta ->
                                        streamedContent.append(delta)
                                        onDelta(LocalModelDelta(content = delta))
                                    }
                            }
                            "response.reasoning_text.delta",
                            "response.reasoning_summary_text.delta" -> {
                                event["delta"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { delta ->
                                        streamedReasoning.append(delta)
                                        onDelta(LocalModelDelta(reasoning = delta))
                                    }
                            }
                            "response.refusal.delta" -> {
                                event["delta"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let { delta ->
                                        streamedContent.append(delta)
                                        onDelta(LocalModelDelta(content = delta))
                                    }
                            }
                            "error" -> throw streamError(event, planSharing, requestId, retryAfterMs)
                            "response.failed" -> throw responseFailure(
                                event = event,
                                planSharing = planSharing,
                                requestId = requestId,
                                retryAfterMs = retryAfterMs,
                            )
                            "response.incomplete" -> throw LocalModelException(
                                code = "RESPONSES_INCOMPLETE",
                                message = incompleteReason(event),
                                retryable = false,
                            )
                            "response.completed" -> {
                                completedResponse = event["response"] as? JsonObject
                                    ?: error("Responses API completed 事件缺少 response")
                            }
                        }
                    }
                }
                val completed = completedResponse ?: throw LocalModelException(
                    code = "RESPONSES_STREAM_INCOMPLETE",
                    message = "Responses API 流在 response.completed 前结束",
                    retryable = true,
                )
                parseCompleted(
                    response = completed,
                    promptBreakdown = promptBreakdown,
                    streamedContent = streamedContent.toString(),
                    streamedReasoning = streamedReasoning.toString(),
                )
            }
        } catch (error: LocalModelException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LocalModelException(
                code = "MODEL_TIMEOUT",
                message = "Responses API 推理超时",
                retryable = true,
                cause = error,
            )
        } catch (error: IOException) {
            throw networkFailure(error)
        }
    }

    internal fun buildPayload(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        planSharing: Boolean = true,
        includeEncryptedReasoning: Boolean = true,
        enforceOpenAiToolSchema: Boolean = planSharing,
    ): JsonObject = buildJsonObject {
        put("model", model)
        responseInstructions(messages).takeIf(String::isNotBlank)?.let { put("instructions", it) }
        put("input", responseInput(messages))
        put("store", false)
        put("stream", true)
        if (includeEncryptedReasoning) {
            put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        }
        if (tools.isNotEmpty()) {
            put("tools", responseTools(tools, planSharing, enforceOpenAiToolSchema))
        }
        if (!planSharing) temperature?.let { put("temperature", it) }
        // ChatGPT plan sharing rejects sampling controls; API-key Responses keeps its own contract.
    }

    private fun responseInstructions(messages: List<JsonObject>): String =
        messages.asSequence()
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "system" }
            .mapNotNull { message -> responseInstructionText(message["content"]) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")

    private fun responseInstructionText(content: JsonElement?): String? =
        when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "input_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.joinToString("\n").takeIf(String::isNotBlank)
            else -> null
        }

    private fun responseInput(messages: List<JsonObject>): JsonArray = buildJsonArray {
        messages.forEach { message ->
            val role = message["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val raw = message[RESPONSES_OUTPUT_KEY] as? JsonArray
            if (raw != null) {
                raw.forEach(::add)
                val streamedFallback = (message["content"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                val rawHasFunctionCall = raw.any { item ->
                    (item as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call"
                }
                val rawHasAssistantText = raw.any { item ->
                    val obj = item as? JsonObject ?: return@any false
                    obj["type"]?.jsonPrimitive?.contentOrNull == "message" &&
                        responseMessageText((obj["content"] as? JsonArray) ?: JsonArray(emptyList())).isNotBlank()
                }
                if (
                    role != "assistant" ||
                    streamedFallback == null ||
                    rawHasFunctionCall ||
                    rawHasAssistantText
                ) {
                    return@forEach
                }
            }
            if (role == "tool") {
                val callId = message["tool_call_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", message["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                })
                return@forEach
            }
            if (role == "system") return@forEach
            if (role !in setOf("developer", "user", "assistant")) return@forEach
            val calls = if (role == "assistant") message["tool_calls"] as? JsonArray else null
            if (calls.isNullOrEmpty() || message["content"] !in listOf(null, JsonNull)) add(buildJsonObject {
                put("role", role)
                val content = message["content"]
                when (content) {
                    is JsonArray -> put("content", responseContent(content))
                    is JsonPrimitive -> put("content", content)
                    JsonNull, null -> put("content", "")
                    else -> put("content", content.toString())
                }
            })
            calls.orEmpty().forEach { element ->
                val call = element as? JsonObject ?: error("工具调用历史格式无效")
                val function = call["function"] as? JsonObject ?: error("工具调用历史缺少 function")
                add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call["id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: error("工具调用历史缺少 call_id"))
                    put("name", function["name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: error("工具调用历史缺少 name"))
                    put("arguments", function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
                })
            }
        }
    }

    private fun responseContent(content: JsonArray): JsonArray = buildJsonArray {
        content.forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> add(buildJsonObject {
                    put("type", "input_text")
                    put("text", obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                })
                "image_url" -> {
                    val source = obj["image_url"]
                    val url = when (source) {
                        is JsonPrimitive -> source.contentOrNull
                        is JsonObject -> source["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                    if (!url.isNullOrBlank()) add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", url)
                    })
                }
                "input_text", "input_image" -> add(obj)
            }
        }
    }

    private fun responseTools(
        tools: JsonArray,
        planSharing: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonArray {
        val functions = buildJsonArray {
            tools.forEachIndexed { index, element ->
                val source = element as? JsonObject
                    ?: throw invalidToolSchema(index, null, "工具定义必须是对象")
                source["type"]?.let { typeElement ->
                    val sourceType = typeElement as? JsonPrimitive
                    if (sourceType == null || !sourceType.isString || sourceType.content != "function") {
                        throw invalidToolSchema(index, null, "工具 type 必须是 function")
                    }
                }
                val function = source["function"] as? JsonObject
                    ?: throw invalidToolSchema(index, null, "工具定义缺少 function")
                val namePrimitive = function["name"] as? JsonPrimitive
                val name = namePrimitive
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: throw invalidToolSchema(index, null, "工具 name 必须是非空字符串")
                val description = when (val value = function["description"]) {
                    null, JsonNull -> "调用 $name 工具。"
                    is JsonPrimitive -> {
                        if (!value.isString) {
                            throw invalidToolSchema(index, name, "description 必须是字符串")
                        }
                        value.content.trim().takeIf(String::isNotEmpty) ?: "调用 $name 工具。"
                    }
                    else -> throw invalidToolSchema(index, name, "description 必须是字符串")
                }
                val strict = responseFunctionStrict(
                    index = index,
                    name = name,
                    value = function["strict"],
                )
                val parameters = normalizeResponseFunctionParameters(
                    index = index,
                    name = name,
                    value = function["parameters"],
                    strict = strict,
                    enforceOpenAiToolSchema = enforceOpenAiToolSchema,
                )
                add(buildJsonObject {
                    put("type", "function")
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                    put("strict", strict)
                })
            }
        }
        if (!planSharing || functions.isEmpty()) return functions

        // Sign in with ChatGPT 套餐共享要求 function/custom tools 位于 namespace
        // 或 additional_tools 中。namespace 自身的 description 是 Responses API 必填字段。
        // 只在套餐共享契约下包装，标准 Responses API 保持普通顶层 function tools。
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "namespace")
                put("name", CHATGPT_PLAN_TOOL_NAMESPACE)
                put("description", CHATGPT_PLAN_TOOL_NAMESPACE_DESCRIPTION)
                put("tools", functions)
            })
        }
    }

    private fun responseFunctionStrict(
        index: Int,
        name: String,
        value: JsonElement?,
    ): Boolean {
        if (value == null || value == JsonNull) {
            // Chat Completions defaults to non-strict. Responses may otherwise auto-normalize
            // into strict mode, so emit false explicitly to preserve existing tool semantics.
            return false
        }
        val primitive = value as? JsonPrimitive
            ?: throw invalidToolSchema(index, name, "strict 必须是布尔值")
        if (primitive.isString) {
            throw invalidToolSchema(index, name, "strict 必须是 JSON 布尔值，不能是字符串")
        }
        return primitive.content.toBooleanStrictOrNull()
            ?: throw invalidToolSchema(index, name, "strict 必须是 true 或 false")
    }

    private fun normalizeResponseFunctionParameters(
        index: Int,
        name: String,
        value: JsonElement?,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonObject {
        val source = when (value) {
            null, JsonNull -> emptyResponseFunctionParameters()
            is JsonObject -> value
            else -> throw invalidToolSchema(index, name, "parameters 必须是 JSON Schema 对象")
        }
        val type = source["type"]
        if (type != null && !schemaTypeContains(type, "object")) {
            throw invalidToolSchema(index, name, "parameters 根节点 type 必须是 object")
        }
        val normalized = buildJsonObject {
            source.forEach { (key, item) -> put(key, item) }
            if (type == null) put("type", "object")
            if ("properties" !in source) put("properties", buildJsonObject {})
        }
        if (enforceOpenAiToolSchema) {
            validateResponseParameterSchema(
                index = index,
                name = name,
                schema = normalized,
                path = "parameters",
                strict = strict,
                enforceOpenAiToolSchema = true,
                depth = 1,
            )
        }
        return normalized
    }

    private fun validateResponseParameterSchema(
        index: Int,
        name: String,
        schema: JsonObject,
        path: String,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
        depth: Int,
    ) {
        if (strict && enforceOpenAiToolSchema && depth > MAX_STRICT_SCHEMA_DEPTH) {
            throw invalidToolSchema(index, name, "$path 超过 OpenAI strict schema 的最大 10 层嵌套")
        }
        val properties = schema["properties"]?.let { element ->
            element as? JsonObject
                ?: throw invalidToolSchema(index, name, "$path.properties 必须是对象")
        }
        val required = schema["required"]?.let { element ->
            val array = element as? JsonArray
                ?: throw invalidToolSchema(index, name, "$path.required 必须是字符串数组")
            array.mapIndexed { requiredIndex, item ->
                val primitive = item as? JsonPrimitive
                if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
                    throw invalidToolSchema(
                        index,
                        name,
                        "$path.required[$requiredIndex] 必须是非空字符串",
                    )
                }
                primitive.content
            }.also { names ->
                if (strict && enforceOpenAiToolSchema && names.size != names.toSet().size) {
                    throw invalidToolSchema(index, name, "$path.required 不能包含重复字段")
                }
                if (strict && enforceOpenAiToolSchema && properties != null) {
                    val unknown = names.filterNot(properties::containsKey)
                    if (unknown.isNotEmpty()) {
                        throw invalidToolSchema(
                            index,
                            name,
                            "$path.required 引用了未声明字段：${unknown.joinToString()}",
                        )
                    }
                }
            }
        }

        validateAdditionalProperties(
            index,
            name,
            schema,
            path,
            strict,
            enforceOpenAiToolSchema,
        )

        if (strict && enforceOpenAiToolSchema && schemaDeclaresObject(schema)) {
            val propertyNames = properties?.keys.orEmpty()
            val requiredNames = required?.toSet().orEmpty()
            val missing = propertyNames.filterNot(requiredNames::contains)
            if (missing.isNotEmpty()) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 strict=true 时所有 properties 都必须列入 required，缺少：${missing.joinToString()}",
                )
            }
        }

        STRICT_UNSUPPORTED_SCHEMA_KEYWORDS.firstOrNull(schema::containsKey)?.let { keyword ->
            if (strict && enforceOpenAiToolSchema) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 OpenAI strict=true 时不支持 JSON Schema 关键字 $keyword",
                )
            }
        }

        properties?.forEach { (propertyName, child) ->
            val childSchema = child as? JsonObject
                ?: throw invalidToolSchema(
                    index,
                    name,
                    "$path.properties.$propertyName 必须是 JSON Schema 对象",
                )
            validateResponseParameterSchema(
                index,
                name,
                childSchema,
                "$path.properties.$propertyName",
                strict,
                enforceOpenAiToolSchema,
                depth + 1,
            )
        }

        schema["items"]?.let { items ->
            val itemSchema = items as? JsonObject
            if (itemSchema != null) {
                validateResponseParameterSchema(
                    index,
                    name,
                    itemSchema,
                    "$path.items",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            } else if (strict && enforceOpenAiToolSchema) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path.items 在 strict=true 时必须是 JSON Schema 对象",
                )
            }
        }

        schema["anyOf"]?.let { anyOfElement ->
            val anyOf = anyOfElement as? JsonArray
                ?: throw invalidToolSchema(index, name, "$path.anyOf 必须是数组")
            anyOf.forEachIndexed { anyOfIndex, child ->
                val childSchema = child as? JsonObject
                    ?: throw invalidToolSchema(
                        index,
                        name,
                        "$path.anyOf[$anyOfIndex] 必须是 JSON Schema 对象",
                    )
                validateResponseParameterSchema(
                    index,
                    name,
                    childSchema,
                    "$path.anyOf[$anyOfIndex]",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            }
        }

        schema["\$defs"]?.let { definitionsElement ->
            val definitions = definitionsElement as? JsonObject
                ?: throw invalidToolSchema(index, name, "$path.\$defs 必须是对象")
            definitions.forEach { (definitionName, child) ->
                val childSchema = child as? JsonObject
                    ?: throw invalidToolSchema(
                        index,
                        name,
                        "$path.\$defs.$definitionName 必须是 JSON Schema 对象",
                    )
                validateResponseParameterSchema(
                    index,
                    name,
                    childSchema,
                    "$path.\$defs.$definitionName",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            }
        }
    }

    private fun validateAdditionalProperties(
        index: Int,
        name: String,
        schema: JsonObject,
        path: String,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ) {
        val additional = schema["additionalProperties"]
        if (additional != null && additional != JsonNull) {
            val validShape = when (additional) {
                is JsonObject -> true
                is JsonPrimitive -> !additional.isString &&
                    additional.content.toBooleanStrictOrNull() != null
                else -> false
            }
            if (!validShape) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path.additionalProperties 必须是布尔值或 JSON Schema 对象",
                )
            }
        }
        if (strict && enforceOpenAiToolSchema && schemaDeclaresObject(schema)) {
            val disabled = (additional as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.content
                ?.toBooleanStrictOrNull() == false
            if (!disabled) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 strict=true 时必须设置 additionalProperties=false",
                )
            }
        }
    }

    private fun schemaDeclaresObject(schema: JsonObject): Boolean =
        schema["properties"] is JsonObject || schemaTypeContains(schema["type"], "object")

    private fun schemaTypeContains(type: JsonElement?, expected: String): Boolean = when (type) {
        is JsonPrimitive -> type.isString && type.content == expected
        is JsonArray -> type.any { item ->
            val primitive = item as? JsonPrimitive
            primitive != null && primitive.isString && primitive.content == expected
        }
        else -> false
    }

    private fun emptyResponseFunctionParameters(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }

    private fun invalidToolSchema(
        index: Int,
        name: String?,
        detail: String,
    ): LocalModelException = LocalModelException(
        code = "RESPONSES_TOOL_SCHEMA_INVALID",
        message = buildString {
            append("Responses 工具定义无效：第 ").append(index + 1).append(" 项")
            name?.let { append("（").append(it).append("）") }
            append(detail)
        },
        retryable = false,
    )

    internal fun responseMessageText(parts: JsonArray): String = buildString {
        parts.forEach { part ->
            val p = part as? JsonObject ?: return@forEach
            when (p["type"]?.jsonPrimitive?.contentOrNull) {
                "output_text" -> append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "refusal" -> append(p["refusal"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
    }

    internal fun parseCompleted(
        response: JsonObject,
        promptBreakdown: TokenPromptBreakdown,
        streamedContent: String = "",
        streamedReasoning: String = "",
    ): LocalModelReply {
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        output.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    content.append(responseMessageText((obj["content"] as? JsonArray) ?: JsonArray(emptyList())))
                }
                "reasoning" -> {
                    (obj["summary"] as? JsonArray).orEmpty().forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        p["text"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
                    }
                }
                "function_call" -> {
                    val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: UUID.randomUUID().toString()
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val rawArguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    val arguments = runCatching {
                        json.parseToJsonElement(rawArguments) as? JsonObject
                    }.getOrNull() ?: JsonObject(emptyMap())
                    toolCalls += LocalToolCall(callId, name, arguments, rawArguments)
                }
            }
        }
        val settledContent = content.toString().takeIf(String::isNotBlank)
            ?: streamedContent.takeIf(String::isNotBlank)
        val settledReasoning = reasoning.toString().takeIf(String::isNotBlank)
            ?: streamedReasoning.takeIf(String::isNotBlank)
        val message = buildJsonObject {
            put("role", "assistant")
            if (settledContent != null) put("content", settledContent) else put("content", JsonNull)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { call ->
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
            put(RESPONSES_OUTPUT_KEY, output)
        }
        return LocalModelReply(
            message = message,
            content = settledContent,
            reasoning = settledReasoning,
            toolCalls = toolCalls,
            usage = parseUsage(response["usage"] as? JsonObject),
            requestId = response["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            promptBreakdown = promptBreakdown,
        )
    }

    private fun parseUsage(usage: JsonObject?): DeepSeekTokenUsage {
        if (usage == null) return DeepSeekTokenUsage(reported = false)
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val cached = (usage["input_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return DeepSeekTokenUsage(
            promptTokens = input.coerceAtLeast(0L),
            cacheHitTokens = cached.coerceAtLeast(0L),
            cacheMissTokens = (input - cached).coerceAtLeast(0L),
            completionTokens = output.coerceAtLeast(0L),
            reasoningTokens = reasoning.coerceAtLeast(0L),
            reported = true,
        )
    }

    private fun responseFailure(
        event: JsonObject,
        planSharing: Boolean,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val response = event["response"] as? JsonObject
        val error = response?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown_error"
        val param = error?.get("param")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Responses API 请求失败"
        return if (planSharing) {
            structuredResponseError(code, param, message, requestId, retryAfterMs)
        } else {
            standardStreamError("RESPONSES_FAILED", code, param, message, requestId, retryAfterMs)
        }
    }

    internal fun streamError(
        event: JsonObject,
        planSharing: Boolean,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException {
        val nested = event["error"] as? JsonObject
        val code = event["code"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("code")?.jsonPrimitive?.contentOrNull
            ?: "unknown_error"
        val param = event["param"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("param")?.jsonPrimitive?.contentOrNull
        val detail = event["message"]?.jsonPrimitive?.contentOrNull
            ?: nested?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Responses API 流式请求失败"
        return if (planSharing) {
            structuredResponseError(code, param, detail, requestId, retryAfterMs)
        } else {
            standardStreamError("RESPONSES_STREAM", code, param, detail, requestId, retryAfterMs)
        }
    }

    private fun standardStreamError(
        prefix: String,
        code: String,
        param: String?,
        detail: String,
        requestId: String?,
        retryAfterMs: Long?,
    ): LocalModelException = LocalModelException(
        code = "${prefix}_$code",
        message = detail,
        retryable = code in setOf("server_error", "server_overloaded", "rate_limit_exceeded"),
        providerRetryAfterMs = retryAfterMs,
        requestId = requestId,
        providerCode = code,
        providerParam = param,
    )

    private fun incompleteReason(event: JsonObject): String {
        val response = event["response"] as? JsonObject
        val details = response?.get("incomplete_details") as? JsonObject
        val reason = details?.get("reason")?.jsonPrimitive?.contentOrNull
        return if (reason.isNullOrBlank()) "Responses API 未完整完成本次请求" else "Responses API 未完整完成：$reason"
    }

    internal fun httpError(
        status: Int,
        body: String,
        planSharing: Boolean,
        requestId: String? = null,
        retryAfterMs: Long? = null,
    ): LocalModelException {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val remoteCode = error?.get("code")?.jsonPrimitive?.contentOrNull
        val remoteParam = error?.get("param")?.jsonPrimitive?.contentOrNull
        val detail = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root?.get("detail")?.jsonPrimitive?.contentOrNull
            ?: root?.get("message")?.jsonPrimitive?.contentOrNull

        if (planSharing && remoteCode != null) {
            return structuredResponseError(
                code = remoteCode,
                param = remoteParam,
                detail = detail,
                requestId = requestId,
                retryAfterMs = retryAfterMs,
                statusOverride = status,
            )
        }

        if (planSharing) {
            val message = when (status) {
                401 -> "ChatGPT 套餐请求未通过身份或授权校验。请确认当前账户仍已授权套餐使用；若持续出现，再重新连接账户。"
                403 -> "ChatGPT 套餐请求被权限、策略或可用地区规则拒绝。请检查 ChatGPT 账户与当前网络环境。"
                503 -> "ChatGPT 套餐直连服务暂时不可用，777 会按有限次数退避重试。"
                else -> detail ?: "Responses API 请求失败（HTTP $status）"
            }
            return LocalModelException(
                code = when (status) {
                    401 -> "CHATGPT_PLAN_ADMISSION_401"
                    403 -> "CHATGPT_PLAN_ADMISSION_403"
                    503 -> "CHATGPT_PLAN_ROUTE_UNAVAILABLE"
                    else -> "MODEL_HTTP_$status"
                },
                message = detail?.takeIf(String::isNotBlank) ?: message,
                retryable = status == 408 || status == 429 || status >= 500,
                status = status,
                providerRetryAfterMs = retryAfterMs,
                requestId = requestId,
            )
        }

        return LocalModelException(
            code = remoteCode?.let { "RESPONSES_HTTP_$it" }
                ?: if (status == 429) "MODEL_HTTP_429" else "MODEL_HTTP_$status",
            message = detail ?: "Responses API 请求失败（HTTP $status）",
            retryable = status == 408 || status == 429 || status >= 500,
            status = status,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = remoteCode,
            providerParam = remoteParam,
        )
    }

    private fun structuredResponseError(
        code: String,
        param: String?,
        detail: String?,
        requestId: String?,
        retryAfterMs: Long?,
        statusOverride: Int? = null,
    ): LocalModelException {
        val knownStatus = statusOverride ?: when (code) {
            "subscription_sharing_user_not_eligible",
            "subscription_sharing_route_not_supported",
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" -> 403
            "subscription_sharing_usage_limit_exceeded" -> 429
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable" -> 503
            "subscription_sharing_unsupported_capability" -> 400
            "subscription_sharing_invalid_user" -> 401
            else -> null
        }
        val mapped = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> Triple(
                "CHATGPT_PLAN_LIMIT_REACHED",
                "ChatGPT 套餐用量请求达到当前限制。你的套餐总额度可能仍有剩余，也可能是此应用的单独限制；请在 ChatGPT「用量」中查看实际限制。",
                false,
            )
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable" -> Triple(
                "CHATGPT_PLAN_USAGE_UNAVAILABLE",
                "ChatGPT 暂时无法确认套餐可用量，777 会保留登录状态并按有限次数退避重试。",
                true,
            )
            "subscription_sharing_user_not_eligible" -> Triple(
                "CHATGPT_PLAN_USER_NOT_ELIGIBLE",
                "当前 ChatGPT 用户、工作区或策略暂不允许共享套餐用量。",
                false,
            )
            "subscription_sharing_unsupported_capability" -> Triple(
                "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
                buildString {
                    append("当前 ChatGPT 套餐共享请求包含暂不支持的能力")
                    if (!param.isNullOrBlank()) append("：").append(param)
                    append("。请调整模型请求后再试。")
                },
                false,
            )
            "subscription_sharing_route_not_supported" -> Triple(
                "CHATGPT_PLAN_ROUTE_NOT_SUPPORTED",
                "当前请求路由不支持 ChatGPT 套餐共享，请检查 Responses API 调用路径。",
                false,
            )
            "subscription_sharing_invalid_user" -> Triple(
                "CHATGPT_PLAN_INVALID_USER",
                "ChatGPT 订阅者上下文未通过验证。若该问题持续出现，请重新连接账户。",
                false,
            )
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context" -> Triple(
                "CHATGPT_PLAN_PERMISSION_CONTEXT_INVALID",
                "当前 ChatGPT 授权上下文不允许这次套餐调用，请重新检查账户授权。",
                false,
            )
            else -> Triple(
                "RESPONSES_FAILED_$code",
                detail ?: "Responses API 请求失败",
                code in setOf("server_error", "server_overloaded", "rate_limit_exceeded"),
            )
        }
        return LocalModelException(
            code = mapped.first,
            message = mapped.second,
            retryable = mapped.third,
            status = knownStatus,
            providerRetryAfterMs = retryAfterMs,
            requestId = requestId,
            providerCode = code,
            providerParam = param,
        )
    }

    internal fun responsesEndpoint(baseUrl: String, planSharing: Boolean): String {
        if (planSharing) return CHATGPT_RESPONSES_URL
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/responses")) clean else "$clean/responses"
    }

    internal fun shouldIncludeEncryptedReasoning(baseUrl: String, planSharing: Boolean): Boolean =
        usesOpenAiResponsesContract(baseUrl, planSharing)

    internal fun usesOpenAiResponsesContract(baseUrl: String, planSharing: Boolean): Boolean {
        if (planSharing) return true
        return runCatching {
            java.net.URI(normalizeModelBaseUrl(baseUrl)).host.equals("api.openai.com", ignoreCase = true)
        }.getOrDefault(false)
    }

    internal fun networkFailure(error: IOException): LocalModelException {
        val detail = error.message.orEmpty().lowercase()
        val interrupted = listOf(
            "connection abort",
            "connection reset",
            "broken pipe",
            "socket closed",
            "stream was reset",
            "stream reset",
        ).any(detail::contains)
        return LocalModelException(
            code = "MODEL_NETWORK",
            message = if (interrupted) {
                "Responses API 流式连接中断，请检查当前网络后恢复请求。"
            } else {
                "Responses API 网络连接失败，请检查当前网络后重试。"
            },
            retryable = true,
            cause = error,
        )
    }

    internal fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0L) * 1_000L
        }
        return runCatching {
            val atMillis = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (atMillis - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    companion object {
        const val RESPONSES_OUTPUT_KEY = "_dsh_responses_output"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 8_000
        private const val CHATGPT_PLAN_TOOL_NAMESPACE = "local"
        private const val CHATGPT_PLAN_TOOL_NAMESPACE_DESCRIPTION =
            "777 本机 Harness 工具，用于文件、终端、网页、任务、设备与已启用扩展能力。"
        private const val MAX_STRICT_SCHEMA_DEPTH = 10
        private val STRICT_UNSUPPORTED_SCHEMA_KEYWORDS = setOf(
            "allOf",
            "not",
            "dependentRequired",
            "dependentSchemas",
            "if",
            "then",
            "else",
        )
    }
}
