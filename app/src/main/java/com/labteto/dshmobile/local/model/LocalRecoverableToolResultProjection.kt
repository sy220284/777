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
    val stored = retention == ToolResultRetention.DURABLE &&
        callId?.let { spill(it, value) } == true
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
            text = retained.text,
            stored = stored,
            truncated = false,
            omittedBytes = 0,
        )
    }
    val recovery = when {
        callId == null -> "请缩小查询范围后继续读取。"
        stored -> "当前已保留原文；可用 tool_output_read(call_id=$callId) 分页或指定 query 检索。原文会按私有存储配额淘汰。"
        else -> "完整结果超过本机私有保留上限；请缩小原查询后重试。"
    }
    return LocalRecoverableToolResultProjection(
        text = retained.text +
            "\n[已从模型上下文省略 ${retained.omittedBytes} 个 UTF-8 字节；$recovery]",
        stored = stored,
        truncated = true,
        omittedBytes = retained.omittedBytes,
    )
}
