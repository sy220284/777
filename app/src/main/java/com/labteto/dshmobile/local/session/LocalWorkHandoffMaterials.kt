package com.labteto.dshmobile.local.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


/**
 * Only references produced by the existing on-device attachment importer are handed
 * to Work. The file bytes remain under the shared workspace and must still be
 * revalidated by the workspace tool at actual read time.
 */
private val WORK_HANDOFF_ATTACHMENT_PATH =
    Regex("""^\.dsh/attachments/([a-f0-9]{64})(?:\.[a-z0-9]{1,10})?$""")

private data class WorkHandoffMedia(val name: String, val path: String, val sha256: String)

internal fun LocalHarnessMessage.handoffAttachmentNames(): List<String> =
    blocks.mapNotNull { block ->
        when (block) {
            is LocalMessageBlock.Image -> block.name
            is LocalMessageBlock.File -> block.name
            else -> null
        }
    }.map { it.replace(Regex("""[\r\n\t]"""), " ").take(100) }.take(6)

private fun LocalHarnessMessage.handoffSafeMedia(): List<WorkHandoffMedia> =
    blocks.mapNotNull { block ->
        val media = when (block) {
            is LocalMessageBlock.Image ->
                if (block.source == LocalMessageMediaSource.USER)
                    Triple(block.name, block.relativePath, block.attachmentId) to block.bytes
                else null
            is LocalMessageBlock.File ->
                if (block.source == LocalMessageMediaSource.USER)
                    Triple(block.name, block.relativePath, block.attachmentId) to block.bytes
                else null
            else -> null
        } ?: return@mapNotNull null
        val (detail, bytes) = media
        if (bytes < 0L) return@mapNotNull null
        val (name, path, id) = detail
        val match = WORK_HANDOFF_ATTACHMENT_PATH.matchEntire(path) ?: return@mapNotNull null
        if (id?.lowercase() != match.groupValues[1]) return@mapNotNull null
        WorkHandoffMedia(
            name = name.replace(Regex("""[\r\n\t]"""), " ").take(100),
            path = path, sha256 = match.groupValues[1],
        )
    }.distinctBy(WorkHandoffMedia::path).take(6)

internal fun LocalHarnessMessage.workHandoffVisibleMessage(): LocalHarnessMessage? {
    if (id.isBlank() || toolName != null || proactive) return null
    val text = when (role) {
        "user" -> com.labteto.dshmobile.local.presentation.editableChatUserText(this)
        "assistant" -> com.labteto.dshmobile.local.model.visibleAssistantText(content)
        else -> return null
    }
    return copy(content = text).takeIf { text.isNotBlank() || it.handoffAttachmentNames().isNotEmpty() }
}

internal fun LocalHarnessMessage.matchesWorkHandoffQuery(term: String): Boolean =
    content.contains(term, ignoreCase = true) || handoffAttachmentNames().any { it.contains(term, ignoreCase = true) }

/** Only public dialogue selected by the user may be passed into Work. */
internal fun workHandoffDialogueCandidates(
    older: List<LocalHarnessMessage>,
    live: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    val recent = LinkedHashMap<String, LocalHarnessMessage>()
    (older + live).forEach { message ->
        message.workHandoffVisibleMessage()?.let { visible -> recent[visible.id] = visible }
    }
    return recent.values.toList()
}

/** Search every loaded public message, show a small recent window without dropping selected older ones. */
internal fun visibleWorkHandoffMessages(
    messages: List<LocalHarnessMessage>,
    query: String,
    selectedIds: Collection<String>,
): List<LocalHarnessMessage> {
    val term = query.trim()
    val selected = selectedIds.toSet()
    return if (term.isBlank()) {
        val recent = messages.takeLast(12).mapTo(hashSetOf(), LocalHarnessMessage::id)
        messages.filter { it.id in selected || it.id in recent }
    } else {
        val matching = messages.filter { it.matchesWorkHandoffQuery(term) }.takeLast(30)
        (messages.filter { it.id in selected } + matching).distinctBy(LocalHarnessMessage::id)
    }
}


