package com.labteto.dshmobile.device

import android.content.Context
import com.labteto.dshmobile.harness.capability.HarnessAndroidDeviceProvider
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.harness.tools.simpleToolProperties
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AndroidDevicePlugin(
    private val provider: HarnessAndroidDeviceProvider,
) : HarnessPlugin {
    constructor(context: Context) : this(AndroidDeviceProvider(context))
    override val id: String = "android-device"

    private data class Spec(
        val toolName: String,
        val capability: String,
        val description: String,
        val properties: Map<String, String> = emptyMap(),
        val required: Set<String> = emptySet(),
        val access: ToolAccess,
        val approval: ToolApprovalPolicy,
        val retention: ToolResultRetention = ToolResultRetention.DURABLE,
    )

    private val specs = listOf(
        Spec("android_device_info", "device_info", "读取 Android 设备与系统版本信息", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_app_list", "app_list", "列出当前可见应用包", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_app_info", "app_info", "读取指定应用信息", mapOf("package" to "string"), setOf("package"), ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_app_launch", "app_launch", "启动指定 Android 应用", mapOf("package" to "string"), setOf("package"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_settings_get", "settings_get", "读取 Android 系统设置", mapOf("namespace" to "string", "key" to "string"), setOf("namespace", "key"), ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_screen", "accessibility_tree", "读取当前无障碍控件树，返回短生命周期 node 编号；未就绪时会返回系统无障碍授权引导", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_find", "accessibility_find", "按文本、控件 id、类型或可交互属性查找当前界面控件", mapOf("text" to "string", "id" to "string", "class" to "string", "clickable" to "boolean", "editable" to "boolean"), access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_click_node", "accessibility_click_node", "按最近一次界面树/查找结果中的 node 编号点击控件", mapOf("node" to "integer"), setOf("node"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_set_text_node", "accessibility_set_text_node", "按 node 编号定位输入框，自动替换文字并回读确认；与 android_input_text 使用同一写入与验证机制", mapOf("node" to "integer", "value" to "string"), setOf("node", "value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_scroll", "accessibility_scroll", "滚动当前界面可滚动区域", mapOf("direction" to "string"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_wait", "accessibility_wait", "等待文本或控件 id 出现，避免固定延时猜测页面状态", mapOf("text" to "string", "id" to "string", "timeout_ms" to "integer"), access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_open_uri", "android_open_uri", "通过系统安全 VIEW Intent 打开 http/https/market/geo/mailto/tel URI", mapOf("uri" to "string"), setOf("uri"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_tap_text", "accessibility_click_text", "按文本定位并点击控件", mapOf("text" to "string"), setOf("text"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_type", "accessibility_set_text", "按现有文字定位输入框，或省略 search_text 使用当前焦点；自动替换文字并回读确认，与 android_input_text 使用同一机制", mapOf("search_text" to "string", "value" to "string"), setOf("value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_input_text", "accessibility_input_text", "一次完成 Android/Compose 文本输入：可用 node、控件 id、现有文本精确定位；全部留空即使用当前焦点。自动聚焦、替换文本并回读确认；返回 false 表示未确认生效。", mapOf("node" to "integer", "id" to "string", "text" to "string", "value" to "string"), setOf("value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_tap", "accessibility_tap", "按屏幕坐标点击", mapOf("x" to "number", "y" to "number", "duration_ms" to "integer"), setOf("x", "y"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_swipe", "accessibility_swipe", "按坐标执行滑动", mapOf("start_x" to "number", "start_y" to "number", "end_x" to "number", "end_y" to "number", "duration_ms" to "integer"), setOf("start_x", "start_y", "end_x", "end_y"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_back", "android_back", "执行系统返回", access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_home", "android_home", "执行系统主页", access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_screenshot", "android_screenshot", "通过无障碍服务截取当前屏幕", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS, retention = ToolResultRetention.EPHEMERAL),
        Spec("android_notification_status", "notification_status", "检查通知读取服务是否已授权并连接", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_notification_list", "notification_list", "读取已授权的当前通知；结果会进入会话历史，已限长并脱敏常见验证码/密码；未授权时先调用 android_notification_status", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_clipboard_get", "clipboard_get", "读取当前剪贴板", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_clipboard_set", "clipboard_set", "写入系统剪贴板；传入空字符串时直接清空剪贴板，空格保留原样", mapOf("text" to "string"), setOf("text"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_clipboard_clear", "clipboard_clear", "清空系统剪贴板，清理自动化测试文本残留", access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_create", "vscreen_create", "创建独立 Android 虚拟显示", mapOf("width" to "integer", "height" to "integer", "density_dpi" to "integer"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_start", "vscreen_start", "一键为指定应用创建并启动虚拟屏。若目标应用无法在虚拟屏启动，将自动释放本次创建的显示器和槽位；成功返回可直接用于截图及操作的 id。", mapOf("package" to "string", "width" to "integer", "height" to "integer", "density_dpi" to "integer"), setOf("package"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_list", "vscreen_list", "列出虚拟显示", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_status", "vscreen_status", "读取虚拟显示状态", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_launch", "vscreen_launch", "在虚拟显示中启动应用", mapOf("id" to "string", "package" to "string"), setOf("id", "package"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_tap", "vscreen_tap", "在虚拟显示指定坐标点击", mapOf("id" to "string", "x" to "number", "y" to "number", "duration_ms" to "integer"), setOf("id", "x", "y"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_swipe", "vscreen_swipe", "在虚拟显示执行滑动", mapOf("id" to "string", "start_x" to "number", "start_y" to "number", "end_x" to "number", "end_y" to "number", "duration_ms" to "integer"), setOf("id", "start_x", "start_y", "end_x", "end_y"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_vscreen_screenshot", "vscreen_screenshot", "截取虚拟显示画面", mapOf("id" to "string"), setOf("id"), ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS, retention = ToolResultRetention.EPHEMERAL),
        Spec("android_vscreen_close", "vscreen_close", "关闭虚拟显示", mapOf("id" to "string"), setOf("id"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
    )

    override suspend fun install(context: HarnessContext) {
        specs.forEach { spec ->
            context.tools.register(
                HarnessTool(
                    name = spec.toolName,
                    schema = functionToolSchema(
                        name = spec.toolName,
                        description = spec.description,
                        properties = simpleToolProperties(spec.properties),
                        required = spec.required,
                    ),
                    access = spec.access,
                    approvalPolicy = spec.approval,
                    exposure = ToolExposure.OPTIONAL,
                    metadata = ToolMetadata(
                        family = "Android",
                        discoveryKeywords = ANDROID_DISCOVERY_KEYWORDS,
                        requirements = requirementsFor(spec.toolName),
                    ),
                    executor = HarnessToolExecutor { _, input, _ ->
                        val arguments = input.mapValues { (_, value) -> value.jsonPrimitive.content }
                        androidDeviceToolResult(
                            spec.capability,
                            provider.invoke(spec.capability, arguments),
                            spec.retention,
                        )
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
        try {
            specs.forEach { context.tools.unregister(it.toolName) }
            context.capabilities.unregister("android-device")
        } finally {
            provider.close()
        }
    }

    private fun requirementsFor(name: String): List<String> = when {
        name.startsWith("android_notification_") ->
            listOf("读取通知需要用户授权通知访问；先调用 android_notification_status 确认")
        name in ACCESSIBILITY_TOOLS ->
            listOf("需要 777 无障碍服务已授权并连接")
        name.startsWith("android_vscreen_") ->
            listOf("需要设备支持虚拟显示；目标应用必须允许在虚拟显示中运行")
        else -> emptyList()
    }

    private companion object {
        val ANDROID_DISCOVERY_KEYWORDS = setOf(
            "android", "安卓", "手机", "设备", "应用", "界面", "无障碍", "通知", "剪贴板", "虚拟屏",
        )
        val ACCESSIBILITY_TOOLS = setOf(
            "android_screen", "android_find", "android_click_node", "android_set_text_node",
            "android_scroll", "android_wait", "android_tap_text", "android_type", "android_input_text", "android_tap",
            "android_swipe", "android_screenshot",
        )
    }
}


/**
 * Android's false response means the action did not receive a completion receipt.
 * Preserve the uncertain effect rather than recording a successful Work tool result.
 */
private val ANDROID_BOOLEAN_ACTIONS = setOf(
    "accessibility_click_node", "accessibility_set_text_node", "accessibility_scroll",
    "accessibility_click_text", "accessibility_set_text", "accessibility_input_text", "accessibility_tap",
    "accessibility_swipe", "android_back", "android_home", "vscreen_tap",
    "vscreen_swipe", "vscreen_close",
)

internal fun androidDeviceToolResult(
    capability: String,
    value: String,
    retention: ToolResultRetention = ToolResultRetention.DURABLE,
): ToolResult {
    val unconfirmed = capability in ANDROID_BOOLEAN_ACTIONS && value == "false"
    return ToolResult(
        content = if (unconfirmed) "Android 未确认本次操作完成。请先重新读取当前界面或虚拟屏状态，再决定后续操作。" else value,
        isError = unconfirmed,
        errorCode = if (unconfirmed) "ANDROID_ACTION_UNCONFIRMED" else null,
        retryable = false,
        recoveryHint = if (unconfirmed) "重新检查当前界面；有副作用的操作应先核对结果再执行。" else null,
        retention = retention,
    )
}
