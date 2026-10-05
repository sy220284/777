package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException

/** A terminal response without text or executable calls cannot satisfy a user request. */
internal fun validateUsableModelReply(reply: LocalModelReply) {
    if (reply.content.isNullOrBlank() && reply.toolCalls.isEmpty()) {
        throw LocalModelException(
            code = "MODEL_EMPTY_RESPONSE",
            message = "模型已结束响应，但没有返回正文或工具调用；本轮未自动重发，请检查模型状态后重试。",
            retryable = false,
            requestId = reply.requestId,
        )
    }
}
