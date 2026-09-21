package com.labteto.dshmobile.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.labteto.dshmobile.device.accessibility.HarnessAccessibilityService
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import com.labteto.dshmobile.device.shizuku.ShizukuBridge
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDeviceProvider(
    private val context: Context,
    private val shizuku: ShizukuBridge = ShizukuBridge(context),
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
        "accessibility_click_text",
        "accessibility_set_text",
        "accessibility_tap",
        "accessibility_swipe",
        "android_back",
        "android_home",
        "android_screenshot",
        "notification_list",
        "clipboard_get",
        "clipboard_set",
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
                        ?.let { " " + it } .orEmpty(),
            )
            "accessibility_tree" -> accessibilityTree()
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
            "notification_list" -> notificationList()
            "clipboard_get" -> clipboardGet()
            "clipboard_set" -> clipboardSet(arguments.required("text"))
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
                append(node.className ?: "?")
                append(" text=").append(node.text ?: "")
                append(" id=").append(node.viewId ?: "")
                append(" bounds=").append(node.bounds)
                if (node.clickable) append(" clickable")
                if (node.editable) append(" editable")
            }
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

    private suspend fun privileged(command: String): String =
        shizuku.execute(command)

    private fun accessibility(): HarnessAccessibilityService =
        HarnessAccessibilityService.active()
            ?: error("无障碍服务未授权或未连接")

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
