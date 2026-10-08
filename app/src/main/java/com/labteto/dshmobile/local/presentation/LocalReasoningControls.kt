package com.labteto.dshmobile.local.presentation

import android.content.Context
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalReasoningModeStore

/**
 * Narrow presentation façade for the conversation's native reasoning-mode switch.
 * UI doesn't own model provider policy or preference persistence.
 */
internal object LocalReasoningControls {
    fun attach(context: Context) = LocalReasoningModeStore.attach(context)

    fun enabled(sessionId: String): Boolean = LocalReasoningModeStore.enabled(sessionId)

    fun isSupported(profile: LocalModelProfile?, mode: LocalUsageMode): Boolean =
        LocalReasoningModeStore.isSupported(profile, withTools = mode == LocalUsageMode.WORK)

    fun setEnabled(sessionId: String, enabled: Boolean) =
        LocalReasoningModeStore.setEnabled(sessionId, enabled)
}
