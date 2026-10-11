package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalModelRuntime
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptPlanConnectionTester
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.observability.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <T> finishChatGptAccountMutation(block: suspend () -> T): T =
    withContext(NonCancellable) { block() }

/** Presentation-facing ChatGPT account workflow; OAuth ownership remains in ChatGptAuthCoordinator. */
@Singleton
class ChatGptSettingsController @Inject constructor(
    private val auth: ChatGptAuthCoordinator,
    private val modelRuntime: LocalModelRuntime,
    private val planTester: ChatGptPlanConnectionTester,
) {
    val state: StateFlow<ChatGptUiState> = auth.state
    private val accountMutationMutex = Mutex()
    @Volatile private var latestSelectedAccountId: String? = null

    suspend fun refresh() {
        // Background resume/refresh must not commit over an in-progress account switch.
        if (!accountMutationMutex.tryLock()) return
        try {
            auth.refresh()
            reconcilePlanProfiles()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warn("ChatGptSettingsController", "ChatGPT background refresh failed", error)
        } finally {
            accountMutationMutex.unlock()
        }
    }

    suspend fun connect(existingAccountId: String? = null, requestPlanConsent: Boolean = false) =
        accountMutationMutex.withLock {
            modelRuntime.requireChatGptAccountSelectionAllowed()
            val account = auth.connect(existingAccountId, requestPlanConsent)
            reconcilePlanProfiles(selectFirstAccountId = account.id)
        }

    suspend fun restart(existingAccountId: String? = null) =
        accountMutationMutex.withLock {
            modelRuntime.requireChatGptAccountSelectionAllowed()
            val account = auth.restartAuthorization(existingAccountId)
            reconcilePlanProfiles(selectFirstAccountId = account.id)
        }

    suspend fun cancelAuthorization() = auth.cancelPendingAuthorization()

    /** Refresh auth state after the real probe so terminal credential failures surface immediately. */
    suspend fun test(id: String): String {
        val result = planTester.test(id)
        if (state.value.selectedAccountId == id) {
            auth.refresh()
            reconcilePlanProfiles()
        }
        return result
    }

    suspend fun select(id: String) {
        latestSelectedAccountId = id
        accountMutationMutex.withLock {
            if (latestSelectedAccountId != id) return@withLock
            modelRuntime.requireChatGptAccountSelectionAllowed()
            auth.selectAccount(id)
            // A newer tap may arrive during the network catalog probe.
            if (latestSelectedAccountId != id) return@withLock
            val snapshot = auth.state.value
            if (shouldRetireChatGptPlanProfiles(snapshot.selectedAccount)) {
                modelRuntime.retireChatGptAccountProfiles(id)
            }
            require(snapshot.connected) {
                snapshot.error ?: "ChatGPT 账户已登录，但套餐用量尚未启用"
            }
            modelRuntime.syncChatGptModels(id, snapshot.models, true)
        }
    }

    suspend fun disconnect(id: String): String? = accountMutationMutex.withLock {
        modelRuntime.removeChatGptAccountProfiles(id)
        finishChatGptAccountMutation {
            val warning = auth.disconnect(id)
            reconcilePlanProfiles()
            warning
        }
    }

    suspend fun remove(id: String): String? = accountMutationMutex.withLock {
        modelRuntime.removeChatGptAccountProfiles(id)
        finishChatGptAccountMutation {
            val warning = auth.remove(id)
            reconcilePlanProfiles()
            warning
        }
    }

    private suspend fun reconcilePlanProfiles(selectFirstAccountId: String? = null) {
        val snapshot = auth.state.value
        snapshot.accounts.filter(::shouldRetireChatGptPlanProfiles).forEach { account ->
            modelRuntime.retireChatGptAccountProfiles(account.id)
        }
        if (snapshot.connected) {
            snapshot.selectedAccountId?.let { accountId ->
                modelRuntime.syncChatGptModels(accountId, snapshot.models, accountId == selectFirstAccountId)
            }
        }
    }
}

internal fun shouldRetireChatGptPlanProfiles(account: ChatGptAccountSummary?): Boolean =
    account == null || !account.signedIn || !account.sharingEnabled
