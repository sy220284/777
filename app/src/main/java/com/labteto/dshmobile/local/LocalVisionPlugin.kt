package com.labteto.dshmobile.local

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 * Main-screen capture always needs approval because pixels leave the device. Virtual-screen capture
 * can run without a second approval after the agent has already been allowed to create/control that
 * isolated display.
 */
class LocalVisionPlugin(
    private val device: HarnessDeviceProvider,
    private val keyProvider: suspend () -> String?,
    private val routeProvider: () -> LocalVisionRoute?,
    private val analyzer: LocalVisionAnalyzer,
) : HarnessPlugin {
    override val id: String = "local-vision"

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
                approvalPolicy = ToolApprovalPolicy.NEVER,
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
    }

    override suspend fun uninstall(context: HarnessContext) {
        context.tools.unregister("vision_status")
        context.tools.unregister("vision_analyze_screen")
        context.tools.unregister("vision_analyze_vscreen")
    }

    private suspend fun analyze(
        prompt: String,
        screenshotCapability: String,
        screenshotArguments: Map<String, String>,
    ): ToolResult {
        val route = routeProvider()
            ?: return ToolResult("视觉模型尚未配置，请先在设置中填写视觉模型、接口地址和密钥", isError = true)
        val key = keyProvider()
            ?: return ToolResult("视觉模型密钥尚未配置", isError = true)
        val imageDataUrl = device.invoke(screenshotCapability, screenshotArguments)
        if (!imageDataUrl.startsWith("data:image/")) {
            return ToolResult("设备截图没有返回有效图片", isError = true)
        }
        val boundedPrompt = buildString {
            appendLine("分析这张 Android 界面截图。")
            appendLine("若用户要求点击目标，请给出目标中心的原始截图像素坐标 x/y，并描述用于复核的可见特征。")
            appendLine("不要臆测画面外内容。")
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
}
