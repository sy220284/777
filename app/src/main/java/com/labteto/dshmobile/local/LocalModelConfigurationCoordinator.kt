package com.labteto.dshmobile.local

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalModelConfigurationResult(
    val configured: Boolean,
    val model: String,
    val baseUrl: String,
    val profiles: List<LocalModelProfile>,
) {
    val configuredModels: List<String>
        get() = profiles.map(LocalModelProfile::model).distinct().sorted()
}

/**
 * Owns persisted model routes and their encrypted credentials.
 *
 * Keeping this boundary outside LocalHarnessEngine prevents model-settings persistence from
 * accumulating inside the execution state machine.
 */
internal class LocalModelConfigurationCoordinator(
    private val preferences: SharedPreferences,
    private val apiKeys: LocalApiKeyStore,
    private val tester: LocalModelConnectionTester,
    private val json: Json,
) {
    suspend fun save(apiKey: String, model: String, baseUrl: String): LocalModelConfigurationResult {
        require(model.isNotBlank()) { "模型名称不能为空" }
        val normalizedModel = normalizeModel(model)
        val normalizedBaseUrl = normalizeModelBaseUrl(baseUrl)
        val id = modelProfileId(normalizedModel, normalizedBaseUrl)
        if (apiKey.isNotBlank()) {
            apiKeys.putFor(id, apiKey)
        } else {
            require(apiKeys.getFor(id) != null) { "请填写该模型的密钥" }
        }
        val profiles = (readProfiles().filterNot { it.id == id } +
            LocalModelProfile(id, normalizedModel, normalizedBaseUrl))
        preferences.edit()
            .putString(KEY_MODEL, normalizedModel)
            .putString(KEY_BASE_URL, normalizedBaseUrl)
            .putString(KEY_MODEL_PROFILES, encodeProfiles(profiles))
            .apply()
        apiKeys.activate(id)
        return LocalModelConfigurationResult(true, normalizedModel, normalizedBaseUrl, profiles)
    }

    suspend fun select(
        id: String,
        profiles: List<LocalModelProfile>,
    ): LocalModelConfigurationResult? {
        val selected = profiles.firstOrNull { it.id == id } ?: return null
        require(apiKeys.getFor(id) != null) { "该模型密钥不可用，请编辑配置重新填写" }
        preferences.edit()
            .putString(KEY_MODEL, selected.model)
            .putString(KEY_BASE_URL, selected.baseUrl)
            .apply()
        apiKeys.activate(selected.id)
        return LocalModelConfigurationResult(true, selected.model, selected.baseUrl, profiles)
    }

    suspend fun remove(
        id: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? {
        val profiles = readProfiles()
        if (profiles.none { it.id == id }) return null
        apiKeys.clearFor(id)
        val remaining = profiles.filterNot { it.id == id }
        val next = remaining.firstOrNull {
            it.model == currentModel && it.baseUrl == currentBaseUrl
        } ?: remaining.firstOrNull()
        preferences.edit()
            .putString(KEY_MODEL_PROFILES, encodeProfiles(remaining))
            .putString(KEY_MODEL, next?.model ?: DEFAULT_MODEL)
            .putString(KEY_BASE_URL, next?.baseUrl ?: DEFAULT_BASE_URL)
            .apply()
        apiKeys.activate(next?.id ?: modelProfileId(DEFAULT_MODEL, DEFAULT_BASE_URL))
        return LocalModelConfigurationResult(
            configured = next != null,
            model = next?.model ?: DEFAULT_MODEL,
            baseUrl = next?.baseUrl ?: DEFAULT_BASE_URL,
            profiles = remaining,
        )
    }

    suspend fun test(apiKey: String, model: String, baseUrl: String): String {
        if (model.isBlank()) return "请选择模型"
        val name = normalizeModel(model)
        val url = runCatching { normalizeModelBaseUrl(baseUrl) }
            .getOrElse { return it.message ?: "地址无效" }
        val key = apiKey.trim().takeIf(String::isNotEmpty)
            ?: apiKeys.getFor(modelProfileId(name, url))
            ?: return "请先填写该模型的密钥"
        return tester.test(key, url, name)
    }

    fun readProfiles(): List<LocalModelProfile> = runCatching {
        json.parseToJsonElement(preferences.getString(KEY_MODEL_PROFILES, "[]") ?: "[]")
            .jsonArray.mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val url = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LocalModelProfile(modelProfileId(name, url), name, url)
            }
            .distinctBy(LocalModelProfile::id)
    }.getOrDefault(emptyList())

    fun encodeProfiles(profiles: List<LocalModelProfile>): String = buildJsonArray {
        profiles.forEach { profile ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("model", profile.model)
                put("baseUrl", profile.baseUrl)
            })
        }
    }.toString()

    fun normalizeModel(model: String): String {
        val value = model.trim().ifBlank { DEFAULT_MODEL }
        return when (value.lowercase()) {
            "deepseek-chat", "deepseek-reasoner" -> DEFAULT_MODEL
            else -> value
        }
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_MODEL_PROFILES = "model_profiles_v2"
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
