package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun canStartChatGptAccountSelection(isBusy: Boolean): Boolean = !isBusy

internal fun shouldDeferBackgroundChatGptModelSync(selectFirst: Boolean, isBusy: Boolean): Boolean =
    !selectFirst && isBusy

/** Projects ChatGPT account mutations through the Model-owned state port without aggregate writes. */
internal class LocalModelAccountStateCoordinator(
    private val configuration: LocalModelConfigurationCoordinator,
    private val gateway: LocalModelGateway,
    private val state: LocalModelStatePort,
    private val isBusy: () -> Boolean,
) {
    fun observeInvalidations(scope: CoroutineScope) {
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
        if (!selectFirst && state.value.loading) state.awaitReady()
        if (shouldDeferBackgroundChatGptModelSync(selectFirst, isBusy())) return
        val before = state.value
        val beforeActiveProfile = gateway.activeProfile()
        val profiles = configuration.saveChatGptModels(accountId, models)
        val requested = if (selectFirst) {
            profiles.firstOrNull {
                it.authKind == LocalModelAuthKind.CHATGPT_PLAN && it.credentialRef == accountId
            }
        } else null
        val result = requested?.let { configuration.select(it.id, profiles) }
        val active = result?.activeProfileId
            ?.let { id -> profiles.firstOrNull { it.id == id } }
            ?: configuration.activeProfile(before.modelState.model, before.modelState.baseUrl, profiles)
        val configured = active != null && gateway.hasCredential(active)
        if (configured) gateway.activate(active!!) else gateway.clearActive()
        val removedActiveChatGptModel = active == null &&
            beforeActiveProfile?.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            beforeActiveProfile.credentialRef == accountId &&
            profiles.none { it.id == beforeActiveProfile.id }
        state.commit(
            before.modelState.copy(
                configured = configured,
                model = result?.model ?: active?.model ?: before.modelState.model,
                baseUrl = result?.baseUrl ?: active?.baseUrl ?: before.modelState.baseUrl,
                modelSelection = before.modelState.modelSelection.replaceProfiles(profiles, active?.id),
            ),
            when {
                models.isEmpty() -> "当前 ChatGPT 账户没有可用于套餐共享的模型"
                removedActiveChatGptModel -> "当前 ChatGPT 模型已不可用，请重新选择模型"
                else -> null
            },
        )
    }

    suspend fun retireChatGptAccountProfiles(accountId: String) {
        if (state.value.loading) state.awaitReady()
        if (gateway.hasChatGptPlanAuthorization(accountId)) return
        val before = state.value
        val beforeActive = gateway.activeProfile()
        val profiles = configuration.saveChatGptModels(accountId, emptyList())
        val active = configuration.activeProfile(before.modelState.model, before.modelState.baseUrl, profiles)
        val configured = active != null && gateway.hasCredential(active)
        if (configured) gateway.activate(active!!) else gateway.clearActive()
        val retiredActive = beforeActive?.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            beforeActive.credentialRef == accountId
        state.commit(
            before.modelState.copy(
                configured = configured,
                model = active?.model ?: before.modelState.model,
                baseUrl = active?.baseUrl ?: before.modelState.baseUrl,
                modelSelection = before.modelState.modelSelection.replaceProfiles(profiles, active?.id),
            ),
            if (retiredActive) {
                "当前 ChatGPT 套餐授权已失效，已停止使用该模型；请重新启用套餐或手动选择其他模型"
            } else before.error,
        )
    }

    suspend fun removeChatGptAccountProfiles(accountId: String) {
        check(!isBusy()) { "请先结束当前任务再断开模型账户" }
        val before = state.value
        val result = configuration.removeChatGptAccount(
            accountId = accountId,
            currentModel = before.modelState.model,
            currentBaseUrl = before.modelState.baseUrl,
        ) ?: return
        val active = configuration.activeProfile(result.model, result.baseUrl, result.profiles)
        val configured = active != null && gateway.hasCredential(active)
        active?.takeIf { configured }?.let(gateway::activate)
        state.commit(
            before.modelState.copy(
                configured = configured,
                model = result.model,
                baseUrl = result.baseUrl,
                modelSelection = before.modelState.modelSelection.replaceProfiles(result.profiles, result.activeProfileId),
            ),
            null,
        )
    }
}
