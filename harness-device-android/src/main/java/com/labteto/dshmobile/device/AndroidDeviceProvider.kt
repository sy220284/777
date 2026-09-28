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
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import com.labteto.dshmobile.device.vscreen.VirtualDisplayController
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceLease
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDeviceProvider(
    private val context: Context,
    private val virtualDisplays: VirtualDisplayController = VirtualDisplayController(context),
    private val resourceScheduler: HarnessResourceScheduler? = null,
) : HarnessDeviceProvider {
    private val virtualDisplayLeases = ConcurrentHashMap<String, HarnessResourceLease>()

    override val capabilities: Set<String> = setOf(
        "device_info",
        "app_list",
        "app_info",
        "app_launch",
        "settings_get",
        "android_open_uri",
        "notification_status",
        "notification_list",
        "clipboard_get",
        "clipboard_set",
        "vscreen_create",
        "vscreen_list",
        "vscreen_status",
        "vscreen_launch",
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
            "android_open_uri" -> openUri(arguments.required("uri"))
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
                        width = arguments["width"]?.toIntOrNull() ?: 1080,
                        height = arguments["height"]?.toIntOrNull() ?: 1920,
                        densityDpi = arguments["density_dpi"]?.toIntOrNull() ?: 420,
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

    suspend fun acquireAgentVirtualDisplay(owner: String): String {
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

    fun releaseAgentVirtualDisplay(id: String) {
        runCatching { virtualDisplays.close(id) }
        virtualDisplayLeases.remove(id)?.close()
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

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")

    private companion object {
        val SAFE_VIEW_SCHEMES = setOf("http", "https", "market", "geo", "mailto", "tel")
    }
}
