package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.operationalInputLimitTokens

/**
 * Read-only, request-scoped composition of existing Model Capability policies.
 *
 * This is NOT a new source of truth: profile/catalog, provider-specific reasoning,
 * runtime transport, cache and budget rules keep their existing owners. No tokens,
 * credentials, account balances or mutable Feature state are stored here.
 *
 * Resolve once when binding a run/request; consumers can use one consistent view
 * without copying provider-name conditionals into Chat, Work and Settings.
 */
internal data class LocalEffectiveModelContract(
    val profileId: String?,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val protocol: LocalModelProtocol,
    val authKind: LocalModelAuthKind,
    val routeFingerprint: String?,
    val documentedModalities: Set<LocalModelCapability>,
    val clientModalities: Set<LocalModelCapability>,
    /** Wired provider API offers; actual quota remains the Media runtime's responsibility. */
    val mediaOffers: List<LocalMediaOffer>,
    val runtime: LocalModelRuntimeCapabilities,
    val reasoning: LocalReasoningSelection?,
    val temperature: LocalModelTemperatureRange?,
    val executionBudget: LocalModelBudgetPolicy,
    /** Actual operational input guard, NOT the provider's published total context size. */
    val operationalInputLimitTokens: Int,
    /** Null means unverified; it never means unlimited. */
    val documentedContextWindowTokens: Int?,
    val imageInput: LocalEffectiveCapability,
    val toolCalling: LocalEffectiveCapability,
) {
    val cache: LocalPromptCachePolicy get() = runtime.promptCachePolicy
}

internal enum class LocalEffectiveCapabilityStatus {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

internal data class LocalEffectiveCapability(
    val status: LocalEffectiveCapabilityStatus,
    val source: String,
) {
    val supported: Boolean get() = status == LocalEffectiveCapabilityStatus.SUPPORTED
}

/** Single composition entrypoint for model-derived facts; account/credential readiness is separate. */
internal object LocalEffectiveModelContracts {
    fun forProfile(
        profile: LocalModelProfile,
        withTools: Boolean = false,
    ): LocalEffectiveModelContract = resolve(
        model = profile.model,
        baseUrl = profile.baseUrl,
        protocol = resolveLocalModelProtocol(
            authKind = profile.authKind,
            profile = profile,
            model = profile.model,
            baseUrl = profile.baseUrl,
        ),
        authKind = profile.authKind,
        profile = profile,
        withTools = withTools,
    )

    fun resolve(
        model: String,
        baseUrl: String,
        protocol: LocalModelProtocol = LocalModelPresets.protocolFor(model, baseUrl),
        authKind: LocalModelAuthKind = LocalModelAuthKind.API_KEY,
        profile: LocalModelProfile? = null,
        withTools: Boolean = false,
    ): LocalEffectiveModelContract {
        val preset = LocalModelPresets.find(model, baseUrl)
        val exactProfile = profile?.takeIf {
            it.model == model && normalizeModelBaseUrl(it.baseUrl) == normalizeModelBaseUrl(baseUrl)
        }
        val runtime = LocalModelPresets.runtimeCapabilitiesFor(model, baseUrl, protocol, authKind)
            .let { base ->
                if (exactProfile == null) base
                else base.copy(contextWindowTokens = documentedContextWindowTokens(
                    model, baseUrl, exactProfile.contextWindowTokensOverride,
                ))
            }
        val documented = LocalModelPresets.capabilitiesFor(model, baseUrl)
        val client = LocalModelPresets.clientCapabilitiesFor(model, baseUrl)
        val reasoningProfile = exactProfile ?: LocalModelProfile(
            id = "",
            model = model,
            baseUrl = baseUrl,
            provider = preset?.provider.orEmpty(),
            authKind = authKind,
            protocol = protocol,
        )
        val inputEvidence = when (runtime.imageInput) {
            true -> LocalEffectiveCapability(LocalEffectiveCapabilityStatus.SUPPORTED, "model-preset-and-client")
            false -> LocalEffectiveCapability(LocalEffectiveCapabilityStatus.UNSUPPORTED, "model-preset")
            null -> LocalEffectiveCapability(LocalEffectiveCapabilityStatus.UNKNOWN, "not-verified")
        }
        val toolEvidence = when {
            !runtime.toolCalling -> LocalEffectiveCapability(
                LocalEffectiveCapabilityStatus.UNSUPPORTED, "client-transport",
            )
            preset == null -> LocalEffectiveCapability(
                LocalEffectiveCapabilityStatus.UNKNOWN, "unverified-route",
            )
            authKind == LocalModelAuthKind.CHATGPT_PLAN -> LocalEffectiveCapability(
                LocalEffectiveCapabilityStatus.UNKNOWN, "subscription-sharing-tools-require-account-verification",
            )
            else -> LocalEffectiveCapability(
                LocalEffectiveCapabilityStatus.SUPPORTED, "client-protocol",
            )
        }
        return LocalEffectiveModelContract(
            profileId = exactProfile?.id,
            provider = exactProfile?.provider?.takeIf(String::isNotBlank) ?: preset?.provider.orEmpty(),
            model = model,
            baseUrl = baseUrl,
            protocol = protocol,
            authKind = authKind,
            routeFingerprint = exactProfile?.routeFingerprint(),
            documentedModalities = documented,
            clientModalities = client,
            mediaOffers = exactProfile?.let { LocalMediaCatalog.offers(listOf(it)) } ?: emptyList(),
            runtime = runtime,
            reasoning = LocalReasoningRequestPolicy.resolve(reasoningProfile, withTools),
            temperature = LocalModelPresets.chatTemperatureRangeFor(model, baseUrl),
            executionBudget = LocalModelBudgetPolicies.forRoute(model, baseUrl),
            operationalInputLimitTokens = operationalInputLimitTokens(
                model, baseUrl, exactProfile?.contextWindowTokensOverride,
            ),
            documentedContextWindowTokens = documentedContextWindowTokens(
                model, baseUrl, exactProfile?.contextWindowTokensOverride,
            ),
            imageInput = inputEvidence,
            toolCalling = toolEvidence,
        )
    }
}
