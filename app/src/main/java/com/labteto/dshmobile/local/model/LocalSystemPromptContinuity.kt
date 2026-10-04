package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.resolveLocalModelProtocol
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
internal fun applyRuntimeSystemPromptUpdate(
    history: LocalModelHistoryBuffer,
    prompt: String,
    state: LocalHarnessState,
): LocalModelPromptUpdateMode {
    val profile = state.modelSelection.activeProfile
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, state.model, state.baseUrl)
    } ?: LocalModelPresets.protocolFor(state.model, state.baseUrl)
    val mode = LocalModelPresets.runtimeCapabilitiesFor(
        model = state.model,
        baseUrl = state.baseUrl,
        protocol = protocol,
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    ).systemPromptUpdateMode
    val hasSystem = history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system"
    val message = buildJsonObject {
        put("role", "system")
        put(
            "content",
            if (hasSystem && mode == LocalModelPromptUpdateMode.APPEND_ONLY) {
                "【系统规则更新；后续以本条为准】\n$prompt"
            } else {
                prompt
            },
        )
    }
    when {
        !hasSystem -> history.prepend(message)
        mode == LocalModelPromptUpdateMode.APPEND_ONLY -> history.append(message)
        else -> history.replaceSystem(message)
    }
    return mode
}
