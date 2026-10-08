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
internal enum class LocalReasoningUiMode { DEFAULT, FAST, DEEP }

internal object LocalReasoningControls {
    fun mode(sessionId: String): LocalReasoningUiMode =
        LocalReasoningUiMode.valueOf(LocalReasoningModeStore.mode(sessionId).name)

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
