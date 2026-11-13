package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelState
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
    modelState: LocalModelState,
    history: List<JsonObject>,
): Int {
    val profile = modelState.modelSelection.activeProfile
    val cachePolicy = LocalModelPresets.promptCachePolicyFor(
        model = modelState.model,
        baseUrl = modelState.baseUrl,
        protocol = profile?.protocol ?: LocalModelPresets.protocolFor(modelState.model, modelState.baseUrl),
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    )
    if (cachePolicy.preserveToolSurface) {
        return LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS
    }
    val operationalLimit = operationalInputLimitTokens(
        modelState.model,
        modelState.baseUrl,
        profile?.contextWindowTokensOverride,
    )
    val pressure = LocalPromptPressureMeter.measure(
        messages = history,
        tools = JsonArray(emptyList()),
        operationalLimitTokens = operationalLimit,
        modelContextWindowTokens = documentedContextWindowTokens(
            modelState.model,
            modelState.baseUrl,
            profile?.contextWindowTokensOverride,
        ),
    )
    return minOf(
        LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
        (pressure.remainingOperationalTokens / 4).coerceAtLeast(0),
    )
}
