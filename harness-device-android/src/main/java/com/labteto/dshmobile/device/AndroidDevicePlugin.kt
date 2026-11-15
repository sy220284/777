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
import com.labteto.dshmobile.harness.tools.ToolDeviceRequirement
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
        Spec("android_screen", "accessibility_tree", "读取当前无障碍控件树。node 是最近一次界面观察的短生命周期编号；跨页面应重新定位；输入优先使用 android_input_text 的稳定 id 或当前焦点。无障碍未授权时返回引导", access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_find", "accessibility_find", "按文本、控件 id、类型或可交互属性查找实时控件；返回的 node 可用于同页后续点击/输入，界面切换后重新查找。编辑文字优先传 id 给 android_input_text", mapOf("text" to "string", "id" to "string", "class" to "string", "clickable" to "boolean", "editable" to "boolean"), access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_click_node", "accessibility_click_node", "按最近一次界面树/查找结果中的 node 编号点击控件", mapOf("node" to "integer"), setOf("node"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_set_text_node", "accessibility_set_text_node", "使用最近一次 android_find/screen 的 node 定位输入框并替换文字。界面切换或节点重排时优先用 android_input_text(id/value) 或焦点；返回区分未定位、写入请求未确认及回读未确认", mapOf("node" to "integer", "value" to "string"), setOf("node", "value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_scroll", "accessibility_scroll", "滚动当前界面可滚动区域", mapOf("direction" to "string"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_wait", "accessibility_wait", "等待文本或控件 id 出现，避免固定延时猜测页面状态", mapOf("text" to "string", "id" to "string", "timeout_ms" to "integer"), access = ToolAccess.READ_ONLY, approval = ToolApprovalPolicy.ALWAYS),
        Spec("android_open_uri", "android_open_uri", "通过系统安全 VIEW Intent 打开 http/https/market/geo/mailto/tel URI", mapOf("uri" to "string"), setOf("uri"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_tap_text", "accessibility_click_text", "按文本定位并点击控件", mapOf("text" to "string"), setOf("text"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_type", "accessibility_set_text", "按现有文字定位输入框，或省略 search_text 使用当前焦点；同 android_input_text 的统一写入与回读协议，回执可区分目标未找到与写入后未确认", mapOf("search_text" to "string", "value" to "string"), setOf("value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
        Spec("android_input_text", "accessibility_input_text", "Android/Compose 输入：优先 id+value 精确选框；其次使用当前焦点对应的输入框（只传 value）；也可传最近 find 返回的 node 或现有 text。自动替换并回读。成功为 true；失败明确区分目标不可用、系统未确认写入、已接受写入但未能回读，后者先重读界面再决定下一步，避免重复修改。", mapOf("node" to "integer", "id" to "string", "text" to "string", "value" to "string"), setOf("value"), access = ToolAccess.DEVICE, approval = ToolApprovalPolicy.MUTATION),
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
                        discoveryKeywords = discoveryKeywordsFor(spec.toolName),
                        requirements = requirementsFor(spec.toolName),
                        requiredDeviceCapabilities = when {
                            spec.toolName in ACCESSIBILITY_TOOLS -> setOf(ToolDeviceRequirement.ACCESSIBILITY)
                            spec.toolName == "android_notification_list" -> setOf(ToolDeviceRequirement.NOTIFICATION_ACCESS)
                            else -> emptySet()
                        },
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
        // Each Android operation contributes its own intent terms. A request mentioning
        // "手机" must not eagerly fill the model schema budget with every Android tool.
        // All tools remain registered and discoverable by exact name at any time.
        fun discoveryKeywordsFor(name: String): Set<String> = when (name) {
            "android_device_info" -> setOf("android", "安卓", "手机", "设备", "手机信息", "系统版本")
            "android_app_list" -> setOf("应用", "应用列表", "已安装应用", "列出应用", "查看应用")
            "android_app_info" -> setOf("应用详情", "应用信息", "应用包名")
            "android_app_launch" -> setOf("打开应用", "启动应用")
            "android_settings_get" -> setOf("读取系统设置", "系统设置项")
            "android_screen" -> setOf("查看屏幕", "查看手机界面", "当前屏幕", "无障碍", "操作手机", "操控手机")
            "android_find" -> setOf("操作手机", "查找控件", "定位控件", "查找按钮", "查找输入框", "界面元素")
            "android_click_node" -> setOf("点击控件", "点击节点")
            "android_set_text_node" -> setOf("节点输入", "修改控件文本")
            "android_scroll" -> setOf("滚动页面", "页面滚动", "滑动列表")
            "android_wait" -> setOf("等待控件", "等待页面", "等待出现")
            "android_open_uri" -> setOf("打开链接", "打开网址", "系统链接")
            "android_tap_text" -> setOf("操作手机", "点击文字", "点击按钮", "点击文本")
            "android_type" -> setOf("文字输入", "替换文本")
            "android_input_text" -> setOf("操作手机", "输入文字", "填写表单", "向输入框输入", "输入文本")
            "android_tap" -> setOf("坐标点击", "点击坐标")
            "android_swipe" -> setOf("坐标滑动", "滑动屏幕")
            "android_back" -> setOf("返回上一页", "系统返回")
            "android_home" -> setOf("返回桌面", "系统主页")
            "android_screenshot" -> setOf("截图", "屏幕截图", "手机截图")
            "android_notification_status" -> setOf("通知", "通知权限", "通知访问")
            "android_notification_list" -> setOf("读取通知", "通知列表", "查看系统通知")
            "android_clipboard_get" -> setOf("剪贴板", "读取剪贴板", "粘贴板")
            "android_clipboard_set" -> setOf("剪贴板", "写入剪贴板", "复制文字")
            "android_clipboard_clear" -> setOf("清空剪贴板", "清除剪贴板")
            "android_vscreen_create" -> setOf("创建虚拟屏", "创建虚拟显示")
            "android_vscreen_start" -> setOf("虚拟屏", "虚拟屏启动", "启动虚拟屏")
            "android_vscreen_list" -> setOf("虚拟屏", "虚拟显示列表")
            "android_vscreen_status" -> setOf("虚拟屏", "虚拟显示状态")
            "android_vscreen_launch" -> setOf("虚拟屏打开应用", "虚拟屏启动应用")
            "android_vscreen_tap" -> setOf("虚拟屏点击", "虚拟显示点击")
            "android_vscreen_swipe" -> setOf("虚拟屏滑动", "虚拟显示滑动")
            "android_vscreen_screenshot" -> setOf("虚拟屏截图", "虚拟显示截图")
            "android_vscreen_close" -> setOf("关闭虚拟屏", "关闭虚拟显示")
            else -> setOf(name)
        }
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
    val inputTool = capability in setOf(
        "accessibility_set_text_node", "accessibility_set_text", "accessibility_input_text",
    )
    val errorCode = when {
        inputTool && value == "input_target_unavailable" -> "ANDROID_INPUT_TARGET_UNAVAILABLE"
        inputTool && value == "input_action_unconfirmed" -> "ANDROID_INPUT_ACTION_UNCONFIRMED"
        inputTool && value == "input_readback_unconfirmed" -> "ANDROID_INPUT_READBACK_UNCONFIRMED"
        capability in ANDROID_BOOLEAN_ACTIONS && value == "false" -> "ANDROID_ACTION_UNCONFIRMED"
        else -> null
    }
    val message = when (errorCode) {
        "ANDROID_INPUT_TARGET_UNAVAILABLE" ->
            "当前界面找不到指定的可编辑控件；请使用 android_find 重新定位，优先选择控件 id 或当前焦点。"
        "ANDROID_INPUT_ACTION_UNCONFIRMED" ->
            "目标输入框已定位，但系统未确认接受文字写入请求；请读取当前控件内容核实状态。"
        "ANDROID_INPUT_READBACK_UNCONFIRMED" ->
            "系统已接受文字写入请求，尚未从同一输入框回读确认目标内容；先用 android_find/android_screen 核对实际文字，再决定是否修改。"
        "ANDROID_ACTION_UNCONFIRMED" ->
            "Android 未确认本次操作完成。请先重新检查当前界面或虚拟屏状态，再决定后续操作。"
        else -> value
    }
    return ToolResult(
        content = message,
        isError = errorCode != null,
        errorCode = errorCode,
        retryable = false,
        recoveryHint = if (errorCode != null) message else null,
        retention = retention,
    )
}
