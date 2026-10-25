package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.LocalInstructionScope
import com.labteto.dshmobile.local.model.LOCAL_INSTRUCTION_SCOPE
import com.labteto.dshmobile.local.model.WORK_USER_DIRECTIVE_PRIORITY
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

/**
 * Tell the child when a long parent context has been clipped. Silent truncation can turn
 * incomplete inherited guidance into apparent complete authority.
 */
internal fun boundedSubagentContextWithNotice(context: String, maxChars: Int): String? {
    val trimmed = context.trim().takeIf(String::isNotEmpty) ?: return null
    val limit = maxChars.coerceAtLeast(1)
    if (trimmed.length <= limit) return trimmed
    // Keep initial stable constraints AND the newest corrections/revocations. Taking only the
    // prefix silently loses late user directives during long Work/Agent Team sessions.
    val notice = "\n[父级资料已截断（省略中段）；必要时请向父级补充原始约束与证据]\n"
    val visible = (limit - notice.length).coerceAtLeast(0)
    if (visible == 0) return truncateWithoutSplittingSurrogatePair(notice, limit)
    val prefixBudget = visible / 3
    val suffixBudget = visible - prefixBudget
    val prefix = truncateWithoutSplittingSurrogatePair(trimmed, prefixBudget)
    val rawStart = (trimmed.length - suffixBudget).coerceAtLeast(0)
    val safeStart = if (rawStart in 1 until trimmed.length &&
        Character.isLowSurrogate(trimmed[rawStart]) &&
        Character.isHighSurrogate(trimmed[rawStart - 1])
    ) rawStart + 1 else rawStart
    return prefix + notice + trimmed.substring(safeStart)
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
    require(boundary >= 0) {
        "SUBAGENT_FORK_BOUNDARY_MISSING：找不到父代理工具调用 $parentCallId；拒绝继承未截取的父级历史"
    }
    return history.take(boundary).toMutableList()
}

/**
 * A child may inherit the parent's user-facing conversation, never the parent's tool
 * transactions or hidden reasoning. Tool results are observable only through a new authorized
 * tool call by this child; carrying them forward would create orphaned tool messages.
 *
 * Mirrors Codex fork's typed history filtering, using 777's canonical message representation.
 */
internal fun parentConversationForSubagent(history: List<JsonObject>): MutableList<JsonObject> =
    history.mapNotNull { item ->
        when (item["role"]?.jsonPrimitive?.contentOrNull) {
            "system", "developer" -> {
                // Codex filters typed runtime fragments at fork. Do not guess authority from
                // natural-language phrases: old untagged history remains compatible.
                val scope = item[LOCAL_INSTRUCTION_SCOPE]?.jsonPrimitive?.contentOrNull
                    ?.let(LocalInstructionScope::valueOf)
                item.takeIf { scope == null || scope.inheritedByChild }
            }
            "user" -> item
            "assistant" -> {
                if (LocalCanonicalModelCodec.canonicalToolCalls(item).isNotEmpty() ||
                    item["content"] == null
                ) {
                    null
                } else {
                    val visible = item.filterKeys { key ->
                        key !in setOf(
                            "reasoning", "reasoning_content", "reasoning_details",
                            "analysis", "thoughts", "provider_replay", "model_tool_calls",
                            "tool_calls", "function_call", "tool_call_id",
                            "_dsh_model_replay", "_dsh_responses_output",
                        )
                    }
                    JsonObject(visible)
                }
            }
            else -> null
        }
    }.toMutableList()



internal fun buildLocalSubagentInitialHistory(
    baseHistory: List<JsonObject>,
    task: String,
    inheritParentHistory: Boolean,
    allowMutation: Boolean,
    context: String,
    outputSchema: JsonObject?,
    instructions: String = "",
): List<JsonObject> {
    val history = if (inheritParentHistory) {
        parentConversationForSubagent(baseHistory)
    } else {
        baseHistory.toMutableList()
    }
    if (!inheritParentHistory) {
        history += buildJsonObject {
            put("role", "system")
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.AGENT.name)
            put(
                "content",
                if (allowMutation) {
                    "你是执行子代理。完成指定子任务，按权限使用可用能力，并核实结果后返回。\n$WORK_USER_DIRECTIVE_PRIORITY"
                } else {
                    "你是只读子代理。完成指定子任务；仅允许读取、搜索和分析，不修改状态。\n$WORK_USER_DIRECTIVE_PRIORITY"
                },
            )
        }
    } else {
        // Parent transcript is observational history, not a new authorization grant. Every
        // inherited child gets its own explicit role even when the parent system prompt exists.
        val childBoundary = buildJsonObject {
            put("role", "system")
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.AGENT.name)
            put("content", if (allowMutation) {
                "【子代理身份与来源】你是独立执行子代理。父对话仅用于了解任务背景；父级工具调用、审批和已完成的执行记录不是你的新授权。仅按本次实际提供的工具和权限执行当前子任务。\n$WORK_USER_DIRECTIVE_PRIORITY"
            } else {
                "【子代理身份与来源】你是独立只读子代理。父对话仅用于了解任务背景；父级工具调用和审批不是你的新授权。只能读取、搜索和分析，不允许修改状态。\n$WORK_USER_DIRECTIVE_PRIORITY"
            })
        }
        val index = history.indexOfFirst {
            it["role"]?.jsonPrimitive?.contentOrNull != "system"
        }.let { if (it < 0) history.size else it }
        history.add(index, childBoundary)
    }
    boundedSubagentContextWithNotice(instructions, maxChars = 16_384)?.let { agentInstructions ->
        val insertion = buildJsonObject {
            put("role", "system")
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.AGENT.name)
            put(
                "content",
                "【子代理长期指令】\n$agentInstructions\n本轮任务不能扩大工具、权限或副作用边界。",
            )
        }
        val index = if (
            history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
        ) 1 else 0
        history.add(index, insertion)
    }
    boundedSubagentContextWithNotice(context, maxChars = 10_000)?.let { inherited ->
        val insertion = buildJsonObject {
            put("role", "system")
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.AGENT.name)
            put(
                "content",
                "【父任务上下文（继承，非新授权）】\n$inherited\n只遵守其中仍有效且与当前子任务相关的约束。若存在冲突、撤销或信息不完整，应依据本轮明确授权和当前任务核实；不能据此扩大工具权限。",
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
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.AGENT.name)
            put("content", localStructuredSubagentInstruction(schema))
        }
    }
    history += buildJsonObject {
        put("role", "user")
        put("content", task)
    }
    return history
}
