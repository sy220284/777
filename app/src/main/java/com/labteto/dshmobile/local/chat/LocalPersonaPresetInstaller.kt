package com.labteto.dshmobile.local.chat

import android.content.Context
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Chat owns bundled artwork staging and the single gallery commit, including failed-IO cleanup. */
@Singleton
internal class LocalPersonaPresetInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: ChatPersonaGalleryStore,
) {
    suspend fun install(preset: PersonaPreset): PersonaGalleryEntry = withContext(Dispatchers.IO) {
        var staged: File? = null
        try {
            preset.artwork?.let { artwork ->
                val path = artwork.assetPath.trim()
                require(path.startsWith("persona-presets/") && ".." !in path &&
                    path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")) {
                    "预置人物图片路径无效"
                }
                val root = File(context.filesDir, "local-harness/chat/persona-portraits")
                check(root.exists() || root.mkdirs()) { "无法创建人物立绘目录" }
                val image = File(root, "preset-${UUID.randomUUID()}.${path.substringAfterLast('.')}")
                staged = image
                context.assets.open(path).use { input ->
                    FileOutputStream(image).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PERSONA_PORTRAIT_BYTES) { "预置人物图片超过允许的大小" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "预置人物图片无法识别" }
            }
            coroutineContext.ensureActive()
            gallery.installPresetForUi(preset.persona, staged?.absolutePath)
        } finally {
            // A write may have committed before throwing; only the store can decide whether referenced.
            staged?.let { image ->
                try { gallery.cleanupDeletedPortrait(image.absolutePath) }
                catch (cleanup: Exception) {
                    com.labteto.dshmobile.observability.AppLog.warn("PersonaPreset", "failure=true operation=cleanup_unused_preset_artwork cause_type=${cleanup.javaClass.simpleName}")
                }
            }
        }
    }
}
