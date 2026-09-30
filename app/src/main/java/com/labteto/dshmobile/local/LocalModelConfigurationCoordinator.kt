package com.labteto.dshmobile.local

import android.content.SharedPreferences
import com.labteto.dshmobile.local.model.LocalModelCredentialResolver
import com.labteto.dshmobile.local.model.LocalModelMutationGate
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
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
 * Owns persisted model routes and activation while credentials stay in provider-specific stores.
 */
internal class LocalModelConfigurationCoordinator(
    private val preferences: SharedPreferences,
    private val apiKeys: LocalApiKeyStore,
    private val credentials: LocalModelCredentialResolver,
    private val tester: LocalModelConnectionTester,
    private val json: Json,
) {
    suspend fun save(apiKey: String, model: String, baseUrl: String): LocalModelConfigurationResult =
        LocalModelMutationGate.run {
            require(model.isNotBlank()) { "模型名称不能为空" }
            val normalizedModel = normalizeModel(model)
            val normalizedBaseUrl = normalizeModelBaseUrl(baseUrl)
            val id = modelProfileId(normalizedModel, normalizedBaseUrl)
            if (apiKey.isNotBlank()) {
                apiKeys.putFor(id, apiKey)
            } else {
                require(apiKeys.getFor(id) != null) { "请填写该模型的密钥" }
            }
            val preset = LocalModelPresets.find(normalizedModel, normalizedBaseUrl)
            val profile = LocalModelProfile(
                id = id,
                model = normalizedModel,
                baseUrl = normalizedBaseUrl,
                provider = preset?.provider.orEmpty(),
                authKind = LocalModelAuthKind.API_KEY,
                protocol = preset?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS,
            )
            val profiles = (readProfiles().filterNot { it.id == id } + profile)
            persistProfiles(profiles)
            activate(profile)
            LocalModelConfigurationResult(true, normalizedModel, normalizedBaseUrl, profiles)
        }

    suspend fun saveChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
    ): List<LocalModelProfile> = LocalModelMutationGate.run {
        val retained = readProfiles().filterNot {
            it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
        }
        val added = models.map { option ->
            LocalModelProfile(
                id = modelProfileId(
                    option.slug,
                    OPENAI_BASE_URL,
                    LocalModelAuthKind.CHATGPT_PLAN,
                    accountId,
                ),
                model = option.slug,
                baseUrl = OPENAI_BASE_URL,
                provider = "ChatGPT",
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
                protocol = LocalModelProtocol.RESPONSES,
                credentialRef = accountId,
                displayName = option.displayName,
            )
        }
        val profiles = (retained + added).distinctBy(LocalModelProfile::id)
        persistProfiles(profiles)
        profiles
    }

    suspend fun select(
        id: String,
        profiles: List<LocalModelProfile> = readProfiles(),
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val selected = profiles.firstOrNull { it.id == id } ?: return@run null
        require(credentials.hasCredential(selected)) {
            if (selected.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
                "ChatGPT 账户授权不可用，请重新连接"
            } else {
                "该模型密钥不可用，请编辑配置重新填写"
            }
        }
        activate(selected)
        LocalModelConfigurationResult(true, selected.model, selected.baseUrl, profiles)
    }

    suspend fun remove(
        id: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val profiles = readProfiles()
        val removed = profiles.firstOrNull { it.id == id } ?: return@run null
        if (removed.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(id)
        val remaining = profiles.filterNot { it.id == id }
        persistProfiles(remaining)
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null)
        val next = when {
            activeId != id -> activeProfile(currentModel, currentBaseUrl, remaining)
            else -> remaining.firstOrNull()
        }
        if (next != null && credentials.hasCredential(next)) {
            activate(next)
        } else {
            preferences.edit()
                .remove(KEY_ACTIVE_PROFILE_ID)
                .putString(KEY_MODEL, DEFAULT_MODEL)
                .putString(KEY_BASE_URL, DEFAULT_BASE_URL)
                .apply()
            apiKeys.activate(modelProfileId(DEFAULT_MODEL, DEFAULT_BASE_URL))
        }
        LocalModelConfigurationResult(
            configured = next != null,
            model = next?.model ?: DEFAULT_MODEL,
            baseUrl = next?.baseUrl ?: DEFAULT_BASE_URL,
            profiles = remaining,
        )
    }

    suspend fun removeChatGptAccount(accountId: String): LocalModelConfigurationResult? =
        LocalModelMutationGate.run {
            val profiles = readProfiles()
            val removedIds = profiles
                .filter { it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId }
                .mapTo(setOf(), LocalModelProfile::id)
            if (removedIds.isEmpty()) return@run null
            val remaining = profiles.filterNot { it.id in removedIds }
            persistProfiles(remaining)
            val activeId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null)
            val next = if (activeId in removedIds) remaining.firstOrNull() else activeId?.let { id ->
                remaining.firstOrNull { it.id == id }
            }
            if (next != null && credentials.hasCredential(next)) {
                activate(next)
            } else if (activeId in removedIds) {
                preferences.edit()
                    .remove(KEY_ACTIVE_PROFILE_ID)
                    .putString(KEY_MODEL, DEFAULT_MODEL)
                    .putString(KEY_BASE_URL, DEFAULT_BASE_URL)
                    .apply()
            }
            LocalModelConfigurationResult(
                configured = remaining.isNotEmpty(),
                model = next?.model ?: preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
                baseUrl = next?.baseUrl ?: preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
                profiles = remaining,
            )
        }

    suspend fun clearActive(
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult = LocalModelMutationGate.run {
        val profiles = readProfiles()
        val active = activeProfile(currentModel, currentBaseUrl, profiles)
        if (active?.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(active.id)
        val remaining = active?.let { selected -> profiles.filterNot { it.id == selected.id } } ?: profiles
        persistProfiles(remaining)
        val next = remaining.firstOrNull()
        if (next != null && credentials.hasCredential(next)) {
            activate(next)
        } else {
            preferences.edit()
                .remove(KEY_ACTIVE_PROFILE_ID)
                .putString(KEY_MODEL, DEFAULT_MODEL)
                .putString(KEY_BASE_URL, DEFAULT_BASE_URL)
                .apply()
        }
        LocalModelConfigurationResult(
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

    fun activeProfile(
        currentModel: String,
        currentBaseUrl: String,
        profiles: List<LocalModelProfile> = readProfiles(),
    ): LocalModelProfile? {
        preferences.getString(KEY_ACTIVE_PROFILE_ID, null)?.let { id ->
            profiles.firstOrNull { it.id == id }?.let { return it }
        }
        return profiles.firstOrNull {
            it.model == currentModel && it.baseUrl.trimEnd('/') == currentBaseUrl.trimEnd('/')
        }
    }

    fun readProfiles(): List<LocalModelProfile> = runCatching {
        val raw = preferences.getString(KEY_MODEL_PROFILES_V3, null)
            ?: preferences.getString(KEY_MODEL_PROFILES_V2, "[]")
            ?: "[]"
        json.parseToJsonElement(raw).jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val name = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val url = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val auth = obj["authKind"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { LocalModelAuthKind.valueOf(it) }.getOrNull() }
                ?: LocalModelAuthKind.API_KEY
            val credentialRef = obj["credentialRef"]?.jsonPrimitive?.contentOrNull
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: modelProfileId(name, url, auth, credentialRef)
            val preset = LocalModelPresets.find(name, url)
            LocalModelProfile(
                id = id,
                model = name,
                baseUrl = url,
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull ?: preset?.provider.orEmpty(),
                authKind = auth,
                protocol = obj["protocol"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { LocalModelProtocol.valueOf(it) }.getOrNull() }
                    ?: preset?.protocol
                    ?: LocalModelProtocol.CHAT_COMPLETIONS,
                credentialRef = credentialRef,
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            )
        }.distinctBy(LocalModelProfile::id)
    }.getOrDefault(emptyList())

    fun encodeProfiles(profiles: List<LocalModelProfile>): String = buildJsonArray {
        profiles.forEach { profile ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("id", profile.id)
                put("model", profile.model)
                put("baseUrl", profile.baseUrl)
                put("provider", profile.provider)
                put("authKind", profile.authKind.name)
                put("protocol", profile.protocol.name)
                profile.credentialRef?.let { put("credentialRef", it) }
                profile.displayName?.let { put("displayName", it) }
            })
        }
    }.toString()

    fun migrateProfileStorageIfNeeded() {
        if (preferences.contains(KEY_MODEL_PROFILES_V3)) return
        val profiles = readProfiles()
        preferences.edit()
            .putString(KEY_MODEL_PROFILES_V3, encodeProfiles(profiles))
            .apply()
    }

    fun legacyConfiguredModelNames(currentModel: String): List<String> =
        (preferences.getStringSet("configured_models", emptySet())
            .orEmpty() + currentModel)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()

    fun normalizeModel(model: String): String {
        val value = model.trim().ifBlank { DEFAULT_MODEL }
        return when (value.lowercase()) {
            "deepseek-chat", "deepseek-reasoner" -> DEFAULT_MODEL
            else -> value
        }
    }

    private fun persistProfiles(profiles: List<LocalModelProfile>) {
        preferences.edit()
            .putString(KEY_MODEL_PROFILES_V3, encodeProfiles(profiles))
            .apply()
    }

    private fun activate(profile: LocalModelProfile) {
        preferences.edit()
            .putString(KEY_MODEL, profile.model)
            .putString(KEY_BASE_URL, profile.baseUrl)
            .putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            .apply()
        credentials.activate(profile)
    }

    companion object {
        const val KEY_MODEL_PROFILES_V3 = "model_profiles_v3"
        const val KEY_MODEL_PROFILES_V2 = "model_profiles_v2"
        const val KEY_ACTIVE_PROFILE_ID = "model_profile_active_v3"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}
