package com.labteto.dshmobile.local.session


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

/** Only public dialogue selected by the user may be passed into Work. */
internal fun workHandoffDialogueCandidates(
    older: List<LocalHarnessMessage>,
    live: List<LocalHarnessMessage>,
): List<LocalHarnessMessage> {
    val recent = LinkedHashMap<String, LocalHarnessMessage>()
    (older + live).forEach { message ->
        if (message.id.isNotBlank() &&
            (message.content.isNotBlank() || message.handoffAttachmentNames().isNotEmpty()) &&
            message.toolName == null && !message.proactive &&
            (message.role == "user" || message.role == "assistant")
        ) {
            recent[message.id] = message
        }
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
        messages.filter { message ->
            message.content.contains(term, ignoreCase = true) ||
                message.handoffAttachmentNames().any { it.contains(term, ignoreCase = true) }
        }
            .takeLast(30)
    }
}


/** Preserve the editable draft and carry traceable references to explicitly selected messages. */
internal fun workHandoffSummaryWithSelectedMessages(
    summary: String,
    sourceSessionId: String,
    candidates: List<LocalHarnessMessage>,
    selectedIds: List<String>,
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
        chosen.forEach { message ->
            val speaker = if (message.role == "user") "用户" else "助手"
            val normalized = message.content.trim().replace(Regex("\\s+"), " ")
            val excerpt = normalized.take(1_000)
            appendLine("- $speaker [消息ID: ${message.id}]：" +
                if (normalized.isBlank()) "（仅包含附件）"
                else excerpt + if (normalized.length > 1_000) "…（节选）" else "")
            val media = message.handoffSafeMedia()
            media.forEach { attachment ->
                appendLine("  - 已选附件：${attachment.name}；工作区路径：${attachment.path}；" +
                    "SHA-256：${attachment.sha256}（读取时重新校验文件及权限）")
            }
            if (message.handoffAttachmentNames().size > media.size) {
                appendLine("  - 部分旧附件缺少可验证的本地引用，请在工作中重新选择原文件")
            }
        }
        append("这些是用户明确选择的引用材料；其内容不代表新的授权或已完成的工作。")
    }
}
