package com.labteto.dshmobile.local.send

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import kotlinx.serialization.json.JsonObject

internal enum class LocalSendDisposition {
    STARTED,
    QUEUED,
    REJECTED,
}

internal enum class LocalSendRejectReason {
    EMPTY,
    LOADING,
    UNCONFIGURED,
    SESSION_TRANSITION,
    QUEUE_FULL,
    QUEUE_UNAVAILABLE,
}

internal data class LocalSendFeedbackState(
    val sessionId: String? = null,
    val rejectReason: LocalSendRejectReason? = null,
    val rejectLimit: Int? = null,
)

internal data class LocalSendResult(
    val disposition: LocalSendDisposition,
    val rejectReason: LocalSendRejectReason? = null,
    val rejectLimit: Int? = null,
) {
    val accepted: Boolean
        get() = disposition != LocalSendDisposition.REJECTED

    companion object {
        val Started = LocalSendResult(LocalSendDisposition.STARTED)
        val Queued = LocalSendResult(LocalSendDisposition.QUEUED)

        fun rejected(reason: LocalSendRejectReason, limit: Int? = null): LocalSendResult =
            LocalSendResult(
                disposition = LocalSendDisposition.REJECTED,
                rejectReason = reason,
                rejectLimit = limit,
            )

        val Empty = rejected(LocalSendRejectReason.EMPTY)
    }
}

internal fun evaluateLocalSendAdmission(
    configured: Boolean,
    loading: Boolean,
    sessionTransitioning: Boolean,
    activeRun: Boolean,
    pendingCount: Int,
    pendingLimit: Int,
): LocalSendResult? {
    if (sessionTransitioning) {
        return LocalSendResult.rejected(LocalSendRejectReason.SESSION_TRANSITION)
    }
    if (loading) {
        return LocalSendResult.rejected(LocalSendRejectReason.LOADING)
    }
    if (!configured) {
        return LocalSendResult.rejected(LocalSendRejectReason.UNCONFIGURED)
    }
    if (activeRun && pendingCount >= pendingLimit) {
        return LocalSendResult.rejected(LocalSendRejectReason.QUEUE_FULL, pendingLimit)
    }
    return null
}


internal data class LocalPreparedSend(
    /** Execution/model-facing text; may include local attachment paths required by tool fallback. */
    val content: String,
    /** Exact user-visible text without internal attachment instructions. */
    val visibleContent: String = content,
    val memoryInput: String,
    val modelMessage: JsonObject,
    val blocks: List<LocalMessageBlock> = emptyList(),
)

internal fun prepareLocalSend(
    text: String,
    attachments: List<LocalImportedAttachment>,
): LocalPreparedSend? {
    val prompt = text.trim()
    if (prompt.isEmpty() && attachments.isEmpty()) return null
    val attachmentBlock = attachments.joinToString("\n") { attachment ->
        val kind = if (attachment.mediaType.startsWith("image/")) "图片" else "文件"
        "- $kind：${attachment.name} → ${attachment.relativePath}（${attachment.bytes} B）"
    }
    val content = buildString {
        if (prompt.isNotEmpty()) append(prompt)
        if (attachments.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("本次附件已导入本机工作区：\n").append(attachmentBlock)
            if (attachments.any { it.mediaType.startsWith("image/") }) {
                append("\n图片处理：支持图片输入的当前路线会直接读取像素；其他路线仅在当前模式具备可用图片分析能力时处理，否则会明确返回不支持。")
            }
        }
    }
    val blocks = buildList {
        if (prompt.isNotBlank()) add(LocalMessageBlock.Text(prompt))
        attachments.forEach { attachment ->
            if (attachment.mediaType.startsWith("image/")) {
                add(
                    LocalMessageBlock.Image(
                        relativePath = attachment.relativePath,
                        mediaType = attachment.mediaType,
                        name = attachment.name,
                        bytes = attachment.bytes,
                        attachmentId = attachment.attachmentId,
                        width = attachment.width,
                        height = attachment.height,
                        source = LocalMessageMediaSource.USER,
                    ),
                )
            } else {
                add(
                    LocalMessageBlock.File(
                        relativePath = attachment.relativePath,
                        mediaType = attachment.mediaType,
                        name = attachment.name,
                        bytes = attachment.bytes,
                        attachmentId = attachment.attachmentId,
                        source = LocalMessageMediaSource.USER,
                    ),
                )
            }
        }
    }
    return LocalPreparedSend(
        content = content,
        visibleContent = prompt,
        memoryInput = prompt,
        modelMessage = buildLocalUserModelMessage(content, attachments),
        blocks = blocks,
    )
}

internal inline fun coordinateLocalSend(
    configured: Boolean,
    loading: Boolean,
    sessionTransitioning: Boolean,
    activeRun: Boolean,
    pendingCount: Int,
    pendingLimit: Int,
    onRejected: (LocalSendResult) -> Unit,
    onAccepted: () -> Unit,
    enqueue: () -> Boolean,
    onQueued: () -> Unit,
    onStart: () -> Unit,
): LocalSendResult {
    val rejection = evaluateLocalSendAdmission(
        configured = configured,
        loading = loading,
        sessionTransitioning = sessionTransitioning,
        activeRun = activeRun,
        pendingCount = pendingCount,
        pendingLimit = pendingLimit,
    )
    if (rejection != null) {
        onRejected(rejection)
        return rejection
    }
    if (activeRun) {
        if (!enqueue()) {
            val fallback = LocalSendResult.rejected(LocalSendRejectReason.QUEUE_UNAVAILABLE)
            onRejected(fallback)
            return fallback
        }
        onAccepted()
        onQueued()
        return LocalSendResult.Queued
    }
    onAccepted()
    onStart()
    return LocalSendResult.Started
}
