package com.labteto.dshmobile.local

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
}

internal data class LocalSendResult(
    val disposition: LocalSendDisposition,
    val rejectReason: LocalSendRejectReason? = null,
    val message: String? = null,
) {
    val accepted: Boolean
        get() = disposition != LocalSendDisposition.REJECTED

    companion object {
        val Started = LocalSendResult(LocalSendDisposition.STARTED)
        val Queued = LocalSendResult(LocalSendDisposition.QUEUED)

        fun rejected(reason: LocalSendRejectReason, message: String): LocalSendResult =
            LocalSendResult(
                disposition = LocalSendDisposition.REJECTED,
                rejectReason = reason,
                message = message,
            )

        val Empty = rejected(LocalSendRejectReason.EMPTY, "请输入消息或添加附件")
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
        return LocalSendResult.rejected(
            LocalSendRejectReason.SESSION_TRANSITION,
            "会话正在切换，请稍后重试；当前输入已保留",
        )
    }
    if (loading) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.LOADING,
            "会话仍在加载，请稍后重试；当前输入已保留",
        )
    }
    if (!configured) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.UNCONFIGURED,
            "当前模型尚未配置，请先完成模型设置",
        )
    }
    if (activeRun && pendingCount >= pendingLimit) {
        return LocalSendResult.rejected(
            LocalSendRejectReason.QUEUE_FULL,
            "当前执行中的补充消息已达到 ${pendingLimit} 条上限；当前输入已保留",
        )
    }
    return null
}


internal data class LocalPreparedSend(
    val content: String,
    val memoryInput: String,
    val modelMessage: JsonObject,
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
                append("\n图片处理：支持图片输入的主模型会直接读取像素；若当前模型不支持，将使用 vision_analyze_file 分析上述工作区图片。")
            }
        }
    }
    return LocalPreparedSend(
        content = content,
        memoryInput = prompt,
        modelMessage = buildLocalUserModelMessage(content, attachments),
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
    onAccepted()
    if (activeRun) {
        if (!enqueue()) {
            val fallback = LocalSendResult.rejected(
                LocalSendRejectReason.QUEUE_FULL,
                "当前执行中的补充消息队列暂时不可用；当前输入已保留",
            )
            onRejected(fallback)
            return fallback
        }
        onQueued()
        return LocalSendResult.Queued
    }
    onStart()
    return LocalSendResult.Started
}
