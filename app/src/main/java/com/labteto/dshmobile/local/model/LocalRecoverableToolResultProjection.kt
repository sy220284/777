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
): LocalRecoverableToolResultProjection {
    val stored = retention == ToolResultRetention.DURABLE &&
        callId?.let { spill(it, value) } == true
    val retained = when (usageMode) {
        LocalUsageMode.WORK -> retainWorkToolResultForModel(value)
        LocalUsageMode.CHAT -> {
            val resolvedBudget = requireNotNull(budget) {
                "Chat tool-result projection requires a history budget"
            }
            retainToolResultForModel(value, usageMode, resolvedBudget)
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
        stored -> "可调用 tool_output_read，并传入 call_id=$callId 分段读取完整结果。"
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
