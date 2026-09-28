package com.labteto.dshmobile.device

import android.content.Context
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AndroidDevicePlugin(
    private val provider: HarnessDeviceProvider,
) : HarnessPlugin {
    constructor(context: Context) : this(AndroidDeviceProvider(context))
    override val id: String = "android-device"

    private data class Spec(
        val toolName: String,
        val capability: String,
        val description: String,
        val properties: Map<String, String> = emptyMap(),
        val required: Set<String> = emptySet(),
        val access: ToolAccess = ToolAccess.DEVICE,
        val approval: ToolApprovalPolicy = ToolApprovalPolicy.MUTATION,
    )

    private val specs = listOf(
        Spec("android_device_info", "device_info", "读取 Android 设备与系统版本信息", access = ToolAccess.READ_ONLY),
        Spec("android_app_list", "app_list", "列出当前可见应用包", access = ToolAccess.READ_ONLY),
        Spec("android_app_info", "app_info", "读取指定应用信息", mapOf("package" to "string"), setOf("package"), ToolAccess.READ_ONLY),
        Spec("android_app_launch", "app_launch", "启动指定 Android 应用", mapOf("package" to "string"), setOf("package")),
        Spec("android_settings_get", "settings_get", "读取 Android 系统设置", mapOf("namespace" to "string", "key" to "string"), setOf("namespace", "key"), ToolAccess.READ_ONLY),
        Spec("android_open_uri", "android_open_uri", "通过系统安全 VIEW Intent 打开 http/https/market/geo/mailto/tel URI", mapOf("uri" to "string"), setOf("uri")),
        Spec("android_notification_status", "notification_status", "检查通知读取服务是否已授权并连接", access = ToolAccess.READ_ONLY),
        Spec("android_notification_list", "notification_list", "读取已授权的当前通知；未授权时先调用 android_notification_status", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_clipboard_get", "clipboard_get", "读取当前剪贴板", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_clipboard_set", "clipboard_set", "写入剪贴板", mapOf("text" to "string"), setOf("text")),
        Spec("android_vscreen_create", "vscreen_create", "创建独立 Android 虚拟显示", mapOf("width" to "integer", "height" to "integer", "density_dpi" to "integer")),
        Spec("android_vscreen_list", "vscreen_list", "列出虚拟显示", access = ToolAccess.READ_ONLY),
        Spec("android_vscreen_status", "vscreen_status", "读取虚拟显示状态", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY),
        Spec("android_vscreen_launch", "vscreen_launch", "在虚拟显示中启动应用", mapOf("id" to "string", "package" to "string"), setOf("id", "package")),
        Spec("android_vscreen_screenshot", "vscreen_screenshot", "截取虚拟显示画面", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_vscreen_close", "vscreen_close", "关闭虚拟显示", mapOf("id" to "string"), setOf("id")),
    )

    override suspend fun install(context: HarnessContext) {
        specs.forEach { spec ->
            context.tools.register(
                HarnessTool(
                    name = spec.toolName,
                    schema = toolSchema(spec),
                    access = spec.access,
                    approvalPolicy = spec.approval,
                    executor = HarnessToolExecutor { _, input, _ ->
                        val arguments = input.mapValues { (_, value) -> value.jsonPrimitive.content }
                        ToolResult(provider.invoke(spec.capability, arguments))
                    },
                ),
            )
        }
        context.capabilities.register(
            com.labteto.dshmobile.harness.capability.CapabilityDescriptor(
                id = "android-device",
                attributes = mapOf("api" to android.os.Build.VERSION.SDK_INT.toString()),
            ),
            provider,
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        specs.forEach { context.tools.unregister(it.toolName) }
        context.capabilities.unregister("android-device")
    }

    private fun toolSchema(spec: Spec): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.toolName)
            put("description", spec.description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    spec.properties.forEach { (name, type) ->
                        put(name, buildJsonObject { put("type", type) })
                    }
                })
                put("required", buildJsonArray {
                    spec.required.forEach { add(JsonPrimitive(it)) }
                })
                put("additionalProperties", false)
            })
        })
    }
}
