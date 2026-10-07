package com.labteto.dshmobile.local.model

import java.net.URI

internal const val LOCAL_NATIVE_TOOL_IMAGE_GENERATION = "image_generation"

private val OPENAI_IMAGE_GENERATION_MODELS = setOf(
    "gpt-6-astra",
    "gpt-5.5",
    "gpt-5.4-mini",
    "gpt-5.4-nano",
    "gpt-5.2",
    "gpt-5",
    "gpt-5-nano",
    "o3",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4o",
    "gpt-4o-mini",
)

internal fun supportsOpenAiImageGenerationModel(model: String): Boolean =
    model.trim().lowercase() in OPENAI_IMAGE_GENERATION_MODELS

internal fun usesOfficialOpenAiResponsesContract(
    baseUrl: String,
    planSharing: Boolean,
): Boolean {
    if (planSharing) return true
    return runCatching {
        URI(normalizeModelBaseUrl(baseUrl)).host.equals("api.openai.com", ignoreCase = true)
    }.getOrDefault(false)
}

internal fun resolveOpenAiImageGenerationToolEnabled(
    baseUrl: String,
    model: String,
    planSharing: Boolean,
    requested: Boolean,
): Boolean =
    requested &&
        usesOfficialOpenAiResponsesContract(baseUrl, planSharing) &&
        supportsOpenAiImageGenerationModel(model)

internal fun resolveLocalNativeToolNames(
    surface: LocalRunModelSurface,
    allowImageGeneration: Boolean,
): List<String> = buildList {
    if (
        surface.protocol == LocalModelProtocol.RESPONSES &&
        resolveOpenAiImageGenerationToolEnabled(
            baseUrl = surface.baseUrl,
            model = surface.model,
            planSharing = surface.profile.authKind == LocalModelAuthKind.CHATGPT_PLAN,
            requested = allowImageGeneration,
        )
    ) {
        add(LOCAL_NATIVE_TOOL_IMAGE_GENERATION)
    }
}
