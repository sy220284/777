package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException

/** Completed answers need visible content; partial results must reach the existing usage owner. */
internal fun validateUsableModelReply(reply: LocalModelReply) {
    if (reply.completionReason != LocalModelCompletionReason.COMPLETED) return
    val hasImage = reply.canonicalMessage?.content
        ?.any { it is LocalCanonicalContent.Image }
        ?: runCatching {
            LocalCanonicalModelCodec.message(reply.message).content
                .any { it is LocalCanonicalContent.Image }
        }.getOrDefault(false)
    if (reply.content.isNullOrBlank() && reply.toolCalls.isEmpty() && !hasImage) {
        throw LocalModelException(
            code = "MODEL_EMPTY_RESPONSE",
            message = "模型已结束响应，但没有返回正文、图片或工具调用；本轮未自动重发，请检查模型状态后重试。",
            retryable = false,
            requestId = reply.requestId,
        )
    }
}

/** Call after recording provider usage, before accepting a complete/structured consumer result. */
internal fun requireCompletedModelReply(reply: LocalModelReply) {
    if (reply.completionReason == LocalModelCompletionReason.COMPLETED) return
    throw LocalModelException(
        code = "MODEL_${reply.completionReason.name}",
        message = "模型结果未完成（${reply.rawStopReason ?: reply.completionReason.name}），本次结果未作为完整内容写入。",
        retryable = false,
        requestId = reply.requestId,
    )
}
