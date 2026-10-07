package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Pure subagent context inheritance policy kept outside the execution runner. */
internal fun boundedSubagentContext(context: String, maxChars: Int = 10_000): String? =
    context.trim().takeIf(String::isNotEmpty)?.let {
        truncateWithoutSplittingSurrogatePair(it, maxChars.coerceAtLeast(1))
    }

internal fun inheritedHistoryBeforeToolCall(
    history: List<JsonObject>,
    parentCallId: String?,
): MutableList<JsonObject> {
    if (parentCallId.isNullOrBlank()) return history.toMutableList()
    val boundary = history.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "assistant" &&
            LocalCanonicalModelCodec.canonicalToolCalls(message).any { call ->
                call.id == parentCallId
            }
    }
    return if (boundary >= 0) history.take(boundary).toMutableList() else history.toMutableList()
}


internal fun buildLocalSubagentInitialHistory(
    baseHistory: List<JsonObject>,
    task: String,
    inheritParentHistory: Boolean,
    allowMutation: Boolean,
    context: String,
    outputSchema: JsonObject?,
): List<JsonObject> {
    val history = baseHistory.toMutableList()
    if (!inheritParentHistory) {
        history += buildJsonObject {
            put("role", "system")
            put(
                "content",
                if (allowMutation) {
                    "你是执行子代理。完成指定子任务，按权限使用可用能力，并核实结果后返回。"
                } else {
                    "你是只读子代理。完成指定子任务；仅允许读取、搜索和分析，不修改状态。"
                },
            )
        }
    }
    boundedSubagentContext(context)?.let { inherited ->
        val insertion = buildJsonObject {
            put("role", "system")
            put(
                "content",
                "【父任务约束】\n$inherited\n遵守以上约束；本子任务的明确更新优先。",
            )
        }
        val index = if (
            history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
        ) 1 else 0
        history.add(index, insertion)
    }
    outputSchema?.let { schema ->
        history += buildJsonObject {
            put("role", "system")
            put("content", localStructuredSubagentInstruction(schema))
        }
    }
    history += buildJsonObject {
        put("role", "user")
        put("content", task)
    }
    return history
}
