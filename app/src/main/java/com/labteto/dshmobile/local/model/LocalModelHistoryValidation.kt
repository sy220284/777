package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException

/** Validate the complete request, rather than accepting individually valid but unpaired messages. */
internal fun validateCanonicalModelHistory(messages: List<LocalCanonicalMessage>) {
    val pending = linkedSetOf<String>()
    messages.forEachIndexed { index, message ->
        fun invalid(detail: String): Nothing = throw LocalModelException(
            "MODEL_HISTORY_INVALID", "模型历史第 ${index + 1} 条无效：$detail；请新建会话或恢复到工具调用前重试。", false,
        )
        if (message.role == LocalCanonicalRole.TOOL) {
            val results = message.content.filterIsInstance<LocalCanonicalContent.ToolResult>()
            if (results.size != 1 || message.content.size != 1) invalid("工具结果缺少唯一调用身份")
            if (!pending.remove(results.single().callId)) invalid("工具结果没有对应的待完成调用")
            return@forEachIndexed
        }
        if (pending.isNotEmpty()) invalid("上一条工具调用尚未收到全部结果")
        if (message.role == LocalCanonicalRole.ASSISTANT) {
            val calls = message.content.filterIsInstance<LocalCanonicalContent.ToolCall>()
            val visible = message.content.any {
                when (it) {
                    is LocalCanonicalContent.Text -> it.text.isNotBlank()
                    is LocalCanonicalContent.Image, is LocalCanonicalContent.Raw -> true
                    else -> false
                }
            }
            if (!visible && calls.isEmpty()) invalid("助手消息既无正文也无工具调用")
            calls.forEach { if (!pending.add(it.id)) invalid("工具调用包含重复身份") }
        }
    }
    if (pending.isNotEmpty()) throw LocalModelException(
        "MODEL_HISTORY_INVALID", "模型历史的工具调用缺少结果，不能继续发送；请新建会话或恢复到工具调用前重试。", false,
    )
}
