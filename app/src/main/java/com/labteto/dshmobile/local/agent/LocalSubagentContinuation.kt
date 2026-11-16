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
): JsonObject? {
    val continuation = SUBAGENT_CONTINUATION_POLICY.message(error, completedContinuations) ?: return null
    if (error.code != "ANTHROPIC_TOOL_ARGUMENTS_INVALID") return continuation
    return kotlinx.serialization.json.JsonObject(
        continuation + ("content" to kotlinx.serialization.json.JsonPrimitive(
            "工具参数解析失败（" + (error.toolInputIssue ?: "invalid_json_object") +
                "），该工具尚未执行。请先用 tool_search 查阅真实工具参数，重新构造完整 JSON 对象，" +
                "继续未完成子任务；已经成功执行的操作不能重复。"
        ))
    )
}
