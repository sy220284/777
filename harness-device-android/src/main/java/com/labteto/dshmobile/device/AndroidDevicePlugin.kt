package com.labteto.dshmobile.device

import android.content.Context
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
    context: Context,
    private val provider: AndroidDeviceProvider = AndroidDeviceProvider(context),
) : HarnessPlugin {
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
        Spec("android_privilege_status", "shizuku_status", "读取 Shizuku Binder、授权与身份状态", access = ToolAccess.READ_ONLY),
        Spec("android_privilege_request", "shizuku_request_permission", "请求 Shizuku 权限", mapOf("request_code" to "integer"), approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_app_list", "app_list", "列出当前可见应用包", access = ToolAccess.READ_ONLY),
        Spec("android_app_info", "app_info", "读取指定应用信息", mapOf("package" to "string"), setOf("package"), ToolAccess.READ_ONLY),
        Spec("android_app_launch", "app_launch", "启动指定 Android 应用", mapOf("package" to "string"), setOf("package")),
        Spec("android_app_stop", "app_stop", "通过 Shizuku 强制停止应用", mapOf("package" to "string"), setOf("package"), ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS),
        Spec("android_settings_get", "settings_get", "读取 Android 系统设置", mapOf("namespace" to "string", "key" to "string"), setOf("namespace", "key"), ToolAccess.READ_ONLY),
        Spec("android_settings_set", "settings_set", "通过 Shizuku 修改 Android 系统设置", mapOf("namespace" to "string", "key" to "string", "value" to "string"), setOf("namespace", "key", "value"), ToolAccess.PRIVILEGED, ToolApprovalPolicy.ALWAYS),
        Spec("android_dumpsys", "dumpsys", "通过 Shizuku 调用 dumpsys", mapOf("service" to "string", "args" to "string"), setOf("service"), ToolAccess.PRIVILEGED),
        Spec("android_screen", "accessibility_tree", "读取当前无障碍控件树", access = ToolAccess.READ_ONLY),
        Spec("android_tap_text", "accessibility_click_text", "按文本定位并点击控件", mapOf("text" to "string"), setOf("text")),
        Spec("android_type", "accessibility_set_text", "定位可编辑控件并写入文本", mapOf("search_text" to "string", "value" to "string"), setOf("search_text", "value")),
        Spec("android_tap", "accessibility_tap", "按屏幕坐标点击", mapOf("x" to "number", "y" to "number", "duration_ms" to "integer"), setOf("x", "y")),
        Spec("android_swipe", "accessibility_swipe", "按坐标执行滑动", mapOf("start_x" to "number", "start_y" to "number", "end_x" to "number", "end_y" to "number", "duration_ms" to "integer"), setOf("start_x", "start_y", "end_x", "end_y")),
        Spec("android_back", "android_back", "执行系统返回"),
        Spec("android_home", "android_home", "执行系统主页"),
        Spec("android_screenshot", "android_screenshot", "通过无障碍服务截取当前屏幕", access = ToolAccess.READ_ONLY),
        Spec("android_notification_list", "notification_list", "读取已授权的当前通知", access = ToolAccess.READ_ONLY),
        Spec("android_clipboard_get", "clipboard_get", "读取当前剪贴板", access = ToolAccess.READ_ONLY),
        Spec("android_clipboard_set", "clipboard_set", "写入剪贴板", mapOf("text" to "string"), setOf("text")),
        Spec("android_vscreen_create", "vscreen_create", "创建独立 Android 虚拟显示", mapOf("width" to "integer", "height" to "integer", "density_dpi" to "integer")),
        Spec("android_vscreen_list", "vscreen_list", "列出虚拟显示", access = ToolAccess.READ_ONLY),
        Spec("android_vscreen_status", "vscreen_status", "读取虚拟显示状态", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY),
        Spec("android_vscreen_launch", "vscreen_launch", "在虚拟显示中启动应用", mapOf("id" to "string", "package" to "string"), setOf("id", "package")),
        Spec("android_vscreen_tap", "vscreen_tap", "在虚拟显示指定坐标点击", mapOf("id" to "string", "x" to "number", "y" to "number", "duration_ms" to "integer"), setOf("id", "x", "y")),
        Spec("android_vscreen_swipe", "vscreen_swipe", "在虚拟显示执行滑动", mapOf("id" to "string", "start_x" to "number", "start_y" to "number", "end_x" to "number", "end_y" to "number", "duration_ms" to "integer"), setOf("id", "start_x", "start_y", "end_x", "end_y")),
        Spec("android_vscreen_screenshot", "vscreen_screenshot", "截取虚拟显示画面", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY),
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
