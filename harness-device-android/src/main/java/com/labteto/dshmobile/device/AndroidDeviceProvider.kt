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
import com.labteto.dshmobile.device.shizuku.ShizukuBridge
import com.labteto.dshmobile.device.vscreen.VirtualDisplayController
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidDeviceProvider(
    private val context: Context,
    private val shizuku: ShizukuBridge = ShizukuBridge(context),
    private val virtualDisplays: VirtualDisplayController = VirtualDisplayController(context),
) : HarnessDeviceProvider {

    override val capabilities: Set<String> = setOf(
        "device_info",
        "shizuku_status",
        "shizuku_request_permission",
        "app_list",
        "app_info",
        "app_launch",
        "app_stop",
        "settings_get",
        "settings_set",
        "dumpsys",
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
            "shizuku_status" -> shizukuStatus()
            "shizuku_request_permission" -> {
                shizuku.requestPermission(arguments["request_code"]?.toIntOrNull() ?: 771)
                "已请求 Shizuku 权限"
            }
            "app_list" -> appList()
            "app_info" -> appInfo(arguments.required("package"))
            "app_launch" -> appLaunch(arguments.required("package"))
            "app_stop" -> privileged("am force-stop ${shellQuote(arguments.required("package"))}")
            "settings_get" -> settingsGet(
                arguments.required("namespace"),
                arguments.required("key"),
            )
            "settings_set" -> settingsSet(
                arguments.required("namespace"),
                arguments.required("key"),
                arguments.required("value"),
            )
            "dumpsys" -> privileged(
                "dumpsys " + shellQuote(arguments.required("service")) +
                    arguments["args"].orEmpty().takeIf(String::isNotBlank)
                        ?.let { value -> " " + value.trim().split(Regex("\\s+")).joinToString(" ", transform = ::shellQuote) }.orEmpty(),
            )
            "accessibility_tree" -> accessibilityTree()
            "accessibility_find" -> accessibilityFind(arguments)
            "accessibility_click_node" ->
                accessibility().clickNode(arguments.required("node").toInt()).toString()
            "accessibility_set_text_node" ->
                accessibility().setTextNode(
                    arguments.required("node").toInt(),
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
                    arguments.required("x").toFloat(),
                    arguments.required("y").toFloat(),
                    arguments["duration_ms"]?.toLongOrNull() ?: 60L,
                ).toString()
            "accessibility_swipe" ->
                accessibility().swipe(
                    arguments.required("start_x").toFloat(),
                    arguments.required("start_y").toFloat(),
                    arguments.required("end_x").toFloat(),
                    arguments.required("end_y").toFloat(),
                    arguments["duration_ms"]?.toLongOrNull() ?: 350L,
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
                val status = virtualDisplays.create(
                    width = arguments["width"]?.toIntOrNull() ?: 1080,
                    height = arguments["height"]?.toIntOrNull() ?: 1920,
                    densityDpi = arguments["density_dpi"]?.toIntOrNull() ?: 420,
                )
                virtualStatus(status)
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
                    x = arguments.required("x").toFloat(),
                    y = arguments.required("y").toFloat(),
                    durationMillis = arguments["duration_ms"]?.toLongOrNull() ?: 60L,
                    displayId = virtualDisplays.displayId(id),
                ).toString()
            }
            "vscreen_swipe" -> {
                val id = arguments.required("id")
                accessibility().swipe(
                    startX = arguments.required("start_x").toFloat(),
                    startY = arguments.required("start_y").toFloat(),
                    endX = arguments.required("end_x").toFloat(),
                    endY = arguments.required("end_y").toFloat(),
                    durationMillis = arguments["duration_ms"]?.toLongOrNull() ?: 350L,
                    displayId = virtualDisplays.displayId(id),
                ).toString()
            }
            "vscreen_screenshot" -> {
                val png = virtualDisplays.screenshotPng(arguments.required("id"))
                "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
            }
            "vscreen_close" -> virtualDisplays.close(arguments.required("id")).toString()
            else -> error("设备能力不存在：$capability")
        }

    private fun deviceInfo(): String = buildString {
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        append("fingerprint=${Build.FINGERPRINT}")
    }

    private fun shizukuStatus(): String {
        val state = shizuku.state()
        return "binder_alive=${state.binderAlive}\npermission=${state.permissionGranted}\nuid=${state.uid ?: -1}"
    }

    private suspend fun appList(): String = withContext(Dispatchers.IO) {
        if (shizuku.state().permissionGranted) {
            return@withContext privileged("pm list packages")
        }
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

    private suspend fun settingsSet(namespace: String, key: String, value: String): String {
        require(namespace.lowercase() in setOf("global", "secure", "system")) {
            "settings namespace 仅支持 global/secure/system"
        }
        return privileged(
            "settings put ${shellQuote(namespace.lowercase())} ${shellQuote(key)} ${shellQuote(value)}",
        )
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
        val clickable = arguments["clickable"]?.toBooleanStrictOrNull()
        val editable = arguments["editable"]?.toBooleanStrictOrNull()
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
        val timeout = (arguments["timeout_ms"]?.toLongOrNull() ?: 10_000L).coerceIn(100L, 60_000L)
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
        return service.snapshots().joinToString("\n") { item ->
            "${item.packageName}\t${item.title.orEmpty()}\t${item.text.orEmpty()}\t${item.postedAt}"
        }
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

    private suspend fun privileged(command: String): String =
        shizuku.execute(command)

    private fun accessibility(): HarnessAccessibilityService =
        HarnessAccessibilityService.active()
            ?: error("无障碍服务未授权或未连接")

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        val SAFE_VIEW_SCHEMES = setOf("http", "https", "market", "geo", "mailto", "tel")
    }
}
