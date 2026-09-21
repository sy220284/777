package com.labteto.dshmobile.device.vscreen

import android.app.ActivityOptions
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
        val display = manager.createVirtualDisplay(
            "777-harness-$id",
            width,
            height,
            densityDpi,
            reader.surface,
            flags,
        ) ?: run {
            reader.close()
            error("系统拒绝创建虚拟屏")
        }
        sessions[id] = Session(id, width, height, densityDpi, reader, display)
        return status(id)
    }

    fun status(id: String): VirtualDisplayStatus {
        val session = sessions[id] ?: error("虚拟屏不存在：$id")
        return VirtualDisplayStatus(
            id = id,
            displayId = session.display.display.displayId,
            width = session.width,
            height = session.height,
            densityDpi = session.densityDpi,
            valid = session.display.display.isValid,
        )
    }

    fun list(): List<VirtualDisplayStatus> = sessions.keys.sorted().map(::status)

    fun launch(id: String, packageName: String): String {
        val session = sessions[id] ?: error("虚拟屏不存在：$id")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("应用没有可启动入口：$packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(session.display.display.displayId)
            .toBundle()
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
