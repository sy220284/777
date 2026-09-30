package com.labteto.dshmobile.local

import android.content.SharedPreferences
import com.labteto.dshmobile.local.model.LocalModelCredentialResolver
import com.labteto.dshmobile.local.model.LocalModelMutationGate
import com.labteto.dshmobile.local.model.LocalModelProfileStore
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import kotlinx.serialization.json.Json

internal data class LocalModelConfigurationResult(
    val configured: Boolean,
    val model: String,
    val baseUrl: String,
    val profiles: List<LocalModelProfile>,
) {
    val configuredModels: List<String>
        get() = profiles.map(LocalModelProfile::model).distinct().sorted()
}

/** Coordinates model routes while profile persistence and credential storage stay separate. */
internal class LocalModelConfigurationCoordinator(
    preferences: SharedPreferences,
    private val apiKeys: LocalApiKeyStore,
    private val credentials: LocalModelCredentialResolver,
    private val tester: LocalModelConnectionTester,
    json: Json,
) {
    private val profiles = LocalModelProfileStore(preferences, json)

    suspend fun save(apiKey: String, model: String, baseUrl: String): LocalModelConfigurationResult =
        LocalModelMutationGate.run {
            require(model.isNotBlank()) { "模型名称不能为空" }
            val name = normalizeModel(model)
            val url = normalizeModelBaseUrl(baseUrl)
            val id = modelProfileId(name, url)
            if (apiKey.isNotBlank()) apiKeys.putFor(id, apiKey)
            else require(apiKeys.getFor(id) != null) { "请填写该模型的密钥" }
            val preset = LocalModelPresets.find(name, url)
            val profile = LocalModelProfile(
                id = id,
                model = name,
                baseUrl = url,
                provider = preset?.provider.orEmpty(),
                protocol = preset?.protocol ?: LocalModelProtocol.CHAT_COMPLETIONS,
            )
            val all = profiles.read().filterNot { it.id == id } + profile
            profiles.write(all)
            activate(profile)
            LocalModelConfigurationResult(true, name, url, all)
        }

    suspend fun saveChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
    ): List<LocalModelProfile> = LocalModelMutationGate.run {
        val all = profiles.read().filterNot {
            it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
        } + models.map { option ->
            LocalModelProfile(
                id = modelProfileId(option.slug, OPENAI_BASE_URL, LocalModelAuthKind.CHATGPT_PLAN, accountId),
                model = option.slug,
                baseUrl = OPENAI_BASE_URL,
                provider = "ChatGPT",
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
                protocol = LocalModelProtocol.RESPONSES,
                credentialRef = accountId,
                displayName = option.displayName,
            )
        }
        all.distinctBy(LocalModelProfile::id).also(profiles::write)
    }

    suspend fun select(
        id: String,
        all: List<LocalModelProfile> = profiles.read(),
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val selected = all.firstOrNull { it.id == id } ?: return@run null
        require(credentials.hasCredential(selected)) { credentialError(selected) }
        activate(selected)
        LocalModelConfigurationResult(true, selected.model, selected.baseUrl, all)
    }

    suspend fun remove(
        id: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val all = profiles.read()
        val removed = all.firstOrNull { it.id == id } ?: return@run null
        val wasActive = profiles.active(currentModel, currentBaseUrl, all)?.id == id
        if (removed.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(id)
        finishRemoval(all.filterNot { it.id == id }, wasActive, currentModel, currentBaseUrl)
    }

    suspend fun removeChatGptAccount(
        accountId: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val all = profiles.read()
        val removed = all.filter {
            it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
        }
        if (removed.isEmpty()) return@run null
        val activeId = profiles.active(currentModel, currentBaseUrl, all)?.id
        finishRemoval(
            all.filterNot { it.id in removed.map(LocalModelProfile::id).toSet() },
            removed.any { it.id == activeId },
            currentModel,
            currentBaseUrl,
        )
    }

    suspend fun clearActive(
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult = LocalModelMutationGate.run {
        val all = profiles.read()
        val active = profiles.active(currentModel, currentBaseUrl, all)
        if (active?.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(active.id)
        finishRemoval(all.filterNot { it.id == active?.id }, true, currentModel, currentBaseUrl)
    }

    suspend fun prepareStartup(model: String, baseUrl: String) {
        if (!profiles.hasV3()) {
            if (profiles.hasV2()) profiles.migrateV2IfNeeded()
            else {
                val names = if (apiKeys.hasLegacyCredential()) legacyConfiguredModelNames(model) else emptyList()
                val migrated = names.map { LocalModelProfile(modelProfileId(it, baseUrl), it, baseUrl) }
                profiles.write(migrated)
                apiKeys.migrate(migrated.map(LocalModelProfile::id))
            }
        }
        val all = profiles.read()
        apiKeys.migrate(all.filter { it.authKind == LocalModelAuthKind.API_KEY }.map(LocalModelProfile::id))
        profiles.active(model, baseUrl, all)?.takeIf { credentials.hasCredential(it) }
            ?.let(credentials::activate) ?: apiKeys.activate(modelProfileId(model, baseUrl))
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

    fun readProfiles(): List<LocalModelProfile> = profiles.read()
    fun activeProfile(model: String, baseUrl: String, all: List<LocalModelProfile> = profiles.read()) =
        profiles.active(model, baseUrl, all)
    fun legacyConfiguredModelNames(model: String): List<String> =
        (profilesLegacyModels() + model).map(String::trim).filter(String::isNotBlank).distinct().sorted()
    fun normalizeModel(model: String): String = model.trim().ifBlank { DEFAULT_MODEL }.let {
        if (it.equals("deepseek-chat", true) || it.equals("deepseek-reasoner", true)) DEFAULT_MODEL else it
    }

    private fun profilesLegacyModels(): Set<String> = legacyPreferences().getStringSet("configured_models", emptySet()).orEmpty()
    private fun legacyPreferences(): SharedPreferences = legacyPrefs
    private val legacyPrefs = preferences

    private suspend fun finishRemoval(
        remaining: List<LocalModelProfile>,
        activeRemoved: Boolean,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult {
        profiles.write(remaining)
        val next = if (activeRemoved) remaining.firstOrNull()
        else profiles.active(currentModel, currentBaseUrl, remaining)
        val usable = next?.takeIf { credentials.hasCredential(it) }
        if (usable != null) activate(usable) else reset()
        return LocalModelConfigurationResult(
            configured = usable != null,
            model = usable?.model ?: DEFAULT_MODEL,
            baseUrl = usable?.baseUrl ?: DEFAULT_BASE_URL,
            profiles = remaining,
        )
    }

    private fun activate(profile: LocalModelProfile) {
        profiles.setActive(profile)
        credentials.activate(profile)
    }

    private fun reset() {
        profiles.clearActive(DEFAULT_MODEL, DEFAULT_BASE_URL)
        apiKeys.activate(modelProfileId(DEFAULT_MODEL, DEFAULT_BASE_URL))
    }

    private fun credentialError(profile: LocalModelProfile) =
        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) "ChatGPT 账户授权不可用，请重新连接"
        else "该模型密钥不可用，请编辑配置重新填写"

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}
