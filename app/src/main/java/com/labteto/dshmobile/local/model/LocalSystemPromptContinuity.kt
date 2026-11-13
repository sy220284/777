package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Applies an in-session system prompt update using the route's declared update semantics.
 *
 * APPEND_ONLY routes retain the already cached prefix and append an explicit override. Replacement
 * routes keep the existing behavior. Startup restoration may still replace the first system message
 * because no provider cache survives the process/session reconstruction boundary.
 */
internal fun recordRuntimeSystemPromptUpdate(
    history: LocalModelHistoryBuffer,
    prompt: String,
    modelState: LocalModelState,
    log: LocalSessionEventLog,
): LocalModelPromptUpdateMode {
    val hadSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
    return applyRuntimeSystemPromptUpdate(history, prompt, modelState).also { mode ->
        log.append("system/prompt", buildJsonObject {
            put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.TURN.name)
            put("content", prompt)
            put("model_content", runtimeSystemPromptContent(prompt, mode, hadSystem))
            put("update_mode", mode.name.lowercase())
        })
    }
}

internal fun runtimeSystemPromptContent(
    prompt: String,
    mode: LocalModelPromptUpdateMode,
    hasExistingSystem: Boolean,
): String =
    if (hasExistingSystem && mode == LocalModelPromptUpdateMode.APPEND_ONLY) {
        "【系统规则更新；后续以本条为准】\n$prompt"
    } else {
        prompt
    }

internal fun applyRuntimeSystemPromptUpdate(
    history: LocalModelHistoryBuffer,
    prompt: String,
    modelState: LocalModelState,
): LocalModelPromptUpdateMode {
    val mode = modelState.currentModelRuntimeCapabilities().systemPromptUpdateMode
    val hasSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
    val message = buildJsonObject {
        put("role", "system")
        put(LOCAL_INSTRUCTION_SCOPE, LocalInstructionScope.TURN.name)
        put("content", runtimeSystemPromptContent(prompt, mode, hasSystem))
    }
    when {
        !hasSystem -> history.prepend(message)
        mode == LocalModelPromptUpdateMode.APPEND_ONLY -> history.append(message)
        else -> history.replaceSystem(message)
    }
    return mode
}

/**
 * Historical builds could append a system override between an assistant's tool call and its
 * receipts (notably exit_plan_mode). Move only those interleaved overrides past a COMPLETE
 * matching batch; do not synthesize tool results or rewrite incomplete/unrelated history.
 */
internal fun reorderInterleavedSystemPromptUpdates(history: List<JsonObject>): List<JsonObject> {
    val reordered = mutableListOf<JsonObject>()
    var index = 0
    while (index < history.size) {
        val message = history[index]
        val calls = if (message["role"]?.jsonPrimitive?.contentOrNull == "assistant") {
            runCatching { LocalCanonicalModelCodec.canonicalToolCalls(message) }.getOrNull().orEmpty()
        } else emptyList()
        val pending = calls.mapTo(linkedSetOf()) { it.id }
        if (calls.isEmpty() || pending.size != calls.size) {
            reordered += message
            index += 1
            continue
        }

        val results = mutableListOf<JsonObject>()
        val overrides = mutableListOf<JsonObject>()
        var cursor = index + 1
        while (pending.isNotEmpty() && cursor < history.size) {
            val next = history[cursor]
            when (next["role"]?.jsonPrimitive?.contentOrNull) {
                "system" -> overrides += next
                "tool" -> {
                    val id = next["tool_call_id"]?.jsonPrimitive?.contentOrNull
                    if (id == null || !pending.remove(id)) break
                    results += next
                }
                else -> break
            }
            cursor += 1
        }

        if (pending.isEmpty() && overrides.isNotEmpty()) {
            reordered += message
            reordered += results
            reordered += overrides
            index = cursor
        } else {
            reordered += message
            index += 1
        }
    }
    return reordered
}
