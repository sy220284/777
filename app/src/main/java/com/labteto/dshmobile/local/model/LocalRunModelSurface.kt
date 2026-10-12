package com.labteto.dshmobile.local.model


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
    val contract = LocalEffectiveModelContracts.forProfile(this)
    return LocalRunModelSurface(
        profile = this,
        protocol = contract.protocol,
        capabilities = contract.runtime,
        routeFingerprint = contract.routeFingerprint ?: routeFingerprint(),
    )
}

internal fun LocalModelState.currentModelRuntimeCapabilities(): LocalModelRuntimeCapabilities {
    val profile = modelSelection.activeProfile
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, model, baseUrl)
    } ?: LocalModelPresets.protocolFor(model, baseUrl)
    return LocalEffectiveModelContracts.resolve(
        model = model,
        baseUrl = baseUrl,
        protocol = protocol,
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
        profile = profile,
    ).runtime
}
