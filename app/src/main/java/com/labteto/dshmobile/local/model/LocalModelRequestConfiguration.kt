package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.json.JsonArray

/** Frozen model identity and request options, reused across retries and context recovery. */
internal data class FrozenLocalModelRequestConfiguration(
    val profile: LocalModelProfile,
    val surface: LocalRunModelSurface,
    val reasoningEffort: String?,
    val temperature: Double?,
    val credentialDiagnostic: LocalCredentialDiagnostic,
)

/**
 * Model Capability owns route resolution, sampling constraints and credential diagnostics.
 * A caller freezes these once and must never re-read mutable UI selection during a request.
 */
internal suspend fun freezeLocalModelRequestConfiguration(
    snapshot: LocalHarnessState,
    profile: LocalModelProfile?,
    tools: JsonArray,
    temperature: Double?,
    modelGateway: LocalModelGateway,
): FrozenLocalModelRequestConfiguration {
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
    return FrozenLocalModelRequestConfiguration(
        frozenProfile, runSurface, reasoningEffort, effectiveTemperature, credentialDiagnostic,
    )
}
