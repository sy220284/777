package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.json.JsonArray

/**
 * Freeze one request's model, identity, reasoning and sampling choices BEFORE admission.
 * Retries use this exact immutable snapshot, never a newly selected UI route.
 *
 * Owned by the existing model coordinator: no new mutable route owner or account manager.
 */
internal data class LocalFrozenModelRequestConfiguration(
    val profile: LocalModelProfile,
    val surface: LocalRunModelSurface,
    val reasoningEffort: String?,
    val temperature: Double?,
    val credentialDiagnostic: LocalCredentialDiagnostic,
)

internal class LocalModelRequestConfigurationRuntime(
    private val modelGateway: LocalModelGateway,
) {
    suspend fun freeze(
        snapshot: LocalHarnessState,
        profile: LocalModelProfile?,
        tools: JsonArray,
        temperature: Double?,
    ): LocalFrozenModelRequestConfiguration {
        val frozenProfile = profile ?: modelGateway.profileForRoute(
            snapshot.modelState.modelSelection.activeProfileId,
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
        )
        val runSurface = frozenProfile.toRunModelSurface()
        val reasoningEffort = LocalReasoningModeStore.effortFor(
            sessionId = snapshot.sessionId,
            profile = frozenProfile,
            withTools = tools.isNotEmpty(),
            defaultChatFast = snapshot.usageMode == LocalUsageMode.CHAT,
        )
        val effectiveTemperature = LocalModelPerformanceStore.current().constrainTemperature(
            temperature ?: if (snapshot.usageMode == LocalUsageMode.WORK) {
                LocalWorkTemperatureStore.requestTemperature(
                    snapshot.sessionId, frozenProfile.model, frozenProfile.baseUrl,
                )
            } else null,
        )
        val credentialDiagnostic = modelGateway.credentialDiagnostic(frozenProfile)
        return LocalFrozenModelRequestConfiguration(
            frozenProfile, runSurface, reasoningEffort, effectiveTemperature, credentialDiagnostic,
        )
    }
}
