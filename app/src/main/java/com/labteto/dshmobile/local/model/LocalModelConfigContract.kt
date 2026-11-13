package com.labteto.dshmobile.local.model

import android.content.SharedPreferences

/** Model-owned durable configuration keys, defaults and validation policy. */
internal object LocalModelConfigContract {
    const val KEY_MODEL = "model"
    const val KEY_BASE_URL = "base_url"
    const val KEY_PROFILES_V3 = "model_profiles_v3"
    const val KEY_PROFILES_BACKUP = "model_profiles_v3_backup"
    const val KEY_PROFILES_V2 = "model_profiles_v2"
    const val KEY_ACTIVE_PROFILE_ID = "model_profile_active_v3"
    const val KEY_WORKER_PROFILE_ID = "worker_profile_id"
    const val KEY_MODEL_ATTEMPTS = "model_attempts"
    const val KEY_IMAGE_INPUT_MODE = "image_input_mode"

    const val DEFAULT_MODEL = "deepseek-flash"
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MODEL_ATTEMPTS = 3

    const val MODEL_ATTEMPTS_MIN = 1
    const val MODEL_ATTEMPTS_MAX = 5

    fun normalizeModelAttempts(value: Int): Int =
        value.coerceIn(MODEL_ATTEMPTS_MIN, MODEL_ATTEMPTS_MAX)
}

internal data class LocalModelExecutionSettingsSnapshot(
    val modelAttempts: Int,
    val workerProfileId: String?,
)

/** Raw key values for rollback; distinct from normalized runtime configuration. */
internal data class LocalModelExecutionRollbackSnapshot(
    val modelAttempts: Int?,
    val workerProfileId: String?,
)

/** Model-owned persistence for execution settings that SettingsFeature exposes to the user. */
internal object LocalModelExecutionSettings {
    fun read(
        preferences: SharedPreferences,
        availableProfiles: List<LocalModelProfile>,
    ): LocalModelExecutionSettingsSnapshot {
        val workerProfileId = preferences
            .getString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, null)
            ?.takeIf { id -> availableProfiles.any { it.id == id } }
        return LocalModelExecutionSettingsSnapshot(
            modelAttempts = LocalModelConfigContract.normalizeModelAttempts(
                preferences.getInt(
                    LocalModelConfigContract.KEY_MODEL_ATTEMPTS,
                    LocalModelConfigContract.DEFAULT_MODEL_ATTEMPTS,
                ),
            ),
            workerProfileId = workerProfileId,
        )
    }

    fun prepare(
        modelAttempts: Int,
        workerProfileId: String?,
        availableProfiles: List<LocalModelProfile>,
    ): LocalModelExecutionSettingsSnapshot {
        val normalizedWorker = workerProfileId?.trim()?.takeIf(String::isNotBlank)
        require(
            normalizedWorker == null || availableProfiles.any { it.id == normalizedWorker },
        ) { "子代理工作模型已不存在，请重新选择" }
        return LocalModelExecutionSettingsSnapshot(
            modelAttempts = LocalModelConfigContract.normalizeModelAttempts(modelAttempts),
            workerProfileId = normalizedWorker,
        )
    }

    /** Stage Model-owned keys without a separate persistence roundtrip. */
    fun stage(editor: SharedPreferences.Editor, snapshot: LocalModelExecutionSettingsSnapshot) {
        editor.putInt(LocalModelConfigContract.KEY_MODEL_ATTEMPTS, snapshot.modelAttempts)
        if (snapshot.workerProfileId == null) {
            editor.remove(LocalModelConfigContract.KEY_WORKER_PROFILE_ID)
        } else {
            editor.putString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, snapshot.workerProfileId)
        }
    }

    /** Keep the original absent/present semantics when a shared commit fails. */
    fun snapshotForRollback(preferences: SharedPreferences): LocalModelExecutionRollbackSnapshot =
        LocalModelExecutionRollbackSnapshot(
            modelAttempts = if (preferences.contains(LocalModelConfigContract.KEY_MODEL_ATTEMPTS)) {
                preferences.getInt(LocalModelConfigContract.KEY_MODEL_ATTEMPTS, 0)
            } else null,
            workerProfileId = if (preferences.contains(LocalModelConfigContract.KEY_WORKER_PROFILE_ID)) {
                preferences.getString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, null)
            } else null,
        )

    fun stageRollback(editor: SharedPreferences.Editor, before: LocalModelExecutionRollbackSnapshot) {
        if (before.modelAttempts == null) editor.remove(LocalModelConfigContract.KEY_MODEL_ATTEMPTS)
        else editor.putInt(LocalModelConfigContract.KEY_MODEL_ATTEMPTS, before.modelAttempts)
        if (before.workerProfileId == null) editor.remove(LocalModelConfigContract.KEY_WORKER_PROFILE_ID)
        else editor.putString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, before.workerProfileId)
    }

    fun write(
        preferences: SharedPreferences,
        modelAttempts: Int,
        workerProfileId: String?,
        availableProfiles: List<LocalModelProfile>,
    ): LocalModelExecutionSettingsSnapshot {
        val snapshot = prepare(modelAttempts, workerProfileId, availableProfiles)
        preferences.edit().also { stage(it, snapshot) }.apply()
        return snapshot
    }
}
