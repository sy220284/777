package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalWebProvider
import com.labteto.dshmobile.local.security.LocalManagedExecutionPolicy
import com.labteto.dshmobile.uicontract.UiExecutionPolicySnapshot
import com.labteto.dshmobile.local.chat.LocalChatStyleGuardSettingsPort
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalModelRuntime
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxProviderQuotaClient
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.local.model.LocalModelSettingsCoordinator
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

@Singleton
class LocalSettingsRuntime @Inject internal constructor(
    private val diagnostics: LocalDiagnosticsPort,
    private val chatGpt: ChatGptSettingsController,
    private val modelRuntime: LocalModelRuntime,
    private val miniMaxQuota: MiniMaxProviderQuotaClient,
    private val modelSettings: LocalModelSettingsCoordinator,
    private val settingsCoordinator: LocalHarnessSettingsCoordinator,
    private val chatStyleGuardSettings: LocalChatStyleGuardSettingsPort,
    private val web: LocalWebProvider,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val approvalPreferences: LocalApprovalPreferences,
    private val executionPolicy: LocalManagedExecutionPolicy,
    runtimeStateStore: LocalRuntimeStateStore,
) : com.labteto.dshmobile.uicontract.SettingsUiPort {
    private val runtimeState = runtimeStateStore.state
    internal val nativeState: Flow<LocalHarnessSettingsState> = runtimeState.map { it.toSettingsUiState() }.distinctUntilChanged()
    internal val nativeInitialState: LocalHarnessSettingsState get() = runtimeState.value.toSettingsUiState()
    override val state: Flow<com.labteto.dshmobile.uicontract.UiSettingsSnapshot> = runtimeState.map { it.toSettingsUiState().toNeutralSnapshot() }.distinctUntilChanged()
    override val initialState get() = runtimeState.value.toSettingsUiState().toNeutralSnapshot()
    override val apiModelPresets get() = LocalModelPresets.entries
    override val chatGptState: StateFlow<ChatGptUiState> = chatGpt.state
    override val chatStyleGuardBuiltInPhrases: List<String> get() = chatStyleGuardSettings.builtInPhrases
    override val approvalMode: StateFlow<LocalApprovalMode> = approvalPreferences.approvalMode

    override fun configureApprovalMode(mode: LocalApprovalMode) = approvalPreferences.setApprovalMode(mode)

    override fun memoryContext(): LocalSettingsMemoryContext = runtimeState.value.let {
        LocalSettingsMemoryContext(it.conversationMode, it.projectId, it.lineageId)
    }
    override suspend fun refreshChatGpt() = chatGpt.refresh()
    override suspend fun connectChatGpt(existingAccountId: String?, requestPlanConsent: Boolean) =
        chatGpt.connect(existingAccountId, requestPlanConsent)
    override suspend fun restartChatGptAuthorization(existingAccountId: String?) = chatGpt.restart(existingAccountId)
    override suspend fun cancelChatGptAuthorization() = chatGpt.cancelAuthorization()
    override suspend fun testChatGptAccount(id: String): String = chatGpt.test(id)
    override suspend fun selectChatGptAccount(id: String) = chatGpt.select(id)
    override suspend fun disconnectChatGptAccount(id: String): String? = chatGpt.disconnect(id)
    override suspend fun removeChatGptAccount(id: String): String? = chatGpt.remove(id)
    override fun configureModel(apiKey: String, model: String, baseUrl: String) = modelRuntime.configure(apiKey, model, baseUrl)
    override suspend fun saveModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol?, profileId: String?, contextWindowTokensOverride: Int?, credentialSourceProfileId: String?) = modelRuntime.saveConfiguration(apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride, credentialSourceProfileId)
    override suspend fun queryMiniMaxQuota(profile: LocalModelProfile): MiniMaxProviderUsage = miniMaxQuota.query(profile)
    override fun selectModel(id: String) = modelRuntime.selectModel(id)
    override fun removeModel(id: String) = modelRuntime.removeModel(id)
    override suspend fun testModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol?, profileId: String?, credentialSourceProfileId: String?) = modelRuntime.testConfiguration(apiKey, model, baseUrl, protocol, profileId, credentialSourceProfileId)
    override suspend fun discoverApiModels(apiKey: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol, credentialSourceProfileId: String?): List<String> = modelRuntime.discoverApiModels(apiKey, baseUrl, protocol, credentialSourceProfileId)
    override fun configureImageInputMode(mode: LocalImageInputMode) = modelSettings.configureImageInputMode(mode)
    override fun configureRuntimeLimits(main: Int, subagent: Int, attempts: Int, workerProfileId: String?) =
        settingsCoordinator.configureRuntimeLimits(main, subagent, attempts, workerProfileId)
    override fun configurePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean) =
        settingsCoordinator.configurePersonalization(rules, autoRecall, autoMemory)
    override suspend fun savePersonalization(rules: String, autoRecall: Boolean, autoMemory: Boolean): Boolean =
        settingsCoordinator.savePersonalization(rules, autoRecall, autoMemory)
    override fun configureChatStyleGuard(enabled: Boolean) = chatStyleGuardSettings.configureEnabled(enabled)
    override fun addChatStyleGuardPhrase(value: String) = chatStyleGuardSettings.addPhrase(value)
    override fun removeChatStyleGuardPhrase(value: String) = chatStyleGuardSettings.removePhrase(value)
    override fun clearChatStyleGuardHits() = chatStyleGuardSettings.clearHits()
    override suspend fun diagnoseNetwork(target: String) = web.diagnose(target)
    override suspend fun sessionStorageStatus(): LocalSessionStorageStatus = sessionStorage.storageStatus()
    override suspend fun compactSessionStorage(): LocalSessionStorageStatus = sessionStorage.compactStorage()
    override suspend fun exportSessionStorage(output: OutputStream) = sessionStorage.exportStorage(output)
    override suspend fun exportCompleteSessionStorage(output: OutputStream) = sessionStorage.exportCompleteStorage(output)
    override suspend fun stageRestoreSessionStorage(archive: java.io.InputStream) =
        sessionStorage.stageRestoreStorage(archive)
    override suspend fun cancelStagedSessionRestore() = sessionStorage.cancelStagedRestore()
    override suspend fun stageLegacySessionRestore(archive: java.io.InputStream) =
        sessionStorage.stageLegacyRestoreStorage(archive)
    /** Read the current effective policy each time; never cache a stale grant or expose secrets. */
    internal fun executionPolicySummary(): UiExecutionPolicySnapshot {
        val policy = executionPolicy.current()
        val names = mapOf(
            "network.enabled" to "网络访问开关",
            "network.allowedHosts" to "可连接域名",
            "network.deniedHosts" to "禁止连接域名",
            "model.allowedProviders" to "模型供应商",
            "browser.history" to "浏览历史访问",
            "browser.upload" to "浏览器上传",
            "browser.download" to "浏览器下载",
            "browser.control" to "浏览器操作",
        )
        return UiExecutionPolicySnapshot(
            networkEnabled = policy.networkEnabled,
            allowedHosts = policy.allowedHosts?.sorted(),
            deniedHosts = policy.deniedHosts.sorted(),
            allowedModelProviders = policy.allowedModelProviders?.sorted(),
            browserHistoryEnabled = policy.browserHistoryAllowed,
            browserUploadEnabled = policy.browserUploadAllowed,
            browserDownloadEnabled = policy.browserDownloadAllowed,
            browserControlEnabled = policy.browserControlAllowed,
            managedRestrictions = policy.managedFields.sorted().map { names.getValue(it) },
        )
    }
    override suspend fun environmentInfo() = diagnostics.environmentInfo()
    override suspend fun diagnosticReport() = diagnostics.diagnosticReport()
    override fun clearCredential() = modelRuntime.clearCredential()
}

private fun LocalHarnessSettingsState.toNeutralSnapshot() = com.labteto.dshmobile.uicontract.UiSettingsSnapshot(
    loading, model, baseUrl, modelSelection, mainMaxSteps, subagentMaxSteps, modelAttempts,
    imageInputMode, userRules, autoRecall, autoMemory, chatStyleGuardEnabled, chatStyleGuardCustomPhrases,
    styleGuardHits, chatPersona.name, usage, error, miniMaxQuotaProfile, chatPersona.bannedPhrases,
)

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class LocalSettingsUiBindings {
    @dagger.Binds @Singleton
    abstract fun settings(implementation: LocalSettingsRuntime): com.labteto.dshmobile.uicontract.SettingsUiPort
}
