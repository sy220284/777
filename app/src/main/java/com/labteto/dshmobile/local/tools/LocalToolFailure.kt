package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect

/** Unknown admission is conservative. Only a known unstarted or read-only invocation can retry. */
internal fun localToolFailure(
    code: String,
    content: String,
    readOnly: Boolean,
    executionStarted: Boolean?,
    transportRetryable: Boolean = code.isRetryableToolTransportFailure(),
): AgentToolResult {
    val possibleSideEffect = executionStarted != false && !readOnly
    val retryable = !possibleSideEffect && transportRetryable
    return AgentToolResult(content = content, isError = true, errorCode = code, retryable = retryable,
        sideEffect = if (possibleSideEffect) AgentToolSideEffect.POSSIBLE else AgentToolSideEffect.NONE,
        recoveryHint = when {
            possibleSideEffect -> "工具可能已经产生副作用；先检查当前状态，不要直接重试。"
            retryable -> "当前调用没有不可确认的副作用，可在检查前置条件后重试一次。"
            code == "TOOL_NOT_FOUND" -> "请核实文件或目录在工作区中的真实路径，可先使用 list_files 或 glob 查找。"
            code == "TOOL_INVALID_ARGUMENT" -> "检查工具参数、路径和匹配模式，然后重新调用。"
            code == "TOOL_UNAVAILABLE" -> "检查扩展服务是否已安装、授权与启动；仍不可用时可改用 read、grep、glob 和编译检查。"
            else -> "根据失败原因检查前置条件后再决定下一步。"
        })
}

internal fun String.isRetryableToolTransportFailure(): Boolean =
    this in setOf("TIMEOUT", "NETWORK_ERROR", "DNS_FAILED", "TOOL_TIMEOUT", "TOOL_LIFECYCLE_UNAVAILABLE", "PARALLEL_TASK_ERROR", "TOOL_IO_ERROR") ||
        startsWith("MODEL_HTTP_5") || contains("TIMEOUT") || contains("NETWORK")
