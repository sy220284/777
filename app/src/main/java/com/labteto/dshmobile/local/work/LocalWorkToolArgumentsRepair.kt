package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repair malformed tool-use arguments within the SAME Agent model step.
 * A failed provider response never enters history or the tool dispatcher.
 * Each correction is a NEW request, keeping already-settled tool receipts untouched.
 */
internal suspend fun <T> sampleWithToolArgumentsRepair(
    revisions: StateFlow<Long>,
    observedRevision: Long,
    originalMessages: List<JsonObject>,
    canRepair: () -> Boolean,
    onRepair: (Int) -> Unit,
    sample: suspend (List<JsonObject>) -> T,
): LocalWorkSamplingOutcome<T> {
    var repairs = 0
    var messages = originalMessages
    while (true) {
        try {
            return sampleUntilWorkSteered(revisions, observedRevision) { sample(messages) }
        } catch (error: LocalModelException) {
            if (error.code != "ANTHROPIC_TOOL_ARGUMENTS_INVALID" ||
                repairs >= MAX_IN_STEP_TOOL_ARGUMENT_REPAIRS ||
                revisions.value != observedRevision || !canRepair()
            ) throw error
            repairs++
            onRepair(repairs)
            // Request-only correction; never append fake user instructions or failed tool calls to history.
            messages = originalMessages + buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    "上一轮模型生成的工具调用参数不是合法 JSON 对象，工具尚未执行。" +
                        "请根据当前已提供的工具参数定义重新生成严格合法、完整的 JSON 对象参数，" +
                        "继续完成原任务。不要重复已在历史中有成功回执的工具操作。"
                )
            }
        }
    }
}

private const val MAX_IN_STEP_TOOL_ARGUMENT_REPAIRS = 2
