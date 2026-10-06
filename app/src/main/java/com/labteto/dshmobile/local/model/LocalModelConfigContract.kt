package com.labteto.dshmobile.local.model

import android.content.SharedPreferences

/** Model-owned durable configuration keys, defaults and validation policy. */
internal object LocalModelConfigContract {
    const val KEY_MODEL = "model"
    const val KEY_BASE_URL = "base_url"
    const val KEY_PROFILES_V3 = "model_profiles_v3"
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

    fun write(
        preferences: SharedPreferences,
        modelAttempts: Int,
        workerProfileId: String?,
        availableProfiles: List<LocalModelProfile>,
    ): LocalModelExecutionSettingsSnapshot {
        val normalizedWorker = workerProfileId?.trim()?.takeIf(String::isNotBlank)
        require(
            normalizedWorker == null || availableProfiles.any { it.id == normalizedWorker },
        ) { "子代理工作模型已不存在，请重新选择" }
        val snapshot = LocalModelExecutionSettingsSnapshot(
            modelAttempts = LocalModelConfigContract.normalizeModelAttempts(modelAttempts),
            workerProfileId = normalizedWorker,
        )
        preferences.edit().apply {
            putInt(LocalModelConfigContract.KEY_MODEL_ATTEMPTS, snapshot.modelAttempts)
            if (snapshot.workerProfileId == null) {
                remove(LocalModelConfigContract.KEY_WORKER_PROFILE_ID)
            } else {
                putString(LocalModelConfigContract.KEY_WORKER_PROFILE_ID, snapshot.workerProfileId)
            }
        }.apply()
        return snapshot
    }
}
