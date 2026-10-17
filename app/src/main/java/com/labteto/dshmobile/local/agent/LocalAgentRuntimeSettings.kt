package com.labteto.dshmobile.local.agent

import android.content.SharedPreferences

internal data class LocalAgentRuntimeSettingsSnapshot(
    val mainMaxSteps: Int,
    val subagentMaxSteps: Int,
)

/** Agent-owned durable runtime-limit configuration. SettingsFeature only invokes this contract. */
internal object LocalAgentRuntimeSettings {
    const val KEY_MAIN_MAX_STEPS = "main_max_steps"
    const val KEY_SUBAGENT_MAX_STEPS = "subagent_max_steps"
    const val DEFAULT_MAIN_MAX_STEPS = 128
    const val DEFAULT_SUBAGENT_MAX_STEPS = 128

    fun read(preferences: SharedPreferences): LocalAgentRuntimeSettingsSnapshot =
        LocalAgentRuntimeSettingsSnapshot(
            mainMaxSteps = LocalAgentRuntimeLimits.normalizeMainSteps(
                preferences.getInt(KEY_MAIN_MAX_STEPS, DEFAULT_MAIN_MAX_STEPS),
            ),
            subagentMaxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                preferences.getInt(KEY_SUBAGENT_MAX_STEPS, DEFAULT_SUBAGENT_MAX_STEPS),
            ),
        )

    /** Validate before any cross-domain Settings transaction touches durable storage. */
    fun prepare(mainMaxSteps: Int, subagentMaxSteps: Int) = LocalAgentRuntimeSettingsSnapshot(
        mainMaxSteps = LocalAgentRuntimeLimits.normalizeMainSteps(mainMaxSteps),
        subagentMaxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(subagentMaxSteps),
    )

    /** Stage only Agent-owned keys in an editor supplied by the Settings coordinator. */
    fun stage(editor: SharedPreferences.Editor, snapshot: LocalAgentRuntimeSettingsSnapshot) {
        editor.putInt(KEY_MAIN_MAX_STEPS, snapshot.mainMaxSteps)
        editor.putInt(KEY_SUBAGENT_MAX_STEPS, snapshot.subagentMaxSteps)
    }

    fun write(
        preferences: SharedPreferences,
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
    ): LocalAgentRuntimeSettingsSnapshot {
        val snapshot = prepare(mainMaxSteps, subagentMaxSteps)
        preferences.edit().also { stage(it, snapshot) }.apply()
        return snapshot
    }
}
