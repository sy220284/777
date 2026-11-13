package com.labteto.dshmobile.local.model


import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.estimatePromptBreakdown
import com.labteto.dshmobile.local.model.chatgpt.CHATGPT_RESPONSES_URL
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.labteto.dshmobile.harness.agent.AgentEagerToolContext
import com.labteto.dshmobile.harness.agent.AgentToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.serialization.json.intOrNull
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
        // Long-lived Responses streams must not be cancelled solely for total elapsed time.
        // readTimeout still bounds idle sockets; the coroutine cancels the call when work stops.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun completeStreaming(
        accessToken: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double? = null,
        reasoningEffort: String? = null,
        planSharing: Boolean,
        allowImageGeneration: Boolean = false,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
        onDelta: (LocalModelDelta) -> Unit = {},
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val promptBreakdown = estimatePromptBreakdown(messages, tools)
        val openAiContract = usesOpenAiResponsesContract(baseUrl, planSharing)
        val payload = buildPayload(
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
            planSharing = planSharing,
            includeEncryptedReasoning = openAiContract,
            enforceOpenAiToolSchema = openAiContract,
            promptCacheComparisonResponseId = promptCacheComparisonResponseId,
            promptCacheKey = promptCacheKey,
            promptCacheTtl = promptCacheTtl,
            enableImageGenerationTool = resolveOpenAiImageGenerationToolEnabled(
                baseUrl = baseUrl,
                model = model,
                planSharing = planSharing,
                requested = allowImageGeneration,
            ),
        )
        validateRequestPayload(payload, planSharing)
        val admissionTracker = LocalModelAdmissionTracker()
        val request = Request.Builder()
            .url(responsesEndpoint(baseUrl, planSharing))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .apply {
                if (planSharing) header("x-openai-internal-codex-responses-lite", "true")
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA).withModelAdmissionTracking(admissionTracker))
            .build()
        var admittedRequestId: String? = null
        val eager = currentCoroutineContext()[AgentEagerToolContext]
        val advertisedToolNames = tools.mapNotNull { schema ->
            ((schema as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()
        val eagerItems = sortedMapOf<Int, JsonObject>()
        val eagerCalls = linkedMapOf<String, LocalToolCall>()
        fun interruptedEagerReply(): LocalModelReply {
            val replay = parseCompleted(buildJsonObject {
                put("status", "completed"); put("id", admittedRequestId.orEmpty())
                put("output", JsonArray(eagerItems.values.toList()))
            }, promptBreakdown)
            return replay.copy(completionReason = LocalModelCompletionReason.INTERRUPTED,
                rawStopReason = "stream_interrupted_after_tool_item_completion",
                message = JsonObject(replay.message - RESPONSES_OUTPUT_KEY))
        }
        suspend fun acceptCompletedItem(event: JsonObject) {
            if (eager == null || event["type"]?.jsonPrimitive?.contentOrNull != "response.output_item.done") return
            val item = event["item"] as? JsonObject ?: return
            if (item["type"]?.jsonPrimitive?.contentOrNull != "function_call" ||
                item["status"]?.jsonPrimitive?.contentOrNull != "completed") return
            val index = event["output_index"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it >= 0 } ?: throw responseProtocolError("完成工具项缺少输出序号")
            val call = parseCompleted(buildJsonObject {
                put("status", "completed"); put("output", JsonArray(listOf(item)))
            }, promptBreakdown).toolCalls.single()
            if (call.name !in advertisedToolNames) throw responseProtocolError("完成工具项不属于本次请求的工具表")
            eagerItems[index]?.let { previous ->
                if (previous != item) throw responseProtocolError("完成工具项内容变化"); return
            }
            if (call.id in eagerCalls || (eagerItems.isNotEmpty() && index <= eagerItems.lastKey()))
                throw responseProtocolError("完成工具项编号重复或顺序变化")
            // Store the exact admitted item before offer: interruption must retain its identity.
            eagerItems[index] = item; eagerCalls[call.id] = call
            eager.offer(AgentToolCall(call.id, call.name, call.arguments, call.rawArguments))
        }

        try {
            withCancellableModelResponse(modelHttp.newCall(request), admissionTracker) { response ->
                val requestId = response.header("x-request-id")
                    ?: response.header("openai-request-id")
                val retryAfterMs = parseRetryAfterMillis(response.header("Retry-After"))
                if (!response.isSuccessful) {
                    val body = response.readBoundedModelError(ERROR_BODY_LIMIT)
                    throw httpError(
                        status = response.code,
                        body = body,
                        planSharing = planSharing,
                        requestId = requestId,
                        retryAfterMs = retryAfterMs,
                    )
                }
                admissionTracker.markAdmitted()
                admittedRequestId = requestId
                val responseBody = response.body ?: throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = requestId,
                    detail = "Responses API 返回了空响应",
                )
                var completedResponse: JsonObject? = null
                var reportedUsage: JsonObject? = null
                val streamed = OpenAiResponsesStreamSnapshot()
                                // Response 生命周期由 withCancellableModelResponse 统一关闭；这里不要单独 use(reader)，
                // 否则成功终态后的 reader.close() 异常仍可能把成功请求翻成失败。
                val reader = ModelStreamLineReader(responseBody.charStream().buffered(), MAX_SSE_LINE_CHARS, MAX_STREAM_BYTES)
                run {
                    while (true) {
                        val line = try {
                            reader.readLine()
                        } catch (failure: ModelStreamLimitException) {
                            throw LocalModelException(
                                code = "MODEL_RESPONSE_TOO_LARGE",
                                message = if (failure.kind == ModelStreamLimitKind.LINE) {
                                    "Responses API 流式响应单行超过安全上限"
                                } else {
                                    "Responses API 流式响应超过本机安全上限"
                                },
                                retryable = false,
                            )
                        } catch (failure: java.io.IOException) {
                            if (streamed.content.isBlank() && eagerCalls.isEmpty()) throw failure
                            null
                        } ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank() || data == "[DONE]") continue
                        val event = runCatching { json.parseToJsonElement(data).jsonObject }
                            .getOrElse { cause ->
                                throw streamInterruptedAfterAdmission(
                                    planSharing = planSharing,
                                    requestId = requestId,
                                    detail = "Responses API 返回了无法解析的数据帧",
                                    cause = cause,
                                )
                            }
                        ((event["response"] as? JsonObject)?.get("usage") as? JsonObject)?.let { reportedUsage = it }
                        streamed.record(event)?.let { onDelta(it) }
                        acceptCompletedItem(event)
                        when (event["type"]?.jsonPrimitive?.contentOrNull) {
                            "error" -> throw streamError(event, planSharing, requestId, retryAfterMs)
                            "response.failed" -> throw responseFailure(
                                event = event,
                                planSharing = planSharing,
                                requestId = requestId,
                                retryAfterMs = retryAfterMs,
                            )
                            "response.incomplete" -> {
                                val partial = event["response"] as? JsonObject
                                    ?: throw responseProtocolError("incomplete 事件缺少 response")
                                completedResponse = JsonObject(partial + ("status" to JsonPrimitive("incomplete")))
                                break
                            }
                            "response.completed" -> {
                                completedResponse = event["response"] as? JsonObject
                                    ?: throw responseProtocolError("completed 事件缺少 response")
                                // completed 是成功终态；不要再读取 EOF，避免终态后的连接收尾异常触发整轮重试。
                                break
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val completed = completedResponse ?: if (streamed.content.isNotBlank() || eagerCalls.isNotEmpty()) buildJsonObject {
                    put("status", "interrupted")
                    reportedUsage?.let { put("usage", it) }
                    put("id", requestId.orEmpty())
                } else throw streamInterruptedAfterAdmission(
                    planSharing = planSharing, requestId = requestId,
                    detail = "Responses API 流在终态前结束",
                )
                val settledReply = parseCompleted(
                    response = streamed.settledResponse(completed),
                    promptBreakdown = promptBreakdown,
                    streamedContent = streamed.content,
                    streamedReasoning = streamed.reasoningText,
                )
                if (eagerCalls.isNotEmpty() && (settledReply.completionReason != LocalModelCompletionReason.COMPLETED ||
                        settledReply.toolCalls.take(eagerCalls.size) != eagerCalls.values.toList())) {
                    interruptedEagerReply()
                } else settledReply
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: LocalModelException) {
            if (eagerCalls.isNotEmpty()) return@withContext interruptedEagerReply()
            // In-stream failures can arrive after real inference has started on any Responses route.
            // Preserve diagnostics but never blindly replay the whole accepted request.
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED && error.retryable) {
                throw error.withoutReplayAfterAdmission()
            }
            throw error
        } catch (error: SocketTimeoutException) {
            if (eagerCalls.isNotEmpty()) return@withContext interruptedEagerReply()
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = admittedRequestId,
                    detail = "Responses API 流式响应超时",
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_TIMEOUT",
                detail = "Responses API 推理超时",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        } catch (error: IOException) {
            if (eagerCalls.isNotEmpty()) return@withContext interruptedEagerReply()
            if (admissionTracker.snapshot() == LocalModelAdmissionState.ADMITTED) {
                throw streamInterruptedAfterAdmission(
                    planSharing = planSharing,
                    requestId = admittedRequestId,
                    detail = "Responses API 流式连接中断",
                    cause = error,
                )
            }
            throw modelTransportFailure(
                code = "MODEL_NETWORK",
                detail = "Responses API 网络请求失败：${error.message ?: "网络异常"}",
                tracker = admissionTracker,
                requestId = admittedRequestId,
                cause = error,
            )
        }
    }

    internal fun supportsOpenAiImageGenerationTool(model: String): Boolean =
        supportsOpenAiImageGenerationModel(model)

    internal fun validateRequestPayload(payload: JsonObject, planSharing: Boolean = false) {
        val input = payload["input"] as? JsonArray
        val hasExecutableInput = input?.any { element ->
            val item = element as? JsonObject
            item != null && item["type"]?.jsonPrimitive?.contentOrNull != "additional_tools" &&
                item["role"]?.jsonPrimitive?.contentOrNull != "developer"
        } == true
        if (!hasExecutableInput) {
            throw LocalModelException(
                code = "RESPONSES_INPUT_REQUIRED",
                message = "Responses API 请求缺少 input；后台任务必须提供至少一条实际输入，不能只发送 instructions。",
                retryable = false,
            )
        }
        if (!planSharing) return
        if ((payload["tools"] as? JsonArray)?.isNotEmpty() == true) {
            throw LocalModelException(
                code = "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
                message = "ChatGPT 套餐 Responses Lite 只接受 input.additional_tools，禁止顶层 tools",
                retryable = false,
            )
        }
        val unsupportedTools = setOf(
            "image_generation", "file_search", "code_interpreter", "computer",
            "mcp", "tool_search", "programmatic_tool_calling",
        )
        val unsupported = input.mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "additional_tools" }
            .flatMap { (it["tools"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull in unsupportedTools }
        if (unsupported != null) throw LocalModelException(
            code = "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
            message = "ChatGPT 套餐授权不支持请求的 OpenAI 托管工具，请使用已配置的本地工具或其他服务。",
            retryable = false,
        )
        val hasFileId = input.mapNotNull { it as? JsonObject }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .any { it["type"]?.jsonPrimitive?.contentOrNull == "input_file" && "file_id" in it }
        if (hasFileId) throw LocalModelException(
            code = "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
            message = "ChatGPT 套餐授权不支持 Files 上传接口的 file_id，请直接附加本地文件。",
            retryable = false,
        )
    }

    internal fun streamInterruptedAfterAdmission(
        planSharing: Boolean,
        requestId: String?,
        detail: String,
        cause: Throwable? = null,
    ): LocalModelException =
        if (planSharing) {
            LocalModelException(
                code = "CHATGPT_PLAN_STREAM_INTERRUPTED",
                message = "$detail。请求已经进入 Responses 流，为避免重复推理和重复套餐消耗，777 不会自动重放本轮。",
                retryable = false,
                cause = cause,
                requestId = requestId,
                providerCode = "stream_interrupted_after_admission",
                admissionState = LocalModelAdmissionState.ADMITTED,
                continuationEligible = true,
            )
        } else {
            modelPostAdmissionFailure(
                code = "RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION",
                detail = detail,
                requestId = requestId,
                cause = cause,
            )
        }

    internal fun buildPayload(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        temperature: Double?,
        reasoningEffort: String? = null,
        planSharing: Boolean = true,
        includeEncryptedReasoning: Boolean = true,
        enforceOpenAiToolSchema: Boolean = planSharing,
        promptCacheComparisonResponseId: String? = null,
        promptCacheKey: String? = null,
        promptCacheTtl: String? = null,
        enableImageGenerationTool: Boolean = false,
    ): JsonObject = buildJsonObject {
        // Do not trust caller opt-in: plan sharing never accepts image_generation.
        val nativeImageToolEnabled = enableImageGenerationTool && !planSharing
        put("model", model)
        val systemInstructions = responseInstructions(messages)
        if (!planSharing) systemInstructions.takeIf(String::isNotBlank)?.let { put("instructions", it) }
        put("store", false)
        put("stream", true)
        if (planSharing || reasoningEffort != null) {
            put("reasoning", buildJsonObject {
                if (planSharing) put("context", "all_turns")
                reasoningEffort?.let { put("effort", it) }
            })
        }
        if (planSharing) put("parallel_tool_calls", false)
        if (!planSharing) {
            promptCacheKey?.trim()?.takeIf(String::isNotBlank)?.let { key ->
                put("prompt_cache_key", key.take(MAX_PROMPT_CACHE_KEY_CHARS))
            }
        }
        val cacheComparison = promptCacheComparisonResponseId
            ?.takeIf { !planSharing }
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val cacheTtl = promptCacheTtl
            ?.trim()
            ?.takeIf { !planSharing && it in SUPPORTED_PROMPT_CACHE_TTLS }
        if (cacheComparison != null || cacheTtl != null) {
            put("prompt_cache_options", buildJsonObject {
                cacheComparison?.let { put("comparison_response_id", it.take(512)) }
                cacheTtl?.let {
                    put("mode", "implicit")
                    put("ttl", it)
                }
            })
        }
        if (includeEncryptedReasoning) {
            put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        }
        val adaptedTools = responseTools(tools, planSharing, enforceOpenAiToolSchema)
        // Codex Responses Lite accepts executable namespaces as a developer input item.
        // Top-level tools belongs exclusively to the standard API-key Responses route.
        if (planSharing && adaptedTools.isEmpty() && explicitNativeWebSearchRequested(messages)) {
            throw LocalModelException(
                code = "CHATGPT_PLAN_UNSUPPORTED_CAPABILITY",
                message = "套餐共享 Responses Lite 不支持直接请求托管 web_search，请通过已配置的本地搜索工具执行",
                retryable = false,
            )
        }
        put("input", buildJsonArray {
            if (planSharing && adaptedTools.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "additional_tools")
                    put("id", "at_" + UUID.nameUUIDFromBytes(adaptedTools.toString().toByteArray(Charsets.UTF_8)))
                    put("role", "developer")
                    put("tools", adaptedTools)
                })
            }
            if (planSharing && systemInstructions.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "message")
                    put("id", "msg_" + UUID.nameUUIDFromBytes(systemInstructions.toByteArray(Charsets.UTF_8)))
                    put("role", "developer")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", systemInstructions)
                        })
                    })
                })
            }
            responseInput(messages).forEach(::add)
        })
        if (!planSharing && (adaptedTools.isNotEmpty() || nativeImageToolEnabled)) {
            put("tools", buildJsonArray {
                adaptedTools.forEach(::add)
                if (nativeImageToolEnabled) {
                    add(buildJsonObject {
                        put("type", "image_generation")
                        if (explicitImageEditRequested(messages)) put("action", "edit")
                    })
                }
            })
        }
        // Explicit image commands must invoke the actual built-in tool, not return a text-only workaround.
        // Only force when the current Responses request already passed native-tool eligibility.
        if (nativeImageToolEnabled && explicitImageGenerationRequested(messages)) {
            put("tool_choice", buildJsonObject { put("type", LOCAL_NATIVE_TOOL_IMAGE_GENERATION) })
        }
        if (!planSharing) temperature?.let { put("temperature", it) }
        // ChatGPT plan sharing rejects sampling controls; API-key Responses keeps its own contract.
    }

    /** Force edits only when the latest turn includes an image and an explicit editing command. */
    private fun explicitImageEditRequested(messages: List<JsonObject>): Boolean {
        if (!explicitImageGenerationRequested(messages)) return false
        val user = messages.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
            ?: return false
        val parts = user["content"] as? JsonArray ?: return false
        val hasImage = parts.any { part ->
            (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull in
                setOf("image_url", "input_image")
        }
        val text = parts.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull in setOf("text", "input_text"))
                obj["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString(" ")
        return hasImage && Regex("""修改|编辑|重绘|改成|edit|modify|retouch|redraw""",
            RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private fun explicitImageGenerationRequested(messages: List<JsonObject>): Boolean {
        val user = messages.lastOrNull {
            it["role"]?.jsonPrimitive?.contentOrNull == "user"
        } ?: return false
        val text = when (val content = user["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if (obj["type"]?.jsonPrimitive?.contentOrNull in setOf("text", "input_text")) {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else null
            }.joinToString("\n")
            else -> ""
        }
        return isExplicitImageGenerationRequest(text)
    }

    private fun explicitNativeWebSearchRequested(messages: List<JsonObject>): Boolean {
        val latest = messages.lastOrNull() ?: return false
        if (latest["role"]?.jsonPrimitive?.contentOrNull != "user") return false
        val text = when (val content = latest["content"]) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                if (obj["type"]?.jsonPrimitive?.contentOrNull in setOf("text", "input_text"))
                    obj["text"]?.jsonPrimitive?.contentOrNull else null
            }.joinToString(" ")
            else -> ""
        }.trim()
        if (text.isEmpty() || text.length > 2_048 ||
            text.startsWith("不要") || text.startsWith("别")) return false
        val command = text.replaceFirst(
            Regex("""^(?:请|帮我|帮忙|麻烦|现在|先|给我)\s*"""), "",
        )
        return Regex(
            """^(?:联网搜索|上网搜索|网上搜索|网络搜索|联网查|上网查|网上查|搜索一下|搜一下|查一下最新|查找最新|search the web|browse the web)""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(command)
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
                val canonicalText = (message["content"] as? JsonPrimitive)?.contentOrNull
                var replacedText = false
                raw.forEach { item ->
                    val obj = item as? JsonObject
                    if (canonicalText != null && obj?.get("type")?.jsonPrimitive?.contentOrNull == "message" &&
                        obj["role"]?.jsonPrimitive?.contentOrNull == "assistant"
                    ) {
                        if (!replacedText) {
                            add(JsonObject(obj + ("content" to buildJsonArray {
                                add(buildJsonObject { put("type", "output_text"); put("text", canonicalText) })
                            })))
                            replacedText = true
                        }
                    } else add(item)
                }
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
                        if (source is JsonObject) source["detail"]?.let { put("detail", it) }
                    })
                }
                "input_text", "input_image", "input_file" -> add(obj)
                else -> throw responseProtocolError("不支持的输入内容类型：${obj["type"]}")
            }
        }
    }

    private fun responseTools(
        tools: JsonArray,
        planSharing: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonArray = OpenAiResponsesToolAdapter.adapt(
        tools = tools,
        planSharing = planSharing,
        enforceOpenAiToolSchema = enforceOpenAiToolSchema,
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
        val rawStopReason = (response["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: response["status"]?.jsonPrimitive?.contentOrNull
        var completionReason = when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "incomplete" -> when (rawStopReason) {
                "content_filter" -> LocalModelCompletionReason.REFUSED
                "max_output_tokens" -> LocalModelCompletionReason.OUTPUT_LIMIT
                else -> LocalModelCompletionReason.INTERRUPTED
            }
            "interrupted", "failed", "cancelled", "canceled", "queued", "in_progress" -> LocalModelCompletionReason.INTERRUPTED
            null, "completed" -> LocalModelCompletionReason.COMPLETED
            else -> throw responseProtocolError("未知响应状态，不能接纳工具调用")
        }
        if ("output" in response && response["output"] !is JsonArray) throw responseProtocolError("output 必须是数组")
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val allowStreamFallback = response["status"]?.jsonPrimitive?.contentOrNull != "completed" || output.isEmpty()
        if (output.any { item ->
                val objectValue = item as? JsonObject
                (objectValue?.get("content") as? JsonArray).orEmpty().any { part ->
                    (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "refusal"
                }
            }) completionReason = LocalModelCompletionReason.REFUSED
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<LocalToolCall>()
        val visibleParts = mutableListOf<JsonObject>()
        val webSources = linkedMapOf<String, String>()
        var generatedImageCount = 0
        output.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    val parts = (obj["content"] as? JsonArray) ?: JsonArray(emptyList())
                    parts.forEach partLoop@ { part ->
                        val visible = part as? JsonObject ?: return@partLoop
                        when (visible["type"]?.jsonPrimitive?.contentOrNull) {
                            "output_text" -> visible["text"]?.jsonPrimitive?.contentOrNull
                            "refusal" -> { completionReason = LocalModelCompletionReason.REFUSED; visible["refusal"]?.jsonPrimitive?.contentOrNull }
                            else -> null
                        }?.takeIf(String::isNotBlank)?.let { text ->
                            content.append(text)
                            visibleParts += buildJsonObject {
                                put("type", "output_text")
                                put("text", text)
                            }
                        }
                        (visible["annotations"] as? JsonArray).orEmpty().forEach { item ->
                            val annotation = item as? JsonObject ?: return@forEach
                            if (annotation["type"]?.jsonPrimitive?.contentOrNull != "url_citation") return@forEach
                            val url = annotation["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (url.length in 12..2048 && runCatching {
                                val parsed = java.net.URI(url)
                                parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()
                            }.getOrDefault(false)) {
                                webSources[url] = annotation["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            }
                        }
                    }
                }
                "reasoning" -> {
                    (obj["summary"] as? JsonArray).orEmpty().forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        p["text"]?.jsonPrimitive?.contentOrNull?.let(reasoning::append)
                    }
                }
                "function_call" -> {
                    if (completionReason != LocalModelCompletionReason.COMPLETED) return@forEach
                    val callId = (obj["call_id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: throw responseProtocolError("工具调用缺少 call_id")
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: throw responseProtocolError("工具调用缺少 name")
                    val rawArguments = (obj["arguments"] as? JsonPrimitive)?.contentOrNull
                        ?: throw responseProtocolError("工具调用缺少 arguments")
                    val arguments = runCatching {
                        json.parseToJsonElement(rawArguments) as? JsonObject
                    }.getOrNull() ?: throw responseProtocolError("工具参数不是合法 JSON 对象")
                    toolCalls += LocalToolCall(callId, name, arguments, rawArguments)
                }
                "image_generation_call" -> {
                    val result = obj["result"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    val mediaType = when (obj["output_format"]?.jsonPrimitive?.contentOrNull) {
                        "jpeg", "jpg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        else -> "image/png"
                    }
                    generatedImageCount += 1
                    visibleParts += buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:$mediaType;base64,$result")
                        })
                    }
                }
            }
        }
        if (completionReason != LocalModelCompletionReason.COMPLETED) toolCalls.clear()
        val rawContent = content.toString().takeIf(String::isNotBlank)
            ?: streamedContent.takeIf { allowStreamFallback && it.isNotBlank() }
        val settledContent = if (webSources.isEmpty()) rawContent else buildString {
            rawContent?.let { append(it).append("\n\n") }
            append("参考来源：\n")
            webSources.keys.take(8).forEach { append(it).append('\n') }
        }.trimEnd()
        val settledReasoning = reasoning.toString().takeIf(String::isNotBlank)
            ?: streamedReasoning.takeIf { allowStreamFallback && it.isNotBlank() }
        val message = buildJsonObject {
            put("role", "assistant")
            if (generatedImageCount == 0) {
                if (settledContent != null) put("content", settledContent) else put("content", JsonNull)
            } else {
                val hasVisibleText = visibleParts.any { part ->
                    part["type"]?.jsonPrimitive?.contentOrNull == "output_text"
                }
                put("content", buildJsonArray {
                    if (!hasVisibleText && settledContent != null) {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", settledContent)
                        })
                    }
                    visibleParts.forEach(::add)
                })
            }
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
            if (completionReason == LocalModelCompletionReason.COMPLETED) put(RESPONSES_OUTPUT_KEY, responseReplayOutput(output))
        }
        return LocalModelReply(
            completionReason = completionReason, rawStopReason = rawStopReason,
            message = message,
            content = settledContent,
            reasoning = settledReasoning,
            toolCalls = toolCalls,
            usage = parseUsage(response["usage"] as? JsonObject),
            requestId = response["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            promptBreakdown = promptBreakdown,
            promptCacheDiagnostic = parsePromptCacheDiagnostic(
                response["prompt_cache_diagnostics"] as? JsonObject,
            ),
        ).also(::validateUsableModelReply)
    }

    private fun responseReplayOutput(output: JsonArray): JsonArray = buildJsonArray {
        output.forEach { item ->
            val obj = item as? JsonObject
            if (obj?.get("type")?.jsonPrimitive?.contentOrNull == "image_generation_call") {
                add(JsonObject(obj - "result"))
            } else {
                add(item)
            }
        }
    }

    private fun parsePromptCacheDiagnostic(value: JsonObject?): LocalPromptCacheDiagnostic? {
        value ?: return null
        val type = value["type"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        return LocalPromptCacheDiagnostic(
            type = type,
            reason = value["reason"]?.jsonPrimitive?.contentOrNull,
            comparisonReusableTokens = value["comparison_reusable_tokens"]?.jsonPrimitive?.longOrNull,
            cacheMissedTokens = value["cache_missed_tokens"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun parseUsage(usage: JsonObject?): DeepSeekTokenUsage {
        if (usage == null) return DeepSeekTokenUsage(reported = false)
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val inputDetails = usage["input_tokens_details"] as? JsonObject
        val cached = inputDetails
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val cacheWrite = inputDetails
            ?.get("cache_write_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        return DeepSeekTokenUsage(
            promptTokens = input.coerceAtLeast(0L),
            cacheHitTokens = cached.coerceAtLeast(0L),
            cacheMissTokens = (input - cached).coerceAtLeast(0L),
            cacheWriteTokens = cacheWrite.coerceAtLeast(0L)
                .coerceAtMost((input - cached).coerceAtLeast(0L)),
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
                admissionState = LocalModelAdmissionState.REJECTED,
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
                admissionState = LocalModelAdmissionState.REJECTED,
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
            admissionState = LocalModelAdmissionState.REJECTED,
        )
    }

    private fun structuredResponseError(
        code: String,
        param: String?,
        detail: String?,
        requestId: String?,
        retryAfterMs: Long?,
        statusOverride: Int? = null,
        admissionState: LocalModelAdmissionState = LocalModelAdmissionState.ADMITTED,
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
                "ChatGPT 暂时无法确认套餐可用量，777 已保留登录状态。",
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
            admissionState = admissionState,
        )
    }

    internal fun responsesEndpoint(baseUrl: String, planSharing: Boolean): String {
        if (planSharing) return CHATGPT_RESPONSES_URL
        val clean = normalizeModelBaseUrl(baseUrl).trimEnd('/')
        return if (clean.endsWith("/responses")) clean else "$clean/responses"
    }

    internal fun shouldIncludeEncryptedReasoning(baseUrl: String, planSharing: Boolean): Boolean =
        usesOpenAiResponsesContract(baseUrl, planSharing)

    internal fun usesOpenAiResponsesContract(baseUrl: String, planSharing: Boolean): Boolean =
        usesOfficialOpenAiResponsesContract(baseUrl, planSharing)

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

    internal fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? =
        retryAfterMillis(value, nowMillis)

    private fun responseProtocolError(detail: String) = LocalModelException(
        code = "RESPONSES_PROTOCOL_ERROR",
        message = "Responses API 协议错误：$detail",
        retryable = false,
    )

    companion object {
        const val RESPONSES_OUTPUT_KEY = "_dsh_responses_output"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        // Image tool result is inline base64 on response.completed; a single SSE line can exceed 4 MiB.
        // Keep the bounded payload above the 20 MiB image limit without removing stream guards.
        private const val MAX_STREAM_BYTES = 64 * 1024 * 1024
        private const val MAX_SSE_LINE_CHARS = 32 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 8_000
        private const val MAX_PROMPT_CACHE_KEY_CHARS = 64
        private val SUPPORTED_PROMPT_CACHE_TTLS = setOf("30m")
    }
}
