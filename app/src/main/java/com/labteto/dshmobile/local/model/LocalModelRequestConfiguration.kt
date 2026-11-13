package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.json.JsonArray

/** Neutral facts projected once by the caller; no aggregate Feature state enters Model. */
internal data class LocalModelRequestSelection(
    val sessionId: String,
    val activeProfileId: String?,
    val model: String,
    val baseUrl: String,
    val usageMode: LocalUsageMode,
)

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
    selection: LocalModelRequestSelection,
    profile: LocalModelProfile?,
    tools: JsonArray,
    temperature: Double?,
    modelGateway: LocalModelGateway,
): FrozenLocalModelRequestConfiguration {
    val frozenProfile = profile ?: modelGateway.profileForRoute(
        selection.activeProfileId,
        selection.model,
        selection.baseUrl,
    )
    val runSurface = frozenProfile.toRunModelSurface()
    val reasoningEffort = LocalReasoningModeStore.effortFor(
        sessionId = selection.sessionId,
        profile = frozenProfile,
        withTools = tools.isNotEmpty(),
        defaultChatFast = selection.usageMode == LocalUsageMode.CHAT,
    )
    val effectiveTemperature = LocalModelPerformanceStore.current().constrainTemperature(
        temperature ?: if (selection.usageMode == LocalUsageMode.WORK) {
            LocalWorkTemperatureStore.requestTemperature(
                selection.sessionId, frozenProfile.model, frozenProfile.baseUrl,
            )
        } else null,
    )
    val credentialDiagnostic = modelGateway.credentialDiagnostic(frozenProfile)
    return FrozenLocalModelRequestConfiguration(
        frozenProfile, runSurface, reasoningEffort, effectiveTemperature, credentialDiagnostic,
    )
}
