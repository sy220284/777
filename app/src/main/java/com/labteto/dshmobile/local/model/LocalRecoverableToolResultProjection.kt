package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalUsageMode

internal data class LocalRecoverableToolResultProjection(
    val text: String,
    val stored: Boolean,
    val truncated: Boolean,
    val omittedBytes: Int,
)

/**
 * Projects a tool result into bounded model-visible text while keeping a durable recovery path.
 *
 * Full output storage and prompt projection are one decision so foreground and subagent histories
 * cannot drift on when a result is recoverable.
 */
internal fun projectRecoverableToolResult(
    value: String,
    retention: ToolResultRetention,
    usageMode: LocalUsageMode,
    budget: LocalHistoryBudget? = null,
    callId: String?,
    spill: (String, String) -> Boolean,
    requireStored: Boolean = false,
    preview: String = value,
): LocalRecoverableToolResultProjection {
    // Immutable content-addressed references cannot overwrite an earlier execution whose
    // provider reused call_id. Both foreground and subagents use this single retention path.
    val reference = callId?.let { toolOutputReference(it, value) }
    val stored = retention == ToolResultRetention.DURABLE &&
        reference?.let { spill(it, value) } == true
    val referenceNote = if (stored) "\n[工具原文引用：tool_output_read(call_id=$reference)]" else ""
    check(!requireStored || stored) { "MCP 完整结果未能保存：本机存储容量不足或写入失败，请缩小查询或分页；本次结果不可回查。" }
    val retained = when (usageMode) {
        LocalUsageMode.WORK -> retainWorkToolResultForModel(preview)
        LocalUsageMode.CHAT -> {
            val resolvedBudget = requireNotNull(budget) {
                "Chat tool-result projection requires a history budget"
            }
            retainToolResultForModel(preview, usageMode, resolvedBudget)
        }
    }
    if (!retained.truncated) {
        return LocalRecoverableToolResultProjection(
            text = retained.text + referenceNote,
            stored = stored,
            truncated = false,
            omittedBytes = 0,
        )
    }
    val recovery = when {
        callId == null -> "请缩小查询范围后继续读取。"
        stored -> "当前已保留原文；可用 tool_output_read(call_id=$reference) 分页或指定 query 检索。原文会按私有存储配额淘汰。"
        else -> "完整结果超过本机私有保留上限；请缩小原查询后重试。"
    }
    return LocalRecoverableToolResultProjection(
        text = retained.text +
            "\n[已从模型上下文省略 ${retained.omittedBytes} 个 UTF-8 字节；$recovery]" + referenceNote,
        stored = stored,
        truncated = true,
        omittedBytes = retained.omittedBytes,
    )
}

/** Streaming digest avoids duplicating a potentially large UTF-8 tool payload in memory. */
internal fun toolOutputReference(callId: String, value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val sink = java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)
    java.io.OutputStreamWriter(sink, Charsets.UTF_8).use {
        it.write(callId); it.write(0); it.write(value)
    }
    return "output-" + digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun retainedToolOutputReference(content: String): String? {
    if (content.startsWith("{")) {
        val wrapped = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content) as?
            kotlinx.serialization.json.JsonObject }.getOrNull()
        val nested = wrapped?.get("content") as? kotlinx.serialization.json.JsonPrimitive
        if (nested?.isString == true) return retainedToolOutputReference(nested.content)
    }
    return Regex("""\[工具原文引用：tool_output_read\(call_id=(output-[a-f0-9]{64})\)\]$""")
        .find(content)?.groupValues?.get(1)
}
