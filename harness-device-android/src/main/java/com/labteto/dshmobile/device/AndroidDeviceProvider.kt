package com.labteto.dshmobile.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.labteto.dshmobile.device.accessibility.AccessibilityNodeSnapshot
import com.labteto.dshmobile.device.accessibility.HarnessAccessibilityService
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import com.labteto.dshmobile.device.vscreen.VirtualDisplayController
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessVirtualDisplayProvider
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceLease
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun sanitizeNotificationField(value: String?, maxChars: Int): String {
    if (value.isNullOrBlank()) return ""
    val normalized = value.replace(Regex("""[\t\r\n]+"""), " ").trim()
    val redacted = NOTIFICATION_SECRET_PATTERN.replace(normalized) { match ->
        "${match.groupValues[1]}：[已脱敏]"
    }
    return if (redacted.length <= maxChars) redacted
    else redacted.take(maxChars).trimEnd() + "…"
}

private val NOTIFICATION_SECRET_PATTERN = Regex(
    """(?i)(验证码|校验码|动态码|一次性密码|otp|verification\s*code|password|密码)\s*[:：]?\s*[A-Za-z0-9_-]{4,32}""",
)

class AndroidDeviceProvider(
    private val context: Context,
    private val virtualDisplays: VirtualDisplayController = VirtualDisplayController(context),
    private val resourceScheduler: HarnessResourceScheduler? = null,
) : HarnessDeviceProvider, HarnessVirtualDisplayProvider {
    private val virtualDisplayLeases = ConcurrentHashMap<String, HarnessResourceLease>()

    override val capabilities: Set<String> = setOf(
        "device_info",
        "app_list",
        "app_info",
        "app_launch",
        "settings_get",
        "accessibility_tree",
        "accessibility_find",
        "accessibility_click_node",
        "accessibility_set_text_node",
        "accessibility_scroll",
        "accessibility_wait",
        "android_open_uri",
        "accessibility_click_text",
        "accessibility_set_text",
        "accessibility_tap",
        "accessibility_swipe",
        "android_back",
        "android_home",
        "android_screenshot",
        "notification_status",
        "notification_list",
        "clipboard_get",
        "clipboard_set",
        "vscreen_create",
        "vscreen_list",
        "vscreen_status",
        "vscreen_launch",
        "vscreen_tap",
        "vscreen_swipe",
        "vscreen_screenshot",
        "vscreen_close",
    )

    override suspend fun invoke(capability: String, arguments: Map<String, String>): String =
        when (capability) {
            "device_info" -> deviceInfo()
            "app_list" -> appList()
            "app_info" -> appInfo(arguments.required("package"))
            "app_launch" -> appLaunch(arguments.required("package"))
            "settings_get" -> settingsGet(
                arguments.required("namespace"),
                arguments.required("key"),
            )
            "accessibility_tree" -> accessibilityTree()
            "accessibility_find" -> accessibilityFind(arguments)
            "accessibility_click_node" ->
                accessibility().clickNode(arguments.deviceRequiredInt("node")).toString()
            "accessibility_set_text_node" ->
                accessibility().setTextNode(
                    arguments.deviceRequiredInt("node"),
                    arguments.required("value"),
                ).toString()
            "accessibility_scroll" -> accessibility().scroll(arguments["direction"] ?: "forward").toString()
            "accessibility_wait" -> accessibilityWait(arguments)
            "android_open_uri" -> openUri(arguments.required("uri"))
            "accessibility_click_text" ->
                accessibility().clickText(arguments.required("text")).toString()
            "accessibility_set_text" ->
                accessibility().setText(
                    arguments.required("search_text"),
                    arguments.required("value"),
                ).toString()
            "accessibility_tap" ->
                accessibility().tap(
                    arguments.deviceRequiredFiniteFloat("x"),
                    arguments.deviceRequiredFiniteFloat("y"),
                    arguments.deviceOptionalLong("duration_ms", 60L, 1L..60_000L),
                ).toString()
            "accessibility_swipe" ->
                accessibility().swipe(
                    arguments.deviceRequiredFiniteFloat("start_x"),
                    arguments.deviceRequiredFiniteFloat("start_y"),
                    arguments.deviceRequiredFiniteFloat("end_x"),
                    arguments.deviceRequiredFiniteFloat("end_y"),
                    arguments.deviceOptionalLong("duration_ms", 350L, 1L..60_000L),
                ).toString()
            "android_back" -> accessibility().globalBack().toString()
            "android_home" -> accessibility().globalHome().toString()
            "android_screenshot" -> {
                val png = accessibility().screenshotPng()
                "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
            }
            "notification_status" -> notificationStatus()
            "notification_list" -> notificationList()
            "clipboard_get" -> clipboardGet()
            "clipboard_set" -> clipboardSet(arguments.required("text"))
            "vscreen_create" -> {
                val lease = resourceScheduler?.acquire(
                    HarnessResourceKind.VIRTUAL_DISPLAY,
                    owner = "vscreen:tool",
                )
                try {
                    val status = virtualDisplays.create(
                        width = arguments.deviceOptionalInt("width", 1080),
                        height = arguments.deviceOptionalInt("height", 1920),
                        densityDpi = arguments.deviceOptionalInt("density_dpi", 420),
                    )
                    if (lease != null) virtualDisplayLeases[status.id] = lease
                    virtualStatus(status)
                } catch (error: Throwable) {
                    lease?.close()
                    throw error
                }
            }
            "vscreen_list" -> virtualDisplays.list().joinToString("\n", transform = ::virtualStatus)
            "vscreen_status" -> virtualStatus(virtualDisplays.status(arguments.required("id")))
            "vscreen_launch" -> virtualDisplays.launch(
                arguments.required("id"),
                arguments.required("package"),
            )
            "vscreen_tap" -> {
                val id = arguments.required("id")
                accessibility().tap(
                    x = arguments.deviceRequiredFiniteFloat("x"),
                    y = arguments.deviceRequiredFiniteFloat("y"),
                    durationMillis = arguments.deviceOptionalLong("duration_ms", 60L, 1L..60_000L),
                    displayId = virtualDisplays.displayId(id),
                ).toString()
            }
            "vscreen_swipe" -> {
                val id = arguments.required("id")
                accessibility().swipe(
                    startX = arguments.deviceRequiredFiniteFloat("start_x"),
                    startY = arguments.deviceRequiredFiniteFloat("start_y"),
                    endX = arguments.deviceRequiredFiniteFloat("end_x"),
                    endY = arguments.deviceRequiredFiniteFloat("end_y"),
                    durationMillis = arguments.deviceOptionalLong("duration_ms", 350L, 1L..60_000L),
                    displayId = virtualDisplays.displayId(id),
                ).toString()
            }
            "vscreen_screenshot" -> {
                val png = virtualDisplays.screenshotPng(arguments.required("id"))
                "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
            }
            "vscreen_close" -> {
                val id = arguments.required("id")
                try {
                    virtualDisplays.close(id).toString()
                } finally {
                    virtualDisplayLeases.remove(id)?.close()
                }
            }
            else -> error("设备能力不存在：$capability")
        }

    override suspend fun acquireAgentVirtualDisplay(owner: String): String {
        val lease = resourceScheduler?.acquire(
            HarnessResourceKind.VIRTUAL_DISPLAY,
            owner = "agent:" + owner.take(80),
        )
        return try {
            val status = virtualDisplays.create()
            if (lease != null) virtualDisplayLeases[status.id] = lease
            status.id
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    override fun releaseAgentVirtualDisplay(id: String) {
        runCatching { virtualDisplays.close(id) }
        virtualDisplayLeases.remove(id)?.close()
    }

    override fun close() {
        try {
            virtualDisplays.closeAll()
        } finally {
            virtualDisplayLeases.values.forEach { lease -> runCatching { lease.close() } }
            virtualDisplayLeases.clear()
        }
    }

    private fun deviceInfo(): String = buildString {
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        append("fingerprint=${Build.FINGERPRINT}")
    }

    private suspend fun appList(): String = withContext(Dispatchers.IO) {
        context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .map { it.packageName }
            .sorted()
            .joinToString("\n")
    }

    private fun appInfo(packageName: String): String {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        val app = info.applicationInfo
        return buildString {
            appendLine("package=${info.packageName}")
            appendLine("version_name=${info.versionName.orEmpty()}")
            appendLine("version_code=${info.longVersionCode}")
            appendLine("enabled=${app?.enabled == true}")
            append("system=${app?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 } == true}")
        }
    }

    private fun appLaunch(packageName: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("应用没有可启动入口：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "已启动 $packageName"
    }

    private fun settingsGet(namespace: String, key: String): String {
        val resolver = context.contentResolver
        return when (namespace.lowercase()) {
            "global" -> Settings.Global.getString(resolver, key)
            "secure" -> Settings.Secure.getString(resolver, key)
            "system" -> Settings.System.getString(resolver, key)
            else -> error("settings namespace 仅支持 global/secure/system")
        }.orEmpty()
    }

    private fun accessibilityTree(): String =
        accessibility().snapshot().joinToString("\n") { node ->
            buildString {
                repeat(node.depth.coerceAtMost(12)) { append("  ") }
                append("node=").append(node.index).append(' ')
                append(node.className ?: "?")
                append(" text=").append(node.text ?: node.contentDescription ?: "")
                append(" desc=").append(node.contentDescription ?: "")
                append(" id=").append(node.viewId ?: "")
                append(" bounds=").append(node.bounds)
                if (node.clickable) append(" clickable")
                if (node.editable) append(" editable")
            }
        }

    private fun accessibilityFind(arguments: Map<String, String>): String {
        val text = arguments["text"]?.takeIf(String::isNotBlank)
        val viewId = arguments["id"]?.takeIf(String::isNotBlank)
        val className = arguments["class"]?.takeIf(String::isNotBlank)
        val clickable = arguments.deviceOptionalBoolean("clickable")
        val editable = arguments.deviceOptionalBoolean("editable")
        require(
            text != null || viewId != null || className != null || clickable != null || editable != null,
        ) { "android_find 至少需要 text、id、class、clickable 或 editable 之一" }
        val matches = accessibility().snapshot(800).filter { node ->
            (text == null || listOf(node.text, node.contentDescription).any {
                it?.contains(text, ignoreCase = true) == true
            }) &&
                (viewId == null || node.viewId?.contains(viewId, ignoreCase = true) == true) &&
                (className == null || node.className?.contains(className, ignoreCase = true) == true) &&
                (clickable == null || node.clickable == clickable) &&
                (editable == null || node.editable == editable)
        }.take(50)
        if (matches.isEmpty()) return "未找到匹配控件"
        return matches.joinToString("\n") { node ->
            buildString {
                append("node=").append(node.index)
                append(" text=").append(node.text ?: "")
                append(" desc=").append(node.contentDescription ?: "")
                append(" id=").append(node.viewId ?: "")
                append(" class=").append(node.className ?: "")
                append(" bounds=").append(node.bounds)
                if (node.clickable) append(" clickable")
                if (node.editable) append(" editable")
            }
        }
    }

    private suspend fun accessibilityWait(arguments: Map<String, String>): String {
        val text = arguments["text"]?.takeIf(String::isNotBlank)
        val viewId = arguments["id"]?.takeIf(String::isNotBlank)
        require(text != null || viewId != null) { "android_wait 至少需要 text 或 id" }
        val timeout = arguments.deviceOptionalLong("timeout_ms", 10_000L, 100L..60_000L)
        val match: AccessibilityNodeSnapshot = withTimeoutOrNull(timeout) {
            var found: AccessibilityNodeSnapshot? = null
            while (found == null) {
                found = accessibility().snapshot(800).firstOrNull { node ->
                    (text == null || listOf(node.text, node.contentDescription).any {
                        it?.contains(text, ignoreCase = true) == true
                    }) &&
                        (viewId == null || node.viewId?.contains(viewId, ignoreCase = true) == true)
                }
                if (found == null) delay(150L)
            }
            found
        } ?: error("等待控件超时（${timeout}ms）")
        return "已找到：node=${match.index} text=${match.text ?: match.contentDescription ?: ""} id=${match.viewId ?: ""} bounds=${match.bounds}"
    }

    private fun openUri(raw: String): String {
        val uri = Uri.parse(raw)
        require(uri.scheme?.lowercase() in SAFE_VIEW_SCHEMES) { "URI scheme 不在允许列表" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "已打开 URI：${uri.scheme}://"
    }

    private fun notificationStatus(): String =
        if (HarnessNotificationListenerService.active() != null) {
            "authorized=true\nconnected=true"
        } else {
            "authorized=false\nconnected=false\n请在 Android 通知使用权设置中授权 777 后重试"
        }

    private fun notificationList(): String {
        val service = HarnessNotificationListenerService.active()
            ?: error("通知读取服务未授权或未连接")
        val rows = service.snapshots()
            .sortedByDescending { it.postedAt }
            .take(MAX_NOTIFICATION_ROWS)
            .joinToString("\n") { item ->
                val title = sanitizeNotificationField(item.title, MAX_NOTIFICATION_TITLE_CHARS)
                val text = sanitizeNotificationField(item.text, MAX_NOTIFICATION_TEXT_CHARS)
                "${item.packageName}\t$title\t$text\t${item.postedAt}"
            }
        return buildString {
            appendLine("隐私提示：通知标题/正文已限长，并对常见验证码与密码模式脱敏；该工具结果会进入当前会话历史。")
            append(rows)
        }.trimEnd()
    }

    private fun clipboardGet(): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip ?: return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    private fun clipboardSet(text: String): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("777 Harness", text))
        return "已写入剪贴板"
    }

    private fun virtualStatus(status: com.labteto.dshmobile.device.vscreen.VirtualDisplayStatus): String =
        "id=${status.id}\ndisplay_id=${status.displayId}\nsize=${status.width}x${status.height}\ndensity=${status.densityDpi}\nvalid=${status.valid}"

    private fun accessibility(): HarnessAccessibilityService =
        HarnessAccessibilityService.active()
            ?: error(
                "无障碍服务未授权或未连接。请在 Android 系统设置的无障碍页面启用 777 服务，返回应用后重试；若已启用仍不可用，请关闭后重新启用一次",
            )

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")

    private companion object {
        const val MAX_NOTIFICATION_ROWS = 50
        const val MAX_NOTIFICATION_TITLE_CHARS = 120
        const val MAX_NOTIFICATION_TEXT_CHARS = 320
        val SAFE_VIEW_SCHEMES = setOf("http", "https", "market", "geo", "mailto", "tel")
    }
}

internal fun Map<String, String>.deviceRequiredInt(key: String): Int {
    val raw = this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")
    return raw.toIntOrNull() ?: throw IllegalArgumentException("$key 必须是整数")
}

internal fun Map<String, String>.deviceRequiredFiniteFloat(key: String): Float {
    val raw = this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")
    val value = raw.toFloatOrNull() ?: throw IllegalArgumentException("$key 必须是数字")
    require(value.isFinite()) { "$key 必须是有限数字" }
    return value
}

internal fun Map<String, String>.deviceOptionalInt(key: String, default: Int): Int {
    val raw = this[key] ?: return default
    require(raw.isNotBlank()) { "$key 不能为空" }
    return raw.toIntOrNull() ?: throw IllegalArgumentException("$key 必须是整数")
}

internal fun Map<String, String>.deviceOptionalLong(
    key: String,
    default: Long,
    range: LongRange,
): Long {
    val raw = this[key] ?: return default
    require(raw.isNotBlank()) { "$key 不能为空" }
    val value = raw.toLongOrNull() ?: throw IllegalArgumentException("$key 必须是整数")
    require(value in range) { "$key 必须在 ${range.first}..${range.last} 之间" }
    return value
}

internal fun Map<String, String>.deviceOptionalBoolean(key: String): Boolean? {
    val raw = this[key] ?: return null
    return raw.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("$key 必须是 true 或 false")
}
