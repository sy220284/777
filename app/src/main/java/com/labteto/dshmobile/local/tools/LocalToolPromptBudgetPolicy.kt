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
    coreDefinitionTokens: Int = 0,
    requestedDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
): Int {
    val profile = modelState.modelSelection.activeProfile
    val cachePolicy = LocalModelPresets.promptCachePolicyFor(
        model = modelState.model,
        baseUrl = modelState.baseUrl,
        protocol = profile?.protocol ?: LocalModelPresets.protocolFor(modelState.model, modelState.baseUrl),
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    )
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
    // Core tools consume input tokens even when no optional tool was selected.
    // Budget those definitions before offering any optional schemas to the model.
    return remainingOptionalToolDefinitionBudget(
        remainingOperationalTokens = pressure.remainingOperationalTokens,
        coreDefinitionTokens = coreDefinitionTokens,
        preserveToolSurface = cachePolicy.preserveToolSurface,
        requestedDefinitionTokens = requestedDefinitionTokens,
    )
}

/** Shared arithmetic for any model surface, independent of catalog and provider configuration. */
internal fun remainingOptionalToolDefinitionBudget(
    remainingOperationalTokens: Int,
    coreDefinitionTokens: Int,
    preserveToolSurface: Boolean,
    requestedDefinitionTokens: Int = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
): Int {
    val capacity = (remainingOperationalTokens.toLong() -
        coreDefinitionTokens.coerceAtLeast(0)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return minOf(
        requestedDefinitionTokens.coerceIn(0, LocalToolRouter.MAX_OPTIONAL_TOOL_PROMPT_TOKENS),
        if (preserveToolSurface) capacity else capacity / 4,
    )
}
