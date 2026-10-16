package com.labteto.dshmobile.local.attachment

import com.labteto.dshmobile.uicontract.UiAttachmentInspection
import com.labteto.dshmobile.uicontract.UiAttachmentInspectionReceipt
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Explicit bounded read of an app-imported content-addressed file.
 * Never exposes generic workspace paths, follows symlinks, or treats a size check as SHA-256.
 * Full integrity is checked as a stream even for large attachments (bounded 32 KiB buffer).
 */
internal fun inspectImportedAttachmentFile(root: File, item: LocalImportedAttachment):
    UiAttachmentInspectionReceipt {
    fun unavailable(message: String) = UiAttachmentInspectionReceipt.Unavailable(message)
    val name = Regex("^\\.dsh/attachments/([a-f0-9]{64})(?:\\.[a-z0-9]{1,10})?$")
        .matchEntire(item.relativePath) ?: return unavailable("附件路径无效")
    val digestName = name.groupValues[1]
    if (item.attachmentId != null && item.attachmentId != digestName)
        return unavailable("附件标识与路径不一致")
    if (item.bytes < 0L) return unavailable("附件大小无效")
    return try {
        val hidden = File(root, ".dsh").toPath()
        val folder = hidden.resolve("attachments")
        val candidate = folder.resolve(item.relativePath.substringAfterLast('/'))
        if (Files.isSymbolicLink(hidden) || Files.isSymbolicLink(folder) ||
            !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
            return unavailable("附件文件不可安全读取")
        val actualLength = Files.size(candidate)
        if (actualLength != item.bytes) return unavailable("附件文件大小与导入记录不一致")

        // Byte count alone cannot detect in-place tampering of a content-addressed file.
        // Keep memory bounded regardless of attachment size; inspect runs on the File Owner IO dispatcher.
        val extension = item.relativePath.substringAfterLast('.', "").lowercase()
        val textType = extension in setOf(
            "txt", "md", "json", "csv", "xml", "html", "log",
            "yaml", "yml", "kt", "py", "js", "ts",
        )
        val previewLimit = if (textType) 16_384 else 0
        val sample = ByteArrayOutputStream()
        val hash = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        Files.newByteChannel(candidate, setOf<OpenOption>(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
        )).use { channel ->
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    hash.update(buffer, 0, size)
                    bytesRead += size
                    if (sample.size() < previewLimit)
                        sample.write(buffer, 0, minOf(size, previewLimit - sample.size()))
                    }
            }
        }
        if (bytesRead != actualLength || Files.size(candidate) != actualLength)
            return unavailable("附件读取期间大小发生变化")
        val actualDigest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actualDigest != digestName) return unavailable("附件内容完整性核验失败")
        val previewData = sample.toByteArray()
        val text = if (previewData.isNotEmpty() && 0.toByte() !in previewData)
            runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(previewData)).toString()
            }.getOrNull() else null
        UiAttachmentInspectionReceipt.Ready(
            UiAttachmentInspection(
                item.name.take(160), item.mediaType.take(100), actualLength,
                true, text, text != null && actualLength > previewData.size,
            ),
        )
    } catch (_: Exception) {
        unavailable("附件读取或检查失败")
    }
}
