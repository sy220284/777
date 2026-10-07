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
import com.labteto.dshmobile.local.model.LocalModelConfigurationTransaction
import com.labteto.dshmobile.observability.AppLog
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private val json: Json,
) {
    private val preferences = LocalHarnessPreferences.from(context)
    private val profiles = LocalModelProfileStore(preferences, json)
    private val startup = LocalModelStartupMigrator(preferences, profiles, apiKeys, gateway)
    private val transactions = LocalModelConfigurationTransaction(
        json, apiKeys::readMutationJournal, apiKeys::writeMutationJournal, apiKeys::clearMutationJournal,
        profiles::transactionId, ::restoreJournal,
        { failure -> AppLog.warn("ModelConfiguration", "model/configuration-journal-cleanup-pending", failure) },
    )
    internal suspend fun save(apiKey: String, model: String, baseUrl: String, protocol: LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null): LocalModelConfigurationResult =
        LocalModelMutationGate.run {
            transactions.recover()
            require(model.isNotBlank()) { "模型名称不能为空" }
            val existingProfiles = profiles.read()
            val draft = resolveLocalModelApiKeyDraft(apiKey, normalizeModel(model), baseUrl, protocol,
                existingProfiles, apiKeys::getFor, profileId, contextWindowTokensOverride)
            val profile = draft.profile
            val all = existingProfiles.filterNot { it.id == profile.id } + profile
            persistConfiguration(all, profile, if (apiKey.isNotBlank()) mapOf(profile.id to draft.key) else emptyMap())
            LocalModelConfigurationResult(true, profile.model, profile.baseUrl, all, profile.id)
        }

    internal suspend fun saveChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
    ): List<LocalModelProfile> = LocalModelMutationGate.run {
        transactions.recover()
        val all = com.labteto.dshmobile.local.model.chatgpt.refreshChatGptPlanProfiles(profiles.read(), accountId, models)
        val active = gateway.activeProfile()?.let { previous -> all.firstOrNull { it.id == previous.id } }
        persistConfiguration(all, active)
        all
    }

    internal suspend fun select(
        id: String,
        all: List<LocalModelProfile> = profiles.read(),
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        transactions.recover()
        val current = profiles.read()
        val selected = current.firstOrNull { it.id == id } ?: return@run null
        require(gateway.hasCredential(selected)) { credentialError(selected) }
        persistConfiguration(current, selected)
        LocalModelConfigurationResult(true, selected.model, selected.baseUrl, current, selected.id)
    }

    internal suspend fun remove(
        id: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        transactions.recover()
        val all = profiles.read()
        val removed = all.firstOrNull { it.id == id } ?: return@run null
        val activeRemoved = profiles.active(currentModel, currentBaseUrl, all)?.id == id
        finishRemoval(all.filterNot { it.id == id }, activeRemoved, currentModel, currentBaseUrl,
            if (removed.authKind == LocalModelAuthKind.API_KEY) mapOf(id to null) else emptyMap())
    }

    internal suspend fun removeChatGptAccount(
        accountId: String,
        currentModel: String,
        currentBaseUrl: String,
    ): LocalModelConfigurationResult? = LocalModelMutationGate.run {
        transactions.recover()
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
        transactions.recover()
        val all = profiles.read()
        val active = profiles.active(currentModel, currentBaseUrl, all)
        finishRemoval(all.filterNot { it.id == active?.id }, true, currentModel, currentBaseUrl,
            if (active?.authKind == LocalModelAuthKind.API_KEY) mapOf(active.id to null) else emptyMap())
    }

    internal suspend fun prepareStartup(model: String, baseUrl: String) = LocalModelMutationGate.run {
        withContext(Dispatchers.IO) { transactions.recover(); startup.prepare(model, baseUrl) }
    }

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
        credentialChanges: Map<String, String?> = emptyMap(),
    ): LocalModelConfigurationResult {
        val candidate = if (activeRemoved) remaining.firstOrNull()
            else profiles.active(currentModel, currentBaseUrl, remaining)
        val next = candidate?.takeIf { gateway.hasCredential(it) }
        persistConfiguration(remaining, next, credentialChanges)
        return LocalModelConfigurationResult(
            configured = next != null,
            model = next?.model ?: LocalModelConfigContract.DEFAULT_MODEL,
            baseUrl = next?.baseUrl ?: LocalModelConfigContract.DEFAULT_BASE_URL,
            profiles = remaining,
            activeProfileId = next?.id,
        )
    }

    /** Journal before secret changes; one confirmed metadata commit is the transaction decision. */
    private suspend fun persistConfiguration(
        all: List<LocalModelProfile>,
        active: LocalModelProfile?,
        credentialChanges: Map<String, String?> = emptyMap(),
    ) = withContext(Dispatchers.IO + NonCancellable) {
        transactions.recover()
        val token = UUID.randomUUID().toString()
        val oldMetadata = profiles.configurationSnapshot()
        val previousAccount = gateway.selectedCredentialAccountId()
        val oldKeys = credentialChanges.keys.associateWith { apiKeys.getFor(it) }
        val journal = buildJsonObject {
            put("version", 1)
            put("transaction", token)
            put("selected_chatgpt", previousAccount?.let(::JsonPrimitive) ?: JsonNull)
            put("metadata", buildJsonObject { oldMetadata.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) } })
            put("keys", buildJsonObject { oldKeys.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) } })
        }
        transactions.commit(journal, persist = {
            credentialChanges.forEach { (id, key) -> if (key == null) apiKeys.clearFor(id) else apiKeys.putFor(id, key) }
            active?.let { gateway.synchronizeCredentialSelection(it) }
            profiles.commitConfiguration(all, active, token)
        }, activate = {
            if (active != null) gateway.activate(active) else {
                gateway.clearActive()
                apiKeys.activate(modelProfileId(LocalModelConfigContract.DEFAULT_MODEL, LocalModelConfigContract.DEFAULT_BASE_URL))
            }
        })
    }

    private suspend fun restoreJournal(journal: JsonObject) {
        journal["keys"]!!.jsonObject.forEach { (id, value) ->
            val key = value.jsonPrimitive.contentOrNull
            if (key == null) apiKeys.clearFor(id) else apiKeys.putFor(id, key)
        }
        profiles.restoreConfiguration(journal["metadata"]!!.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull })
        gateway.restoreCredentialAccountSelection(journal["selected_chatgpt"]?.jsonPrimitive?.contentOrNull)
    }

    private fun credentialError(profile: LocalModelProfile): String =
        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) "ChatGPT 账户授权不可用，请重新连接"
        else "该模型密钥不可用，请编辑配置重新填写"

}
