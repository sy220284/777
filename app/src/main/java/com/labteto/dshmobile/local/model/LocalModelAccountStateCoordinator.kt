package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun canStartChatGptAccountSelection(isBusy: Boolean): Boolean = !isBusy

/** Projects ChatGPT account model mutations into the aggregate runtime without owning OAuth. */
internal class LocalModelAccountStateCoordinator(
    private val configuration: LocalModelConfigurationCoordinator,
    private val gateway: LocalModelGateway,
    private val state: MutableStateFlow<LocalHarnessState>,
    private val isBusy: () -> Boolean,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            gateway.invalidatedChatGptAccounts.collect { accountId ->
                retireChatGptAccountProfiles(accountId)
            }
        }
    }
    fun requireAccountSelectionAllowed() {
        check(canStartChatGptAccountSelection(isBusy())) { "请先结束当前任务再切换模型账户" }
    }

    suspend fun syncChatGptModels(
        accountId: String,
        models: List<ChatGptModelOption>,
        selectFirst: Boolean,
    ) {
        // 启动恢复期间目录刷新等待统一就绪边界，避免与模型迁移并发写；运行中请求使用冻结路由。
        if (!selectFirst && state.value.loading) {
            state.first { !it.loading }
        }
        // 已开始的显式账户操作允许完整提交，避免授权状态已写入后再留下半完成状态。
        val before = state.value
        val beforeActiveProfile = gateway.activeProfile()
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
        if (configured) gateway.activate(active!!) else gateway.clearActive()
        val removedActiveChatGptModel = active == null &&
            beforeActiveProfile?.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            beforeActiveProfile.credentialRef == accountId &&
            profiles.none { it.id == beforeActiveProfile.id }
        state.update { current ->
            current.copy(
                configured = configured,
                model = result?.model ?: active?.model ?: current.model,
                baseUrl = result?.baseUrl ?: active?.baseUrl ?: current.baseUrl,
                modelSelection = current.modelSelection.replaceProfiles(profiles, active?.id),
                error = when {
                    models.isEmpty() -> "当前 ChatGPT 账户没有可用于套餐共享的模型"
                    removedActiveChatGptModel -> "当前 ChatGPT 模型已不可用，请重新选择模型"
                    else -> null
                },
            )
        }
    }

    suspend fun retireChatGptAccountProfiles(accountId: String) {
        if (state.value.loading) state.first { !it.loading }
        val before = state.value
        val beforeActive = gateway.activeProfile()
        val profiles = configuration.saveChatGptModels(accountId, emptyList())
        val active = configuration.activeProfile(before.model, before.baseUrl, profiles)
        val configured = active != null && gateway.hasCredential(active)
        if (configured) gateway.activate(active!!) else gateway.clearActive()
        val retiredActive = beforeActive?.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            beforeActive.credentialRef == accountId
        state.update { current ->
            current.copy(
                configured = configured,
                model = active?.model ?: current.model,
                baseUrl = active?.baseUrl ?: current.baseUrl,
                modelSelection = current.modelSelection.replaceProfiles(profiles, active?.id),
                error = if (retiredActive) {
                    "当前 ChatGPT 套餐授权已失效，已停止使用该模型；请重新启用套餐或手动选择其他模型"
                } else {
                    current.error
                },
            )
        }
    }

    suspend fun removeChatGptAccountProfiles(accountId: String) {
        check(!isBusy()) { "请先结束当前任务再断开模型账户" }
        val current = state.value
        val result = configuration.removeChatGptAccount(
            accountId = accountId,
            currentModel = current.model,
            currentBaseUrl = current.baseUrl,
        ) ?: return
        val active = configuration.activeProfile(result.model, result.baseUrl, result.profiles)
        val configured = active != null && gateway.hasCredential(active)
        active?.takeIf { configured }?.let(gateway::activate)
        state.update { current ->
            current.copy(
                configured = configured,
                model = result.model,
                baseUrl = result.baseUrl,
                modelSelection = current.modelSelection.replaceProfiles(result.profiles, result.activeProfileId),
                error = null,
            )
        }
    }

    suspend fun requestMarkerOrNull(): String? {
        val snapshot = state.value
        val profileId = snapshot.modelSelection.activeProfileId ?: return null
        return gateway.profileForRoute(
            profileId = profileId,
            model = snapshot.model,
            baseUrl = snapshot.baseUrl,
        ).id
    }
}
