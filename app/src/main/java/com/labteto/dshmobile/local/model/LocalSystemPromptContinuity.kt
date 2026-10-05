package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
    state: LocalHarnessState,
    log: LocalSessionEventLog,
): LocalModelPromptUpdateMode {
    val hadSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
    return applyRuntimeSystemPromptUpdate(history, prompt, state).also { mode ->
        log.append("system/prompt", buildJsonObject {
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
    state: LocalHarnessState,
): LocalModelPromptUpdateMode {
    val mode = state.currentModelRuntimeCapabilities().systemPromptUpdateMode
    val hasSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
    val message = buildJsonObject {
        put("role", "system")
        put("content", runtimeSystemPromptContent(prompt, mode, hasSystem))
    }
    when {
        !hasSystem -> history.prepend(message)
        mode == LocalModelPromptUpdateMode.APPEND_ONLY -> history.append(message)
        else -> history.replaceSystem(message)
    }
    return mode
}
