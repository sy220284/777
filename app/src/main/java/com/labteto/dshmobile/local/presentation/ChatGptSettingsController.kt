package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import kotlinx.coroutines.flow.StateFlow

/** Settings-facing ChatGPT account workflow; OAuth ownership remains in ChatGptAuthCoordinator. */
internal class ChatGptSettingsController(
    private val auth: ChatGptAuthCoordinator,
    private val syncModels: suspend (String, List<ChatGptModelOption>, Boolean) -> Unit,
    private val removeProfiles: suspend (String) -> Unit,
) {
    val state: StateFlow<ChatGptUiState> = auth.state

    suspend fun refresh() {
        auth.refresh()
        val snapshot = auth.state.value
        snapshot.selectedAccountId?.let { syncModels(it, snapshot.models, false) }
    }

    suspend fun connect(existingAccountId: String? = null) {
        val account = auth.connect(existingAccountId)
        syncModels(account.id, auth.state.value.models, true)
    }

    suspend fun select(id: String) {
        auth.selectAccount(id)
        syncModels(id, auth.state.value.models, true)
    }

    suspend fun disconnect(id: String) {
        removeProfiles(id)
        auth.disconnect(id)
        val snapshot = auth.state.value
        snapshot.selectedAccountId?.let { syncModels(it, snapshot.models, false) }
    }
}
