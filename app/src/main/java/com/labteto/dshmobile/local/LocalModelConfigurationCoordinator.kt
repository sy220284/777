package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.persistence.LocalHarnessPreferences
import android.content.Context
import com.labteto.dshmobile.local.model.LocalApiKeyStore
import com.labteto.dshmobile.local.model.LocalDeepSeekSearchCredentialResolver
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelConnectionTester
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelMutationGate
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProfileStore
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelStartupMigrator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.modelProfileId
import com.labteto.dshmobile.local.model.resolveLocalModelApiKeyDraft
import com.labteto.dshmobile.local.model.LocalModelConfigContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

internal data class LocalModelConfigurationResult(
    val configured: Boolean, val model: String, val baseUrl: String,
    val profiles: List<LocalModelProfile>,
    val activeProfileId: String?,
)

/** Coordinates model-route mutations; storage, migration and credential resolution stay extracted. */
@Singleton
class LocalModelConfigurationCoordinator @Inject internal constructor(
    @ApplicationContext context: Context,
    private val apiKeys: LocalApiKeyStore,
    private val gateway: LocalModelGateway,
    private val tester: LocalModelConnectionTester,
    json: Json,
) {
    private val preferences = LocalHarnessPreferences.from(context)
    private val profiles = LocalModelProfileStore(preferences, json)
    private val startup = LocalModelStartupMigrator(preferences, profiles, apiKeys, gateway)
    internal suspend fun save(apiKey: String, model: String, baseUrl: String, protocol: LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null): LocalModelConfigurationResult =
        LocalModelMutationGate.run {
            require(model.isNotBlank()) { "模型名称不能为空" }
            val existingProfiles = profiles.read()
            val draft = resolveLocalModelApiKeyDraft(apiKey, normalizeModel(model), baseUrl, protocol,
                existingProfiles, apiKeys::getFor, profileId, contextWindowTokensOverride)
            val profile = draft.profile
            if (apiKey.isNotBlank()) apiKeys.putFor(profile.id, draft.key)
            val all = existingProfiles.filterNot { it.id == profile.id } + profile
            profiles.write(all)
            activate(profile)
            LocalModelConfigurationResult(true, profile.model, profile.baseUrl, all, profile.id)
        }

    internal suspend fun saveChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
    ): List<LocalModelProfile> = LocalModelMutationGate.run {
        profiles.replaceChatGpt(accountId, models)
    }

    internal suspend fun select(
        id: String,
        all: List<LocalModelProfile> = profiles.read(),
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val selected = all.firstOrNull { it.id == id } ?: return@run null
        require(gateway.hasCredential(selected)) { credentialError(selected) }
        activate(selected)
        LocalModelConfigurationResult(true, selected.model, selected.baseUrl, all, selected.id)
    }

    internal suspend fun remove(
        id: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        val all = profiles.read()
        val removed = all.firstOrNull { it.id == id } ?: return@run null
        val activeRemoved = profiles.active(currentModel, currentBaseUrl, all)?.id == id
        if (removed.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(id)
        finishRemoval(all.filterNot { it.id == id }, activeRemoved, currentModel, currentBaseUrl)
    }

    internal suspend fun removeChatGptAccount(
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
        val removedIds = removed.mapTo(hashSetOf()) { it.id }
        finishRemoval(
            all.filterNot { it.id in removedIds },
            activeId != null && activeId in removedIds,
            currentModel,
            currentBaseUrl,
        )
    }

    internal suspend fun clearActive(
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult = LocalModelMutationGate.run {
        val all = profiles.read()
        val active = profiles.active(currentModel, currentBaseUrl, all)
        if (active?.authKind == LocalModelAuthKind.API_KEY) apiKeys.clearFor(active.id)
        finishRemoval(all.filterNot { it.id == active?.id }, true, currentModel, currentBaseUrl)
    }

    internal suspend fun prepareStartup(model: String, baseUrl: String) = startup.prepare(model, baseUrl)

    internal suspend fun test(apiKey: String, model: String, baseUrl: String, protocol: LocalModelProtocol? = null, profileId: String? = null): String {
        if (model.isBlank()) return "请选择模型"
        return tester.testStoredRoute(apiKey, normalizeModel(model), baseUrl, protocol, profiles.read(), apiKeys::getFor, profileId)
    }

    internal fun readProfiles(): List<LocalModelProfile> = profiles.read()

    internal suspend fun resolveDeepSeekSearchCredential(): String? =
        LocalDeepSeekSearchCredentialResolver(::readProfiles, apiKeys).resolve()

    internal fun activeProfile(model: String, baseUrl: String, all: List<LocalModelProfile> = profiles.read()) =
        profiles.active(model, baseUrl, all)

    internal fun normalizeModel(model: String): String =
        model.trim().ifBlank { LocalModelConfigContract.DEFAULT_MODEL }.let {
            if (it.equals("deepseek-chat", true) || it.equals("deepseek-reasoner", true)) LocalModelConfigContract.DEFAULT_MODEL else it
        }

    private suspend fun finishRemoval(
        remaining: List<LocalModelProfile>,
        activeRemoved: Boolean,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult {
        profiles.write(remaining)
        val candidate = if (activeRemoved) remaining.firstOrNull()
            else profiles.active(currentModel, currentBaseUrl, remaining)
        val next = candidate?.takeIf { gateway.hasCredential(it) }
        if (next != null) activate(next) else reset()
        return LocalModelConfigurationResult(
            configured = next != null,
            model = next?.model ?: LocalModelConfigContract.DEFAULT_MODEL,
            baseUrl = next?.baseUrl ?: LocalModelConfigContract.DEFAULT_BASE_URL,
            profiles = remaining,
            activeProfileId = next?.id,
        )
    }

    private suspend fun activate(profile: LocalModelProfile) {
        gateway.synchronizeCredentialSelection(profile)
        profiles.setActive(profile)
        gateway.activate(profile)
    }

    private fun reset() {
        profiles.clearActive(LocalModelConfigContract.DEFAULT_MODEL, LocalModelConfigContract.DEFAULT_BASE_URL); gateway.clearActive()
        apiKeys.activate(modelProfileId(LocalModelConfigContract.DEFAULT_MODEL, LocalModelConfigContract.DEFAULT_BASE_URL))
    }

    private fun credentialError(profile: LocalModelProfile): String =
        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) "ChatGPT 账户授权不可用，请重新连接"
        else "该模型密钥不可用，请编辑配置重新填写"

    companion object {
        const val LocalModelConfigContract.DEFAULT_MODEL = "deepseek-flash"
        const val LocalModelConfigContract.DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
