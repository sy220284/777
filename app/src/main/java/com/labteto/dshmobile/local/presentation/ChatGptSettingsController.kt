package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.observability.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Settings-facing ChatGPT account workflow; OAuth ownership remains in ChatGptAuthCoordinator. */
internal class ChatGptSettingsController(
    private val auth: ChatGptAuthCoordinator,
    private val requireAccountSelectionAllowed: () -> Unit,
    private val syncModels: suspend (String, List<ChatGptModelOption>, Boolean) -> Unit,
    private val removeProfiles: suspend (String) -> Unit,
    private val testAccount: suspend (String) -> String,
) {
    val state: StateFlow<ChatGptUiState> = auth.state

    suspend fun refresh() {
        try {
            auth.refresh()
            syncSelectedIfConnected()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warn("ChatGptSettingsController", "ChatGPT background refresh failed", error)
        }
    }

    suspend fun connect(existingAccountId: String? = null, requestPlanConsent: Boolean = false) {
        requireAccountSelectionAllowed()
        val account = auth.connect(existingAccountId, requestPlanConsent)
        if (auth.state.value.connected) syncModels(account.id, auth.state.value.models, true)
    }

    suspend fun restart(existingAccountId: String? = null) {
        requireAccountSelectionAllowed()
        val account = auth.restartAuthorization(existingAccountId)
        if (auth.state.value.connected) syncModels(account.id, auth.state.value.models, true)
    }

    suspend fun cancelAuthorization() = auth.cancelPendingAuthorization()

    /** Refresh auth state after the real probe so terminal credential failures surface immediately. */
    suspend fun test(id: String): String {
        val result = testAccount(id)
        if (state.value.selectedAccountId == id) {
            auth.refresh()
        }
        return result
    }

    suspend fun select(id: String) {
        requireAccountSelectionAllowed()
        auth.selectAccount(id)
        val snapshot = auth.state.value
        require(snapshot.connected) {
            snapshot.error ?: "ChatGPT 账户已登录，但套餐用量尚未启用"
        }
        syncModels(id, snapshot.models, true)
    }

    suspend fun disconnect(id: String): String? {
        removeProfiles(id)
        val warning = auth.disconnect(id)
        syncSelectedIfConnected()
        return warning
    }

    suspend fun remove(id: String): String? {
        removeProfiles(id)
        val warning = auth.remove(id)
        syncSelectedIfConnected()
        return warning
    }

    private suspend fun syncSelectedIfConnected() {
        val snapshot = auth.state.value
        if (snapshot.connected) {
            snapshot.selectedAccountId?.let { syncModels(it, snapshot.models, false) }
        }
    }
}
