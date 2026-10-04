package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.resolveLocalModelProtocol

/**
 * Immutable model-facing facts for one admitted Agent run.
 *
 * The selected profile is frozen before execution starts. All request/cache/tool-surface decisions
 * in that run should consume this snapshot instead of re-reading mutable UI selection.
 */
internal data class LocalRunModelSurface(
    val profile: LocalModelProfile,
    val protocol: LocalModelProtocol,
    val capabilities: LocalModelRuntimeCapabilities,
    val routeFingerprint: String,
) {
    val promptCachePolicy: LocalPromptCachePolicy get() = capabilities.promptCachePolicy
    val model: String get() = profile.model
    val baseUrl: String get() = profile.baseUrl
    val contextWindowTokensOverride: Int? get() = profile.contextWindowTokensOverride
}

internal fun LocalModelProfile.toRunModelSurface(): LocalRunModelSurface {
    val resolvedProtocol = resolveLocalModelProtocol(
        authKind = authKind,
        profile = this,
        model = model,
        baseUrl = baseUrl,
    )
    return LocalRunModelSurface(
        profile = this,
        protocol = resolvedProtocol,
        capabilities = LocalModelPresets.runtimeCapabilitiesFor(
            model = model,
            baseUrl = baseUrl,
            protocol = resolvedProtocol,
            authKind = authKind,
        ),
        routeFingerprint = routeFingerprint(),
    )
}

internal fun LocalHarnessState.currentModelRuntimeCapabilities(): LocalModelRuntimeCapabilities {
    val profile = modelSelection.activeProfile
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, model, baseUrl)
    } ?: LocalModelPresets.protocolFor(model, baseUrl)
    return LocalModelPresets.runtimeCapabilitiesFor(
        model = model,
        baseUrl = baseUrl,
        protocol = protocol,
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    )
}
