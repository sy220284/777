package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Projects ChatGPT account model mutations into the aggregate runtime without owning OAuth. */
internal class LocalModelAccountStateCoordinator(
    private val configuration: LocalModelConfigurationCoordinator,
    private val gateway: LocalModelGateway,
    private val state: MutableStateFlow<LocalHarnessState>,
    private val isBusy: () -> Boolean,
) {
    suspend fun syncChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
        selectFirst: Boolean,
    ) {
        require(!isBusy()) { "请先结束当前任务再切换模型账户" }
        val before = state.value
        val profiles = configuration.saveChatGptModels(accountId, models)
        val requested = if (selectFirst) {
            profiles.firstOrNull {
                it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
            }
        } else {
            null
        }
        val result = requested?.let { configuration.select(it.id, profiles) }
        val active = result?.activeProfileId
            ?.let { id -> profiles.firstOrNull { it.id == id } }
            ?: configuration.activeProfile(before.model, before.baseUrl, profiles)
        val configured = active != null && gateway.hasCredential(active)
        active?.takeIf { configured }?.let(gateway::activate)
        state.update { current ->
            current.copy(
                configured = configured,
                model = result?.model ?: active?.model ?: current.model,
                baseUrl = result?.baseUrl ?: active?.baseUrl ?: current.baseUrl,
                configuredModels = profiles.map(LocalModelProfile::model).distinct().sorted(),
                modelProfiles = profiles,
                activeModelProfileId = active?.id,
                error = if (models.isEmpty()) "当前 ChatGPT 账户没有可用于套餐共享的模型" else null,
            )
        }
    }

    suspend fun removeChatGptAccountProfiles(accountId: String) {
        require(!isBusy()) { "请先结束当前任务再断开模型账户" }
        val current = state.value
        val result = configuration.removeChatGptAccount(
            accountId = accountId,
            currentModel = current.model,
            currentBaseUrl = current.baseUrl,
        ) ?: return
        val active = configuration.activeProfile(result.model, result.baseUrl, result.profiles)
        val configured = active != null && gateway.hasCredential(active)
        active?.takeIf { configured }?.let(gateway::activate)
        state.update {
            it.copy(
                configured = configured,
                model = result.model,
                baseUrl = result.baseUrl,
                configuredModels = result.configuredModels,
                modelProfiles = result.profiles,
                activeModelProfileId = result.activeProfileId,
                error = null,
            )
        }
    }

    suspend fun requestMarkerOrNull(): String? {
        val snapshot = state.value
        val profile = configuration.activeProfile(
            snapshot.model,
            snapshot.baseUrl,
            snapshot.modelProfiles,
        ) ?: return null
        return if (gateway.hasCredential(profile)) "" else null
    }
}
