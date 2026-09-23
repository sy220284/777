package com.labteto.dshmobile.local

import java.util.Base64
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

data class LocalVisionRoute(
    val baseUrl: String,
    val model: String,
)

fun interface LocalVisionAnalyzer {
    suspend fun analyze(
        apiKey: String,
        route: LocalVisionRoute,
        prompt: String,
        imageDataUrl: String,
    ): String
}

/**
 * Vision tools keep raw screenshots out of the text agent history.
 *
 * Every capture needs fresh approval because pixels leave the device, including virtual displays.
 */
class LocalVisionPlugin(
    private val device: HarnessDeviceProvider,
    private val keyProvider: suspend () -> String?,
    private val routeProvider: () -> LocalVisionRoute?,
    private val analyzer: LocalVisionAnalyzer,
    private val workspaceRoot: File? = null,
) : HarnessPlugin {
    override val id: String = "local-vision"
    private val analysisCache = workspaceRoot?.let { root ->
        LocalVisionAnalysisCache(File(root, ".dsh/vision-cache"))
    }

    override suspend fun install(context: HarnessContext) {
        context.tools.register(
            HarnessTool(
                name = "vision_status",
                schema = schema(
                    name = "vision_status",
                    description = "检查本机视觉模型是否已配置",
                ),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                executor = HarnessToolExecutor { _, _, _ ->
                    val configured = routeProvider() != null && keyProvider() != null
                    ToolResult(if (configured) "视觉模型已配置" else "视觉模型尚未配置")
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "vision_analyze_screen",
                schema = schema(
                    name = "vision_analyze_screen",
                    description = "截取当前主屏并交给已配置的多模态模型分析；图像会发送到外部视觉模型",
                    properties = mapOf("prompt" to "string"),
                    required = setOf("prompt"),
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 240_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    analyze(
                        prompt = input.requiredString("prompt"),
                        screenshotCapability = "android_screenshot",
                        screenshotArguments = emptyMap(),
                    )
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "vision_analyze_vscreen",
                schema = schema(
                    name = "vision_analyze_vscreen",
                    description = "分析 Agent 虚拟屏画面并返回视觉判断和坐标；不把图片写入文字模型历史",
                    properties = mapOf("id" to "string", "prompt" to "string"),
                    required = setOf("id", "prompt"),
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 240_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    analyze(
                        prompt = input.requiredString("prompt"),
                        screenshotCapability = "vscreen_screenshot",
                        screenshotArguments = mapOf("id" to input.requiredString("id")),
                    )
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "vision_analyze_file",
                schema = schema(
                    name = "vision_analyze_file",
                    description = "分析工作区内的 PNG/JPEG/WebP/GIF 图片；图片会发送到外部视觉模型；相同图片与分析要求可复用已批准分析缓存",
                    properties = mapOf(
                        "path" to "string",
                        "prompt" to "string",
                        "refresh" to "boolean",
                    ),
                    required = setOf("path", "prompt"),
                ),
                access = ToolAccess.NETWORK,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 240_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    val route = routeProvider()
                    val key = keyProvider()
                    if (route == null || key.isNullOrBlank()) {
                        return@HarnessToolExecutor ToolResult(
                            "视觉模型尚未配置，请先在设置中填写视觉模型、接口地址和密钥",
                            isError = true,
                        )
                    }
                    val prompt = input.requiredString("prompt")
                    val file = runCatching { imageFile(input.requiredString("path")) }
                        .getOrElse { error ->
                            return@HarnessToolExecutor ToolResult(
                                "视觉文件读取失败：${error.message ?: error::class.java.simpleName}",
                                isError = true,
                            )
                        }
                    val refresh = input.optionalBoolean("refresh")
                    if (!refresh) {
                        analysisCache?.get(file, route, prompt)?.let { cached ->
                            return@HarnessToolExecutor ToolResult(cached)
                        }
                    }
                    val dataUrl = runCatching { imageFileDataUrl(file) }
                        .getOrElse { error ->
                            return@HarnessToolExecutor ToolResult(
                                "视觉文件读取失败：${error.message ?: error::class.java.simpleName}",
                                isError = true,
                            )
                        }
                    val result = analyzeDataUrl(
                        prompt = prompt,
                        imageDataUrl = dataUrl,
                        intro = "分析这张用户工作区图片。",
                        fixedRoute = route,
                        fixedKey = key,
                    )
                    if (!result.isError) analysisCache?.put(file, route, prompt, result.content)
                    result
                },
            ),
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        context.tools.unregister("vision_status")
        context.tools.unregister("vision_analyze_screen")
        context.tools.unregister("vision_analyze_vscreen")
        context.tools.unregister("vision_analyze_file")
    }

    private suspend fun analyze(
        prompt: String,
        screenshotCapability: String,
        screenshotArguments: Map<String, String>,
    ): ToolResult {
        if (routeProvider() == null || keyProvider().isNullOrBlank()) {
            return ToolResult(
                "视觉模型尚未配置，请先在设置中填写视觉模型、接口地址和密钥",
                isError = true,
            )
        }
        val imageDataUrl = device.invoke(screenshotCapability, screenshotArguments)
        if (!imageDataUrl.startsWith("data:image/")) {
            return ToolResult("设备截图没有返回有效图片", isError = true)
        }
        return analyzeDataUrl(
            prompt = prompt,
            imageDataUrl = imageDataUrl,
            intro = "分析这张 Android 界面截图。\n若用户要求点击目标，请给出目标中心的原始截图像素坐标 x/y，并描述用于复核的可见特征。",
        )
    }

    private suspend fun analyzeDataUrl(
        prompt: String,
        imageDataUrl: String,
        intro: String,
        fixedRoute: LocalVisionRoute? = null,
        fixedKey: String? = null,
    ): ToolResult {
        val route = fixedRoute ?: routeProvider()
            ?: return ToolResult("视觉模型尚未配置，请先在设置中填写视觉模型、接口地址和密钥", isError = true)
        val key = fixedKey ?: keyProvider()
            ?: return ToolResult("视觉模型密钥尚未配置", isError = true)
        val boundedPrompt = buildString {
            appendLine(intro)
            appendLine("只描述可见事实，不要臆测画面外内容。")
            append("任务：")
            append(prompt.take(4_000))
        }
        return runCatching {
            analyzer.analyze(key, route, boundedPrompt, imageDataUrl)
        }.fold(
            onSuccess = { ToolResult(it) },
            onFailure = { error ->
                ToolResult("视觉分析失败：${error.message ?: error::class.java.simpleName}", isError = true)
            },
        )
    }

    private fun imageFile(relativePath: String): File {
        val root = workspaceRoot?.canonicalFile ?: error("本机工作区未配置")
        require(!File(relativePath).isAbsolute) { "视觉文件路径必须使用工作区相对路径" }
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "拒绝读取工作区之外的图片" }
        require(file.isFile) { "图片文件不存在：$relativePath" }
        require(file.length() in 1..MAX_IMAGE_FILE_BYTES) {
            "图片大小必须在 1..${MAX_IMAGE_FILE_BYTES / 1024 / 1024} MiB"
        }
        require(sniffLocalImageMediaType(file) != null) { "视觉文件仅支持真实的 PNG/JPEG/WebP/GIF 图片" }
        return file
    }

    private fun imageFileDataUrl(file: File): String {
        val mime = sniffLocalImageMediaType(file)
            ?: error("视觉文件仅支持真实的 PNG/JPEG/WebP/GIF 图片")
        val encoded = Base64.getEncoder().encodeToString(file.readBytes())
        return "data:$mime;base64,$encoded"
    }

    private fun schema(
        name: String,
        description: String,
        properties: Map<String, String> = emptyMap(),
        required: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    properties.forEach { (property, type) ->
                        put(property, buildJsonObject { put("type", type) })
                    }
                })
                put("required", buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                })
                put("additionalProperties", false)
            })
        })
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: error("缺少参数：$name")

    private fun JsonObject.optionalBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull == true

    private companion object {
        const val MAX_IMAGE_FILE_BYTES = 16L * 1024L * 1024L
    }
}
