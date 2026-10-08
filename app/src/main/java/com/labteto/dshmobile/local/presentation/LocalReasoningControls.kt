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
        when (LocalReasoningModeStore.mode(sessionId)) {
            LocalReasoningMode.DEFAULT -> LocalReasoningUiMode.DEFAULT
            LocalReasoningMode.FAST -> LocalReasoningUiMode.FAST
            LocalReasoningMode.LOW -> LocalReasoningUiMode.LOW
            LocalReasoningMode.DEEP -> LocalReasoningUiMode.DEEP
            LocalReasoningMode.MAX -> LocalReasoningUiMode.MAX
        }

    fun availableModes(profile: LocalModelProfile?, mode: LocalUsageMode): List<LocalReasoningUiMode> {
        val supported = profile ?: return emptyList()
        val policy = LocalReasoningRequestPolicy.resolve(supported, mode == LocalUsageMode.WORK)
            ?: return emptyList()
        return buildList {
            // Default means no request override, independently of the provider's current default.
            add(LocalReasoningUiMode.DEFAULT)
            add(LocalReasoningUiMode.FAST)
            if (policy.lowEffort != null && policy.lowEffort != policy.disabledEffort) add(LocalReasoningUiMode.LOW)
            add(LocalReasoningUiMode.DEEP)
            if (policy.maxEffort != null) add(LocalReasoningUiMode.MAX)
        }
    }

    /** Project unsupported saved choices to the same default used by effortFor, without rewriting them. */
    fun effectiveMode(
        mode: LocalReasoningUiMode,
        availableModes: List<LocalReasoningUiMode>,
        needsBasicReasoning: Boolean = false,
    ): LocalReasoningUiMode = when {
        mode in availableModes -> mode
        // Mandatory-reasoning routes expose LOW through their FAST/basic position.
        mode == LocalReasoningUiMode.LOW && needsBasicReasoning && LocalReasoningUiMode.FAST in availableModes ->
            LocalReasoningUiMode.FAST
        else -> LocalReasoningUiMode.DEFAULT
    }

    fun setMode(sessionId: String, mode: LocalReasoningUiMode) =
        LocalReasoningModeStore.setMode(sessionId, when (mode) {
            LocalReasoningUiMode.DEFAULT -> LocalReasoningMode.DEFAULT
            LocalReasoningUiMode.FAST -> LocalReasoningMode.FAST
            LocalReasoningUiMode.LOW -> LocalReasoningMode.LOW
            LocalReasoningUiMode.DEEP -> LocalReasoningMode.DEEP
            LocalReasoningUiMode.MAX -> LocalReasoningMode.MAX
        })

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
