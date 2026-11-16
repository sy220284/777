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
    if (error.code == "MODEL_EMPTY_RESPONSE") {
        return kotlinx.serialization.json.JsonObject(
            continuation + ("content" to kotlinx.serialization.json.JsonPrimitive(
                "上一轮模型给出了空白完成结果。请核对已有工具回执和当前子任务目标，生成实际结果或使用正确工具继续；已执行的操作不可重复。"
            ))
        )
    }
    if (error.code in setOf("MODEL_STREAM_PROTOCOL", "MODEL_RESPONSE_PROTOCOL", "RESPONSES_PROTOCOL_ERROR")) {
        return kotlinx.serialization.json.JsonObject(
            continuation + ("content" to kotlinx.serialization.json.JsonPrimitive(
                "上一模型响应的工具调用参数或协议结构有误，工具尚未执行。错误：" +
                    com.labteto.dshmobile.observability.sanitizeDiagnosticText(error.message.orEmpty()).take(250) +
                    "。请通过 tool_search 确认真实参数，重新生成完整有效的调用并继续；已完成操作不可重复。"
            ))
        )
    }
    if (error.code == "ANTHROPIC_PROTOCOL_ERROR") {
        return kotlinx.serialization.json.JsonObject(
            continuation + ("content" to kotlinx.serialization.json.JsonPrimitive(
                "上一轮模型响应的 SSE 或消息块格式异常，当前调用没有执行工具。请检查已有真实工具回执，通过 tool_search 查找工具参数后重新发起合法调用，继续原子任务；不得重复已经执行的操作。"
            ))
        )
    }
    if (error.code != "ANTHROPIC_TOOL_ARGUMENTS_INVALID") return continuation
    return kotlinx.serialization.json.JsonObject(
        continuation + ("content" to kotlinx.serialization.json.JsonPrimitive(
            "工具参数解析失败（" + (error.toolInputIssue ?: "invalid_json_object") +
                "），该工具尚未执行。请先用 tool_search 查阅真实工具参数，重新构造完整 JSON 对象，" +
                "继续未完成子任务；已经成功执行的操作不能重复。"
        ))
    )
}
