package com.labteto.dshmobile.local.agent

import android.content.SharedPreferences

internal data class LocalAgentRuntimeSettingsSnapshot(
    val mainMaxSteps: Int,
    val subagentMaxSteps: Int,
)

/** Agent-owned durable runtime-limit configuration. SettingsFeature only invokes this contract. */
internal data class LocalAgentRuntimeRollbackSnapshot(
    val mainMaxSteps: Int?,
    val subagentMaxSteps: Int?,
)

internal object LocalAgentRuntimeSettings {
    const val KEY_MAIN_MAX_STEPS = "main_max_steps"
    const val KEY_SUBAGENT_MAX_STEPS = "subagent_max_steps"
    const val DEFAULT_MAIN_MAX_STEPS = 256
    const val DEFAULT_SUBAGENT_MAX_STEPS = 192

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

    /** Agent owns raw-key absence as well as values for failed cross-feature commits. */
    fun snapshotForRollback(preferences: SharedPreferences): LocalAgentRuntimeRollbackSnapshot =
        LocalAgentRuntimeRollbackSnapshot(
            mainMaxSteps = if (preferences.contains(KEY_MAIN_MAX_STEPS)) preferences.getInt(KEY_MAIN_MAX_STEPS, 0) else null,
            subagentMaxSteps = if (preferences.contains(KEY_SUBAGENT_MAX_STEPS)) preferences.getInt(KEY_SUBAGENT_MAX_STEPS, 0) else null,
        )

    fun stageRollback(editor: SharedPreferences.Editor, before: LocalAgentRuntimeRollbackSnapshot) {
        if (before.mainMaxSteps == null) editor.remove(KEY_MAIN_MAX_STEPS)
        else editor.putInt(KEY_MAIN_MAX_STEPS, before.mainMaxSteps)
        if (before.subagentMaxSteps == null) editor.remove(KEY_SUBAGENT_MAX_STEPS)
        else editor.putInt(KEY_SUBAGENT_MAX_STEPS, before.subagentMaxSteps)
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
