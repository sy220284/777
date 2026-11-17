package com.labteto.dshmobile.local.vision

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.record
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible multimodal transport for explicit image-analysis tools.
 *
 * The route and credential come from the frozen tool-run profile or the selected model. Screenshots are sent only
 * after a model tool call, keeping image bytes out of durable conversation history.
 */
@Singleton
class VisionClient @Inject constructor(
    private val json: Json,
    private val usageTracker: javax.inject.Provider<DeepSeekUsageTracker>,
    private val modelGateway: javax.inject.Provider<LocalModelGateway>,
) : LocalVisionAnalyzer {
    internal constructor(json: Json) : this(
        json,
        javax.inject.Provider { error("测试构造器没有用量追踪器") },
        javax.inject.Provider { error("测试构造器没有模型网关") },
    )

    override suspend fun analyze(
        apiKey: String,
        route: LocalVisionRoute,
        prompt: String,
        imageDataUrl: String,
    ): String = analyze(
        apiKey = apiKey,
        baseUrl = route.baseUrl,
        model = route.model,
        prompt = prompt,
        imageDataUrl = imageDataUrl,
        usageContext = route.usageContext,
        profile = route.profile,
    )

    suspend fun analyze(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        imageDataUrl: String,
        usageContext: TokenUsageContext? = null,
        profile: LocalModelProfile? = null,
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "当前模型密钥为空" }
        require(model.isNotBlank()) { "当前模型名称为空" }
        require(prompt.isNotBlank()) { "图片分析要求不能为空" }
        validateImageDataUrl(imageDataUrl)
        val encodedLength = (imageDataUrl.length - imageDataUrl.indexOf(',') - 1).toLong()
        val padding = if (imageDataUrl.endsWith("==")) 2 else if (imageDataUrl.endsWith("=")) 1 else 0
        require(encodedLength % 4 == 0L && encodedLength / 4 * 3 - padding <=
            LocalModelPresets.maxNativeImageBytesFor(model, baseUrl)) {
            "图片超过当前模型的上传大小限制，请压缩后重试"
        }

        // Production has one inference path. A dependency failure must surface, never
        // select a second HTTP transport with different protocol, auth and replay rules.
        val gateway = modelGateway.get()
        val selectedProfile = requireNotNull(profile) { "视觉模型请求缺少冻结模型档案" }
        val messages = buildPayload(model, prompt, imageDataUrl)["messages"]
            ?.jsonArray
            ?.map { it.jsonObject }
            ?: error("图片分析请求构造失败")
        // Bind the key already used for cache admission to the actual inference request.
        val binding = if (selectedProfile.authKind == com.labteto.dshmobile.local.model.LocalModelAuthKind.API_KEY)
            com.labteto.dshmobile.local.model.LocalMediaCredentialBinding(selectedProfile,
                com.labteto.dshmobile.local.model.LocalResolvedCredential(apiKey, selectedProfile.authKind))
        else kotlin.coroutines.EmptyCoroutineContext
        val reply = withContext(binding) {
            gateway.complete(
                model = model,
                baseUrl = baseUrl,
                messages = messages,
                tools = JsonArray(emptyList()),
                profile = selectedProfile,
            )
        }
        usageTracker.get().record(
            model = model,
            usage = reply.usage,
            requestId = reply.requestId,
            context = usageContext?.copy(
                action = TokenUsageAction.VISION,
                taskLabel = usageContext.taskLabel ?: prompt.take(120),
            ) ?: TokenUsageContext(
                mode = LocalUsageMode.WORK,
                action = TokenUsageAction.VISION,
                taskLabel = prompt.take(120),
            ),
            promptBreakdown = reply.promptBreakdown,
            route = reply.routeIdentity,
        )
        com.labteto.dshmobile.local.model.requireCompletedModelReply(reply)
        return@withContext reply.content?.trim()?.takeIf(String::isNotBlank)
            ?: error("当前模型返回了空文本")
    }

    internal fun buildPayload(
        model: String,
        prompt: String,
        imageDataUrl: String,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", false)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put(
                    "content",
                    "你负责分析用户提供的图片，按当前任务识别可见内容、解释图表或评估界面；区分观察与推断。需要定位时使用原图像素坐标，无法确认时说明。",
                )
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", imageDataUrl)
                            put("detail", "high")
                        })
                    })
                })
            })
        })
    }

    internal fun parse(body: String): String =
        parseRoot(json.parseToJsonElement(body).jsonObject)

    private fun parseRoot(root: JsonObject): String {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message") as? JsonObject ?: JsonObject(emptyMap())
        val stop = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        val completion = when {
            message["refusal"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true || stop == "content_filter" ->
                com.labteto.dshmobile.local.model.LocalModelCompletionReason.REFUSED
            stop == "length" -> com.labteto.dshmobile.local.model.LocalModelCompletionReason.OUTPUT_LIMIT
            stop == null || stop == "stop" -> com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED
            else -> com.labteto.dshmobile.local.model.LocalModelCompletionReason.INTERRUPTED
        }
        com.labteto.dshmobile.local.model.requireCompletedModelReply(
            com.labteto.dshmobile.local.model.LocalModelReply(message, null, null, emptyList(),
                completionReason = completion, rawStopReason = stop))
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")
            ?: error("当前模型响应缺少 choices[0].message.content")
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "text" || type == "output_text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }.joinToString("\n")
            else -> ""
        }.trim().ifBlank { error("当前模型返回了空文本") }
    }

    private fun validateImageDataUrl(value: String) {
        require(value.startsWith("data:image/")) { "图片输入必须是图片 data URL" }
        require(";base64," in value.take(128)) { "图片输入必须使用 base64 data URL" }
        require(value.length <= MAX_IMAGE_DATA_URL_CHARS) { "图片输入超过大小上限" }
    }

    private companion object {
        const val MAX_IMAGE_DATA_URL_CHARS = 24 * 1024 * 1024
    }
}
