package com.labteto.dshmobile.local.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Project durable cross-provider history onto the selected protocol, without changing the
 * recorded messages. This is intentionally request-scoped, like Codex's modality-aware prompt
 * history projection. Unsupported active inputs remain explicit errors; older attachments are
 * represented honestly instead of poisoning every later request.
 */
internal fun projectCanonicalHistoryForProtocol(
    messages: List<LocalCanonicalMessage>,
    protocol: LocalModelProtocol,
): List<LocalCanonicalMessage> {
    if (protocol != LocalModelProtocol.ANTHROPIC_MESSAGES) return messages
    val currentUserIndex = messages.indexOfLast { it.role == LocalCanonicalRole.USER }
    return messages.mapIndexed { messageIndex, message ->
        if (message.role != LocalCanonicalRole.USER) return@mapIndexed message
        val isCurrentUserInput = messageIndex == currentUserIndex
        val projected = message.content.map { part ->
            if (part !is LocalCanonicalContent.Raw) return@map part
            val type = (part.value["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (type == "input_file" && isCurrentUserInput) {
                // Small inline text documents can be read by Anthropic as actual text, without
                // asking the user to upload them again or saving their Base64 into history.
                decodeInlineTextFile(part)?.let { return@map it }
            }
            if (isCurrentUserInput) part else LocalCanonicalContent.Text(
                historicalUnsupportedBlockReference(part, type),
            )
        }
        message.copy(content = projected)
    }
}

private const val MAX_INLINE_TEXT_FILE_BYTES = 24 * 1024

/** Bounded, strict UTF-8 decoding; no hidden decoding of binary/PDF/video data. */
private fun decodeInlineTextFile(block: LocalCanonicalContent.Raw): LocalCanonicalContent.Text? {
    val source = (block.value["file_data"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (!source.startsWith("data:", ignoreCase = true)) return null
    val comma = source.indexOf(',')
    if (comma <= 5) return null
    val header = source.substring(5, comma).lowercase()
    val mime = header.substringBefore(';')
    if (mime !in setOf("text/plain", "text/markdown", "text/csv", "text/tab-separated-values",
            "application/json", "application/xml", "text/xml", "text/html")) return null
    if (!header.split(';').contains("base64")) return null
    val encoded = source.substring(comma + 1)
    if (encoded.length > (MAX_INLINE_TEXT_FILE_BYTES + 2) / 3 * 4 + 4) return null
    val bytes = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.size > MAX_INLINE_TEXT_FILE_BYTES) return null
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        return null
    }
    if ('\u0000' in text) return null
    val filename = safeAttachmentName((block.value["filename"] as? JsonPrimitive)?.contentOrNull)
    return LocalCanonicalContent.Text("[附件内容：$filename，已自动转换为文本]\n$text\n[附件内容结束]")
}

private fun historicalUnsupportedBlockReference(
    block: LocalCanonicalContent.Raw,
    rawType: String,
): String {
    val type = rawType.take(48).takeIf {
        it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }
    } ?: "unknown"
    val filename = safeAttachmentName((block.value["filename"] as? JsonPrimitive)?.contentOrNull)
    val path = (block.value["path"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.length <= 240 && it.none { c -> c.isISOControl() } }
    return buildString {
        append("[历史附件/内容块：").append(type)
        if (filename != "未命名附件") append("，名称：").append(filename)
        if (path != null) append("，工作区路径：").append(path)
        append("。原始内容保留在本地历史，当前协议无法重放；")
        append(if (path != null) "需要查看时使用工作区文件工具。" else "需要查看时请重新附加原文件。")
        append("]")
    }
}

private fun safeAttachmentName(name: String?): String =
    name?.substringAfterLast('/').orEmpty().substringAfterLast('\\')
        .take(120).takeIf { it.isNotBlank() && it.none { c -> c.isISOControl() } }
        ?: "未命名附件"
