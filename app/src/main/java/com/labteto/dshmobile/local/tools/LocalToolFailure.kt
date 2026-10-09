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
            else -> "根据失败原因检查前置条件后再决定下一步。"
        })
}

internal fun String.isRetryableToolTransportFailure(): Boolean =
    this in setOf("TIMEOUT", "NETWORK_ERROR", "DNS_FAILED", "TOOL_TIMEOUT", "TOOL_LIFECYCLE_UNAVAILABLE", "PARALLEL_TASK_ERROR") ||
        startsWith("MODEL_HTTP_5") || contains("TIMEOUT") || contains("NETWORK")
