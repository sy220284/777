package com.labteto.dshmobile.device.vscreen

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class VirtualDisplayStatus(
    val id: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val valid: Boolean,
)

internal inline fun <T> releaseResourceOnFailure(
    release: () -> Unit,
    block: () -> T,
): T {
    try {
        return block()
    } catch (error: Throwable) {
        runCatching { release() }
        throw error
    }
}

class VirtualDisplayController(
    private val context: Context,
) {
    private data class Session(
        val id: String,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val imageReader: ImageReader,
        val display: VirtualDisplay,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(
        width: Int = 1080,
        height: Int = 1920,
        densityDpi: Int = 420,
    ): VirtualDisplayStatus {
        require(width in 320..4096 && height in 320..4096) { "虚拟屏尺寸超出范围" }
        require(densityDpi in 120..800) { "虚拟屏密度超出范围" }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val manager = context.getSystemService(DisplayManager::class.java)
        val id = "vd-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        return releaseResourceOnFailure(reader::close) {
            val display = manager.createVirtualDisplay(
                "777-harness-$id",
                width,
                height,
                densityDpi,
                reader.surface,
                flags,
            ) ?: error("系统拒绝创建虚拟屏")
            releaseResourceOnFailure(display::release) {
                val session = Session(id, width, height, densityDpi, reader, display)
                val createdStatus = statusOf(session)
                sessions[id] = session
                createdStatus
            }
        }
    }

    fun status(id: String): VirtualDisplayStatus {
        val session = sessions[id] ?: error("虚拟屏不存在：$id")
        return statusOf(session)
    }

    fun list(): List<VirtualDisplayStatus> =
        sessions.values.toList()
            .sortedBy(Session::id)
            .map(::statusOf)

    private fun statusOf(session: Session): VirtualDisplayStatus = VirtualDisplayStatus(
        id = session.id,
        displayId = session.display.display.displayId,
        width = session.width,
        height = session.height,
        densityDpi = session.densityDpi,
        valid = session.display.display.isValid,
    )

    fun launch(id: String, packageName: String): String {
        val session = sessions[id] ?: error("虚拟屏不存在：$id")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("应用没有可启动入口：$packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val displayId = session.display.display.displayId
        check(session.display.display.isValid) { "虚拟屏已失效：$id；请关闭后重新创建" }
        check(context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
        )) { "设备不支持在独立显示器启动应用；请使用主屏操作能力" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(ActivityManager::class.java)
            if (!manager.isActivityStartAllowedOnDisplay(context, displayId, intent)) {
                throw SecurityException(
                    "Android 拒绝将 $packageName 启动到虚拟显示器 $displayId：" +
                        "当前应用没有该目标显示器的活动启动许可；可用 android_vscreen_close 回收槽位。",
                )
            }
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        context.startActivity(intent, options)
        return "已在虚拟屏 $id 启动 $packageName"
    }

    suspend fun screenshotPng(id: String, timeoutMillis: Long = 3_000L): ByteArray {
        val session = sessions[id] ?: error("虚拟屏不存在：$id")
        return withTimeout(timeoutMillis.coerceIn(100L, 10_000L)) {
            while (true) {
                val image = session.imageReader.acquireLatestImage()
                if (image == null) {
                    delay(16)
                    continue
                }
                try {
                    return@withTimeout withContext(Dispatchers.Default) {
                        val plane = image.planes.first()
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * session.width
                        val paddedWidth = session.width + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(
                            paddedWidth,
                            session.height,
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            session.width,
                            session.height,
                        )
                        if (cropped !== bitmap) bitmap.recycle()
                        val output = ByteArrayOutputStream()
                        try {
                            check(cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "虚拟屏截图编码失败"
                            }
                            output.toByteArray()
                        } finally {
                            cropped.recycle()
                        }
                    }
                } finally {
                    image.close()
                }
            }
            error("不可达")
        }
    }

    fun close(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        runCatching { session.display.release() }
        runCatching { session.imageReader.close() }
        return true
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    fun displayId(id: String): Int =
        sessions[id]?.display?.display?.displayId ?: error("虚拟屏不存在：$id")
}