@Serializable
internal data class WorkHandoffDocumentAttachment(
    val name: String, val path: String, val sha256: String,
)

@Serializable
internal data class WorkHandoffDocumentMessage(
    val messageId: String,
    val role: String,
    val text: String,
    val attachments: List<WorkHandoffDocumentAttachment>,
)

@Serializable
internal data class WorkHandoffDocument(
    val version: Int = 1,
    val sourceSessionId: String,
    val messages: List<WorkHandoffDocumentMessage>,
)

/** Immutable full-text material: UI excerpts may be short, the owned file is never clipped. */
internal fun workHandoffFullDocument(
    sourceSessionId: String,
    candidates: List<LocalHarnessMessage>,
    selectedIds: List<String>,
): String {
    require(sourceSessionId.isNotBlank() && selectedIds.isNotEmpty()) { "未选择可交接的原对话" }
    val byId = candidates.associateBy(LocalHarnessMessage::id)
    val messages = selectedIds.distinct().map { id ->
        val original = byId[id]?.workHandoffVisibleMessage()
            ?: error("所选消息已失效，请重新选择：$id")
        WorkHandoffDocumentMessage(
            messageId = original.id,
            role = original.role,
            text = original.content,
            attachments = original.handoffSafeMedia().map {
                WorkHandoffDocumentAttachment(it.name, it.path, it.sha256)
            },
        )
    }
    return Json.encodeToString(WorkHandoffDocument(sourceSessionId = sourceSessionId, messages = messages))
}

/** Preserve the editable draft and carry traceable references to explicitly selected messages. */
internal fun workHandoffSummaryWithSelectedMessages(
    summary: String,
    sourceSessionId: String,
    candidates: List<LocalHarnessMessage>,
    selectedIds: List<String>,
    fullTextReference: String? = null,
): String {
    if (selectedIds.isEmpty()) return summary
    val byId = candidates.associateBy(LocalHarnessMessage::id)
    val chosen = selectedIds.distinct().mapNotNull(byId::get).filter { message ->
        (message.content.isNotBlank() || message.handoffAttachmentNames().isNotEmpty()) &&
            message.toolName == null && !message.proactive &&
            (message.role == "user" || message.role == "assistant")
    }
    if (chosen.isEmpty()) return summary
    return buildString {
        summary.trim().takeIf(String::isNotBlank)?.let { appendLine(it); appendLine() }
        appendLine("【用户选定的原对话材料】")
        appendLine("来源会话：$sourceSessionId")
        if (fullTextReference != null) {
            appendLine("完整原文：工作区/$fullTextReference（保留所有选定消息的原始文字及换行）")
            appendLine("执行工作前，先使用工作区读取工具打开此文件，长内容逐段读完。")
        }
        val previews = if (fullTextReference != null) chosen.take(12) else chosen
        previews.forEach { message ->
            val speaker = if (message.role == "user") "用户" else "助手"
            val normalized = message.content.trim().replace(Regex("\\s+"), " ")
            val excerptLimit = if (fullTextReference == null) 1_000 else 220
            val excerpt = normalized.take(excerptLimit)
            appendLine("- $speaker [消息ID: ${message.id}]：" +
                if (normalized.isBlank()) "（仅包含附件）"
                else excerpt + if (normalized.length > excerptLimit) "…（节选）" else "")
            val media = message.handoffSafeMedia()
            media.forEach { attachment ->
                appendLine("  - 已选附件：${attachment.name}；工作区路径：${attachment.path}；" +
                    "SHA-256：${attachment.sha256}（读取时重新校验文件及权限）")
            }
            if (message.handoffAttachmentNames().size > media.size) {
                appendLine("  - 部分旧附件缺少可验证的本地引用，请在工作中重新选择原文件")
            }
        }
        if (previews.size < chosen.size) {
            appendLine("其余 ${chosen.size - previews.size} 条消息的全文保存在上述交接文件中。")
        }
        append("把这些材料用作用户指定的本轮参考，依据当前授权执行，按实际回执核对工作进度。")
    }
}
