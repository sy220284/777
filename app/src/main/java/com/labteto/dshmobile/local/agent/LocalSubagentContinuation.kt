package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_SUBAGENT_CONTINUATIONS = 2

internal fun subagentContinuationMessage(
    error: LocalModelException,
    completedContinuations: Int,
): JsonObject? {
    if (!error.continuationEligible || completedContinuations >= MAX_SUBAGENT_CONTINUATIONS) return null
    return buildJsonObject {
        put("role", "user")
        put(
            "content",
            "上一模型请求中断，但不能安全重放原请求。基于当前已持久化上下文继续未完成子任务；不要重复已经完成并有结果的工具动作。",
        )
    }
}
