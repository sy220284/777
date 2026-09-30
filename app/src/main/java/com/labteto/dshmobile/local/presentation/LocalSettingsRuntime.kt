package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalSessionStorageStatus
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class LocalSettingsMemoryContext(
    val conversationMode: LocalConversationMode,
    val projectId: String?,
    val lineageId: String,
)

/**
 * Narrow Settings capability boundary. UI depends on this surface instead of the process-wide
 * engine; implementation ownership can move behind this seam without changing Settings.
 */
@Singleton
class LocalSettingsRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    private val chatGptAuth: ChatGptAuthCoordinator,
) {
    val state: Flow<LocalHarnessSettingsState> =
        engine.state.map { it.toSettingsUiState() }.distinctUntilChanged()
    val initialState: LocalHarnessSettingsState get() = engine.state.value.toSettingsUiState()
    val chatGptState: kotlinx.coroutines.flow.StateFlow<ChatGptUiState> = chatGptAuth.state

    fun memoryContext(): LocalSettingsMemoryContext = engine.state.value.let {
        LocalSettingsMemoryContext(it.conversationMode, it.projectId, it.lineageId)
    }

    suspend fun refreshChatGpt() {
        chatGptAuth.refresh()
        val state = chatGptAuth.state.value
        val accountId = state.selectedAccountId ?: return
        engine.syncChatGptModels(accountId, state.models, selectFirst = false)
    }

    suspend fun connectChatGpt(existingAccountId: String? = null) {
        val account = chatGptAuth.connect(existingAccountId)
        engine.syncChatGptModels(account.id, chatGptAuth.state.value.models, selectFirst = true)
    }

    suspend fun selectChatGptAccount(id: String) {
        chatGptAuth.selectAccount(id)
        engine.syncChatGptModels(id, chatGptAuth.state.value.models, selectFirst = true)
    }

    suspend fun disconnectChatGptAccount(id: String) {
        engine.removeChatGptAccountProfiles(id)
        chatGptAuth.disconnect(id)
    }

    fun configureModel(apiKey: String, model: String, baseUrl: String) =
        engine.configure(apiKey, model, baseUrl)
    suspend fun saveModel(apiKey: String, model: String, baseUrl: String) =
        engine.saveModelConfiguration(apiKey, model, baseUrl)
    fun selectModel(id: String) = engine.selectModel(id)
    fun removeModel(id: String) = engine.removeModelProfile(id)
    suspend fun testModel(apiKey: String, model: String, baseUrl: String): String =
        engine.testModelConfiguration(apiKey, model, baseUrl)
    fun configureImageInputMode(mode: LocalImageInputMode) = engine.configureImageInputMode(mode)
    fun configureRuntimeLimits(main: Int, subagent: Int, attempts: Int) =
        engine.configureRuntimeLimits(main, subagent, attempts)
    fun configurePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean) =
        engine.configurePersonalization(rules, autoRecall, autoMemory)
    fun configureChatStyleGuard(enabled: Boolean) = engine.configureChatStyleGuard(enabled)
    fun addChatStyleGuardPhrase(value: String): Boolean = engine.addChatStyleGuardPhrase(value)
    fun removeChatStyleGuardPhrase(value: String) = engine.removeChatStyleGuardPhrase(value)
    fun clearChatStyleGuardHits() = engine.clearChatStyleGuardHits()
    suspend fun diagnoseNetwork(target: String): String = engine.diagnoseNetwork(target)
    suspend fun sessionStorageStatus(): LocalSessionStorageStatus = engine.sessionStorageStatusForUi()
    suspend fun compactSessionStorage(): LocalSessionStorageStatus = engine.compactSessionStorageForUi()
    suspend fun exportSessionStorage(output: OutputStream): Long = engine.exportSessionStorageForUi(output)
    suspend fun environmentInfo(): String = engine.environmentInfoForUi()
    suspend fun diagnosticReport(): String = engine.diagnosticReportForUi()
    fun clearCredential() = engine.clearCredential()
}
