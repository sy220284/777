package com.labteto.dshmobile.local.presentation

import android.content.Context
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalReasoningMode
import com.labteto.dshmobile.local.model.LocalReasoningRequestPolicy
import com.labteto.dshmobile.local.model.LocalReasoningModeStore

/**
 * Narrow presentation façade for the conversation's native reasoning-mode switch.
 * UI doesn't own model provider policy or preference persistence.
 */
internal enum class LocalReasoningUiMode { DEFAULT, FAST, LOW, DEEP, MAX }

internal object LocalReasoningControls {
    fun mode(sessionId: String): LocalReasoningUiMode =
        LocalReasoningUiMode.valueOf(LocalReasoningModeStore.mode(sessionId).name)

    fun availableModes(profile: LocalModelProfile?, mode: LocalUsageMode): List<LocalReasoningUiMode> {
        val supported = profile ?: return emptyList()
        val policy = LocalReasoningRequestPolicy.resolve(supported, mode == LocalUsageMode.WORK)
            ?: return emptyList()
        return buildList {
            // Only DeepSeek's documented provider default is known to equal HIGH.
            // Other providers keep an explicit "Default" position to avoid false claims.
            if (supported.model.trim().lowercase() !in setOf("deepseek-flash", "deepseek-v4-pro")) {
                add(LocalReasoningUiMode.DEFAULT)
            }
            add(LocalReasoningUiMode.FAST)
            if (policy.lowEffort != null && policy.lowEffort != policy.disabledEffort) add(LocalReasoningUiMode.LOW)
            add(LocalReasoningUiMode.DEEP)
            if (policy.maxEffort != null) add(LocalReasoningUiMode.MAX)
        }
    }

    fun setMode(sessionId: String, mode: LocalReasoningUiMode) =
        LocalReasoningModeStore.setMode(sessionId, LocalReasoningMode.valueOf(mode.name))

    fun restoreDefault(sessionId: String) = LocalReasoningModeStore.setMode(sessionId, LocalReasoningMode.DEFAULT)

    fun requiresBasicReasoning(profile: LocalModelProfile?, mode: LocalUsageMode): Boolean =
        LocalReasoningRequestPolicy.resolve(profile, mode == LocalUsageMode.WORK)?.disabledEffort == "low"
    fun attach(context: Context) = LocalReasoningModeStore.attach(context)

    fun enabled(sessionId: String): Boolean = LocalReasoningModeStore.enabled(sessionId)

    fun isSupported(profile: LocalModelProfile?, mode: LocalUsageMode): Boolean =
        LocalReasoningModeStore.isSupported(profile, withTools = mode == LocalUsageMode.WORK)

    fun setEnabled(sessionId: String, enabled: Boolean) =
        LocalReasoningModeStore.setEnabled(sessionId, enabled)
}
