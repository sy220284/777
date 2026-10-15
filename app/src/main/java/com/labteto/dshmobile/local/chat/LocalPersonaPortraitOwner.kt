package com.labteto.dshmobile.local.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import com.labteto.dshmobile.uicontract.PersonaPortraitUiCommand
import com.labteto.dshmobile.uicontract.PersonaPortraitUiPort
import com.labteto.dshmobile.uicontract.UiPersonaPortraitReceipt
import com.labteto.dshmobile.uicontract.UiPersonaPortraitPreviewReceipt
import java.io.ByteArrayOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal const val MAX_PERSONA_PORTRAIT_BYTES = 20L * 1024L * 1024L

/** Chat owns managed portrait files and the gallery writer. UI supplies only a picker grant. */
@Singleton
class LocalPersonaPortraitOwner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: ChatPersonaGalleryStore,
) : PersonaPortraitUiPort {
    override val maxImportBytes: Long get() = MAX_PERSONA_PORTRAIT_BYTES
    override suspend fun preview(galleryId: String): UiPersonaPortraitPreviewReceipt = withContext(Dispatchers.IO) {
        try {
            val path = gallery.findEntry(galleryId)?.portraitPath?.takeIf(String::isNotBlank)
                ?: return@withContext UiPersonaPortraitPreviewReceipt.Missing
            val root = File(context.filesDir, "local-harness/chat/persona-portraits").canonicalFile
            val image = File(path).canonicalFile
            require(image.parentFile == root) { "人物立绘不在受管理的图片目录" }
            if (!image.isFile) return@withContext UiPersonaPortraitPreviewReceipt.Missing
            val bytes = image.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= maxImportBytes) { "人物立绘超过允许的图片大小" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (gallery.findEntry(galleryId)?.portraitPath != path)
                UiPersonaPortraitPreviewReceipt.Failed("人物立绘已变化，请重新打开预览")
            else UiPersonaPortraitPreviewReceipt.Ready(bytes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiPersonaPortraitPreviewReceipt.Failed(failure.message) }
    }

    override suspend fun submit(command: PersonaPortraitUiCommand): UiPersonaPortraitReceipt = withContext(Dispatchers.IO) {
        var target: File? = null
        try {
            if (gallery.findEntry(command.galleryId) == null) return@withContext UiPersonaPortraitReceipt.Missing
            val path = when (command) {
                is PersonaPortraitUiCommand.Remove -> ""
                is PersonaPortraitUiCommand.Import -> {
                    val uri = Uri.parse(command.contentUri)
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri).orEmpty()
                    require(mime.startsWith("image/")) { "请选择图片文件" }
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
                        ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") } ?: "jpg"
                    val root = File(context.filesDir, "local-harness/chat/persona-portraits")
                    check(root.exists() || root.mkdirs()) { "无法创建人物立绘目录" }
                    val destination = File(root, "${UUID.randomUUID()}.$extension")
                    target = destination
                    requireNotNull(resolver.openInputStream(uri)) { "无法读取人物形象图" }.use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_PERSONA_PORTRAIT_BYTES) { "人物形象图过大，请选择 20MB 以内的图片" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destination.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "人物形象图无法识别" }
                    destination.absolutePath
                }
            }
            coroutineContext.ensureActive()
            val previous = gallery.replacePortraitForUi(command.galleryId, path)
                ?: return@withContext UiPersonaPortraitReceipt.Missing
            val warning = try { gallery.cleanupDeletedPortrait(previous); null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failure.message ?: "立绘已更新，旧图片清理未完成" }
            UiPersonaPortraitReceipt.Updated(command.galleryId, warning)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { UiPersonaPortraitReceipt.Failed(failure.message) }
        finally {
            // Also protects a target referenced by a write that committed before throwing.
            target?.let { candidate ->
                try { gallery.cleanupDeletedPortrait(candidate.absolutePath) }
                catch (failure: Exception) { com.labteto.dshmobile.observability.AppLog.warn("PersonaPortrait", "未引用立绘清理失败: ${failure.message}") }
            }
        }
    }
}
