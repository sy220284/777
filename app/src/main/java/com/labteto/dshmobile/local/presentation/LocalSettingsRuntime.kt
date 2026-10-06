package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalWebProvider
import com.labteto.dshmobile.local.chat.LocalChatStyleGuardSettingsPort
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelRuntime
import com.labteto.dshmobile.local.model.LocalModelSettingsCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthCoordinator
import com.labteto.dshmobile.local.model.chatgpt.ChatGptPlanConnectionTester
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsPort
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.local.settings.LocalHarnessSettingsCoordinator
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
class LocalSettingsRuntime @Inject internal constructor(
    private val diagnostics: LocalDiagnosticsPort,
    chatGptAuth: ChatGptAuthCoordinator,
    chatGptPlanTester: ChatGptPlanConnectionTester,
    private val modelRuntime: LocalModelRuntime,
    private val modelSettings: LocalModelSettingsCoordinator,
    private val settingsCoordinator: LocalHarnessSettingsCoordinator,
    private val chatStyleGuardSettings: LocalChatStyleGuardSettingsPort,
    private val web: LocalWebProvider,
    private val sessionStorage: LocalSessionStorageRuntime,
    runtimeStateStore: LocalRuntimeStateStore,
) {
    private val chatGpt = ChatGptSettingsController(
        auth = chatGptAuth,
        requireAccountSelectionAllowed = modelRuntime::requireChatGptAccountSelectionAllowed,
        syncModels = modelRuntime::syncChatGptModels,
        retireProfiles = modelRuntime::retireChatGptAccountProfiles,
        removeProfiles = modelRuntime::removeChatGptAccountProfiles,
        testAccount = chatGptPlanTester::test,
    )
    private val runtimeState = runtimeStateStore.state
    val state: Flow<LocalHarnessSettingsState> = runtimeState.map { it.toSettingsUiState() }.distinctUntilChanged()
    val initialState get() = runtimeState.value.toSettingsUiState()
    val chatGptState: StateFlow<ChatGptUiState> = chatGpt.state
    val chatStyleGuardBuiltInPhrases: List<String> get() = chatStyleGuardSettings.builtInPhrases

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
    fun configureModel(apiKey: String, model: String, baseUrl: String) = modelRuntime.configure(apiKey, model, baseUrl)
    suspend fun saveModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null) = modelRuntime.saveConfiguration(apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride)
    fun selectModel(id: String) = modelRuntime.selectModel(id)
    fun removeModel(id: String) = modelRuntime.removeModel(id)
    suspend fun testModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null) = modelRuntime.testConfiguration(apiKey, model, baseUrl, protocol, profileId)
    fun configureImageInputMode(mode: LocalImageInputMode) = modelSettings.configureImageInputMode(mode)
    fun configureRuntimeLimits(main: Int, subagent: Int, attempts: Int, workerProfileId: String?) =
        settingsCoordinator.configureRuntimeLimits(main, subagent, attempts).also {
            settingsCoordinator.configureWorkerProfile(workerProfileId)
        }
    fun configurePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean) =
        settingsCoordinator.configurePersonalization(rules, autoRecall, autoMemory)
    fun configureChatStyleGuard(enabled: Boolean) = chatStyleGuardSettings.configureEnabled(enabled)
    fun addChatStyleGuardPhrase(value: String) = chatStyleGuardSettings.addPhrase(value)
    fun removeChatStyleGuardPhrase(value: String) = chatStyleGuardSettings.removePhrase(value)
    fun clearChatStyleGuardHits() = chatStyleGuardSettings.clearHits()
    suspend fun diagnoseNetwork(target: String) = web.diagnose(target)
    suspend fun sessionStorageStatus(): LocalSessionStorageStatus = sessionStorage.storageStatus()
    suspend fun compactSessionStorage(): LocalSessionStorageStatus = sessionStorage.compactStorage()
    suspend fun exportSessionStorage(output: OutputStream) = sessionStorage.exportStorage(output)
    suspend fun environmentInfo() = diagnostics.environmentInfo()
    suspend fun diagnosticReport() = diagnostics.diagnosticReport()
    fun clearCredential() = modelRuntime.clearCredential()
}
