package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonObject

private val SUBAGENT_CONTINUATION_POLICY = LocalAgentContinuationPolicy(
    maxContinuations = 2,
    prompt = "上一模型请求中断，但不能安全重放原请求。基于当前已持久化上下文继续未完成子任务；不要重复已经完成并有结果的工具动作。",
)

internal fun subagentContinuationMessage(
    error: LocalModelException,
    completedContinuations: Int,
): JsonObject? = SUBAGENT_CONTINUATION_POLICY.message(error, completedContinuations)
