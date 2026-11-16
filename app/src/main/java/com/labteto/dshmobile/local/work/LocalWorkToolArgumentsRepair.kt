package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repair invalid tool arguments and provider response protocol errors in the SAME model step.
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
            if (error.code !in MODEL_OUTPUT_REPAIRABLE_PROTOCOL_ERRORS ||
                !error.continuationEligible ||
                error.admissionState != com.labteto.dshmobile.local.model.LocalModelAdmissionState.ADMITTED ||
                repairs >= MAX_IN_STEP_TOOL_ARGUMENT_REPAIRS ||
                revisions.value != observedRevision || !canRepair()
            ) throw error
            repairs++
            onRepair(repairs)
            // Only the failed model step is corrected. Tool execution is never replayed.
            val safeTool = error.providerParam?.takeIf {
                it.matches(Regex("[A-Za-z][A-Za-z0-9_/-]{0,127}"))
            }
            val instruction = buildString {
                append("上一轮模型响应解析失败，该模型回复中没有执行任何工具。错误码=")
                append(error.code)
                append("；具体原因=")
                if (error.code in setOf(
                        "ANTHROPIC_PROTOCOL_ERROR", "MODEL_STREAM_PROTOCOL",
                        "MODEL_RESPONSE_PROTOCOL", "RESPONSES_PROTOCOL_ERROR",
                    )) {
                    append("模型返回的响应帧或工具参数结构违反当前协议；")
                    append(com.labteto.dshmobile.observability.sanitizeDiagnosticText(
                        error.message.orEmpty(),
                    ).take(280))
                } else {
                    append(error.toolInputIssue ?: "invalid_json_object")
                }
                safeTool?.let { append("；目标工具="); append(it) }
                append("。重新生成完整、符合当前工具定义的 JSON 对象参数。")
                if (safeTool == "tool_call/bash") {
                    append("bash 的正确格式为 tool_call({\"name\":\"bash\",\"arguments\":{\"command\":\"实际 Shell 命令\"}})。")
                }
                if (repairs > 1) {
                    append("已经再次发生相同类别错误；请先调用 tool_search 查清真实参数，再调用正确工具。")
                } else {
                    append("如果不确定名称或参数，先用 tool_search 查询 Schema。")
                }
                append("继续原任务；历史中已有成功回执的操作不得重复执行。")
            }
            messages = originalMessages + buildJsonObject {
                put("role", "user")
                put("content", instruction)
            }
        }
    }
}

/** Only errors caused by a received model reply, before a tool is authorized, are corrigible. */
private val MODEL_OUTPUT_REPAIRABLE_PROTOCOL_ERRORS = setOf(
    "ANTHROPIC_TOOL_ARGUMENTS_INVALID",
    "ANTHROPIC_PROTOCOL_ERROR",
    "MODEL_STREAM_PROTOCOL",
    "MODEL_RESPONSE_PROTOCOL",
    "RESPONSES_PROTOCOL_ERROR",
)

private const val MAX_IN_STEP_TOOL_ARGUMENT_REPAIRS = 2
