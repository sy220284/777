package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.operationalInputLimitTokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Chooses the optional-tool schema budget without making the projection layer own provider rules.
 *
 * Cache-sensitive official routes keep an already enabled tool surface stable. Other routes retain
 * the existing context-pressure budget so unknown/custom providers do not inherit assumptions.
 */
internal fun optionalToolPromptBudgetForRoute(
    state: LocalHarnessState,
    history: List<JsonObject>,
): Int {
    val profile = state.modelState.modelSelection.activeProfile
    val cachePolicy = LocalModelPresets.promptCachePolicyFor(
        model = state.modelState.model,
        baseUrl = state.modelState.baseUrl,
        protocol = profile?.protocol ?: LocalModelPresets.protocolFor(state.modelState.model, state.modelState.baseUrl),
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    )
    if (cachePolicy.preserveToolSurface) {
        return LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS
    }
    val operationalLimit = operationalInputLimitTokens(
        state.modelState.model,
        state.modelState.baseUrl,
        profile?.contextWindowTokensOverride,
    )
    val pressure = LocalPromptPressureMeter.measure(
        messages = history,
        tools = JsonArray(emptyList()),
        operationalLimitTokens = operationalLimit,
        modelContextWindowTokens = documentedContextWindowTokens(
            state.modelState.model,
            state.modelState.baseUrl,
            profile?.contextWindowTokensOverride,
        ),
    )
    return minOf(
        LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
        (pressure.remainingOperationalTokens / 4).coerceAtLeast(0),
    )
}
