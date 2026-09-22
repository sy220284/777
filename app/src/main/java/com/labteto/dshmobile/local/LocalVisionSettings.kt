package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class LocalVisionSettingsSnapshot(
    val configured: Boolean = false,
    val model: String = "",
    val baseUrl: String = "",
)

@Singleton
class LocalVisionSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val keys: LocalVisionApiKeyStore,
) {
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)

    fun route(): LocalVisionRoute? {
        val model = preferences.getString(KEY_MODEL, "").orEmpty().trim()
        val baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty().trim()
        if (model.isEmpty() || baseUrl.isEmpty()) return null
        return runCatching {
            LocalVisionRoute(
                baseUrl = normalizeModelBaseUrl(baseUrl),
                model = model,
            )
        }.getOrNull()
    }

    suspend fun snapshot(): LocalVisionSettingsSnapshot {
        val route = route()
        return LocalVisionSettingsSnapshot(
            configured = route != null && keys.get() != null,
            model = route?.model.orEmpty(),
            baseUrl = route?.baseUrl.orEmpty(),
        )
    }

    suspend fun configure(
        apiKey: String,
        model: String,
        baseUrl: String,
    ): LocalVisionSettingsSnapshot {
        val cleanModel = model.trim()
        val cleanBaseUrl = normalizeModelBaseUrl(baseUrl)
        require(cleanModel.isNotEmpty()) { "视觉模型名称不能为空" }
        if (apiKey.isNotBlank()) {
            keys.put(apiKey)
        } else {
            require(keys.get() != null) { "请填写视觉模型密钥" }
        }
        preferences.edit()
            .putString(KEY_MODEL, cleanModel)
            .putString(KEY_BASE_URL, cleanBaseUrl)
            .apply()
        return snapshot()
    }

    suspend fun clearCredential(): LocalVisionSettingsSnapshot {
        keys.clear()
        return snapshot()
    }

    suspend fun apiKey(): String? = keys.get()

    private companion object {
        const val KEY_MODEL = "vision_model"
        const val KEY_BASE_URL = "vision_base_url"
    }
}
