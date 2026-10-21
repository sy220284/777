package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelConfigurationCoordinator
import com.labteto.dshmobile.local.LocalModelConfigurationResult
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun canStartChatGptAccountSelection(isBusy: Boolean): Boolean = !isBusy

internal fun shouldDeferBackgroundChatGptModelSync(selectFirst: Boolean, isBusy: Boolean): Boolean =
    !selectFirst && isBusy

/** Account changes share the same durable mutation and UI projection boundary as API models. */
internal class LocalModelAccountStateCoordinator(
    private val configuration: LocalModelConfigurationCoordinator,
    private val gateway: LocalModelGateway,
    private val state: LocalModelStatePort,
    private val isBusy: () -> Boolean,
    private val onCommitted: (LocalModelConfigurationResult, String?, Boolean, String?) -> Unit,
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
        originatingSessionId: String? = null,
    ) {
        if (!selectFirst && state.value.loading) state.awaitReady()
        if (shouldDeferBackgroundChatGptModelSync(selectFirst, isBusy())) return
        // The account catalog refresh and optional first selection are one transaction.
        // No separate select() or post-lock gateway activation may race with remove/save.
        configuration.saveChatGptModels(accountId, models, selectFirst) { result ->
            onCommitted(
                result,
                if (models.isEmpty()) "当前 ChatGPT 账户没有可用于套餐共享的模型" else null,
                selectFirst,
                originatingSessionId,
            )
        }
    }

    suspend fun retireChatGptAccountProfiles(accountId: String) {
        if (state.value.loading) state.awaitReady()
        if (gateway.hasChatGptPlanAuthorization(accountId)) return
        val selected = gateway.activeProfile()
        val wasActive = selected?.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            selected.credentialRef == accountId
        configuration.saveChatGptModels(accountId, emptyList()) { result ->
            onCommitted(
                result,
                if (wasActive)
                    "当前 ChatGPT 套餐授权已失效，已停止使用该模型；请重新启用套餐或手动选择其他模型"
                else null,
                wasActive,
                null,
            )
        }
    }

    suspend fun removeChatGptAccountProfiles(accountId: String) {
        check(!isBusy()) { "请先结束当前任务再断开模型账户" }
        val before = state.value.modelState
        configuration.removeChatGptAccount(
            accountId = accountId,
            currentModel = before.model,
            currentBaseUrl = before.baseUrl,
            onCommitted = { onCommitted(it, null, true, null) },
        )
    }
}
