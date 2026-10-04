package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalSessionStorageStatus
import com.labteto.dshmobile.local.model.LocalModelSettingsCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptPlanConnectionTester
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class LocalSettingsMemoryContext(
    val conversationMode: LocalConversationMode,
    val projectId: String?,
    val lineageId: String,
)

@Singleton
class LocalSettingsRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
    chatGptAuth: ChatGptAuthCoordinator,
    chatGptPlanTester: ChatGptPlanConnectionTester,
    private val modelSettings: LocalModelSettingsCoordinator,
    runtimeStateStore: LocalRuntimeStateStore,
) {
    private val chatGpt = ChatGptSettingsController(
        auth = chatGptAuth,
        requireAccountSelectionAllowed = engine::requireChatGptAccountSelectionAllowed,
        syncModels = engine::syncChatGptModels,
        retireProfiles = engine::retireChatGptAccountProfiles,
        removeProfiles = engine::removeChatGptAccountProfiles,
        testAccount = chatGptPlanTester::test,
    )
    private val runtimeState = runtimeStateStore.state
    val state: Flow<LocalHarnessSettingsState> = runtimeState.map { it.toSettingsUiState() }.distinctUntilChanged()
    val initialState get() = runtimeState.value.toSettingsUiState()
    val chatGptState: StateFlow<ChatGptUiState> = chatGpt.state

    fun memoryContext(): LocalSettingsMemoryContext = runtimeState.value.let {
        LocalSettingsMemoryContext(it.conversationMode, it.projectId, it.lineageId)
    }
    suspend fun refreshChatGpt() = chatGpt.refresh()
    suspend fun connectChatGpt(existingAccountId: String? = null, requestPlanConsent: Boolean = false) =
        chatGpt.connect(existingAccountId, requestPlanConsent)
    suspend fun restartChatGptAuthorization(existingAccountId: String? = null) = chatGpt.restart(existingAccountId)
    suspend fun cancelChatGptAuthorization() = chatGpt.cancelAuthorization()
    suspend fun testChatGptAccount(id: String): String = chatGpt.test(id)
    suspend fun selectChatGptAccount(id: String) = chatGpt.select(id)
    suspend fun disconnectChatGptAccount(id: String): String? = chatGpt.disconnect(id)
    suspend fun removeChatGptAccount(id: String): String? = chatGpt.remove(id)
    fun configureModel(apiKey: String, model: String, baseUrl: String) = engine.configure(apiKey, model, baseUrl)
    suspend fun saveModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null) = engine.saveModelConfiguration(apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride)
    fun selectModel(id: String) = engine.selectModel(id)
    fun removeModel(id: String) = engine.removeModelProfile(id)
    suspend fun testModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.LocalModelProtocol? = null, profileId: String? = null) = engine.testModelConfiguration(apiKey, model, baseUrl, protocol, profileId)
    fun configureImageInputMode(mode: LocalImageInputMode) = modelSettings.configureImageInputMode(mode)
    fun configureRuntimeLimits(main: Int, subagent: Int, attempts: Int, workerProfileId: String?) = engine.configureRuntimeLimits(main, subagent, attempts).also { engine.configureWorkerProfile(workerProfileId) }
    fun configurePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean) =
        engine.configurePersonalization(rules, autoRecall, autoMemory)
    fun configureChatStyleGuard(enabled: Boolean) = engine.configureChatStyleGuard(enabled)
    fun addChatStyleGuardPhrase(value: String) = engine.addChatStyleGuardPhrase(value)
    fun removeChatStyleGuardPhrase(value: String) = engine.removeChatStyleGuardPhrase(value)
    fun clearChatStyleGuardHits() = engine.clearChatStyleGuardHits()
    suspend fun diagnoseNetwork(target: String) = engine.diagnoseNetwork(target)
    suspend fun sessionStorageStatus(): LocalSessionStorageStatus = engine.sessionStorageStatusForUi()
    suspend fun compactSessionStorage(): LocalSessionStorageStatus = engine.compactSessionStorageForUi()
    suspend fun exportSessionStorage(output: OutputStream) = engine.exportSessionStorageForUi(output)
    suspend fun environmentInfo() = engine.environmentInfoForUi()
    suspend fun diagnosticReport() = engine.diagnosticReportForUi()
    fun clearCredential() = engine.clearCredential()
}
