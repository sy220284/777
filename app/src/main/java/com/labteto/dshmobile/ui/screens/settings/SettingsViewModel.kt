package com.labteto.dshmobile.ui.screens.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.R
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.interaction.LocalApprovalMode
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.uicontract.UiSettingsSnapshot as LocalHarnessSettingsState
import com.labteto.dshmobile.local.presentation.LocalSettingsDataFacade
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.SettingsUiCommand
import com.labteto.dshmobile.uicontract.SettingsUiReceipt
import com.labteto.dshmobile.uicontract.submit
import com.labteto.dshmobile.uicontract.save
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.ui.theme.APP_BACKGROUND_DIR
import com.labteto.dshmobile.ui.theme.APP_BACKGROUND_FILE
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val localHarness: SettingsUiPort,
    private val settingsData: LocalSettingsDataFacade,
    private val memoryUi: com.labteto.dshmobile.uicontract.MemoryUiPort,
    private val usageUi: com.labteto.dshmobile.uicontract.UsageUiPort,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val deviceCapabilitiesController = DeviceCapabilitiesController(appContext)
    private val memorySettingsController = MemorySettingsController(viewModelScope, localHarness, memoryUi)
    private val backgroundChangeMutex = Mutex()

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val localHarnessState: StateFlow<LocalHarnessSettingsState> = localHarness.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = localHarness.initialState,
        )
    val chatGptState: StateFlow<ChatGptUiState> = localHarness.chatGptState
    val chatStyleGuardBuiltInPhrases: List<String> get() = localHarness.chatStyleGuardBuiltInPhrases
    val approvalMode: StateFlow<LocalApprovalMode> = localHarness.approvalMode
    val deepSeekPricing: StateFlow<DeepSeekPricingState> = settingsData.deepSeekPricing
    val usageAnalytics: StateFlow<TokenUsageAnalyticsSnapshot> = usageUi.revision
        .mapLatest {
            withContext(Dispatchers.IO) { usageUi.snapshot() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TokenUsageAnalyticsSnapshot(),
        )

    internal val memoryManagement = memorySettingsController.state
    val memories: StateFlow<List<MemoryRecord>> = memoryManagement.map { it.records }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deviceCapabilities: StateFlow<DeviceCapabilitiesState> =
        deviceCapabilitiesController.state

    init {
        viewModelScope.launch {
            hostsStore.settings.collect { _state.value = it }
        }
        refreshAdvancedSettings()
        refreshChatGpt()
    }

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { hostsStore.setSetting(transform) }
    }

    private fun applySetting(command: com.labteto.dshmobile.uicontract.SettingsUiCommand): Boolean {
        return when (val receipt = localHarness.submit(command)) {
            com.labteto.dshmobile.uicontract.SettingsUiReceipt.Applied,
            com.labteto.dshmobile.uicontract.SettingsUiReceipt.Submitted -> true
            com.labteto.dshmobile.uicontract.SettingsUiReceipt.Rejected -> false
            is com.labteto.dshmobile.uicontract.SettingsUiReceipt.Failed -> throw IllegalStateException(receipt.message)
        }
    }

    fun configureApprovalMode(mode: LocalApprovalMode) = applySetting(SettingsUiCommand.ApprovalMode(mode))

    fun configureChatStyleGuard(enabled: Boolean) {
        applySetting(SettingsUiCommand.StyleGuard(enabled))
    }

    fun configureImageInputMode(mode: com.labteto.dshmobile.local.model.LocalImageInputMode) {
        applySetting(SettingsUiCommand.ImageInputMode(mode))
    }

    fun addChatStyleGuardPhrase(phrase: String): Boolean =
        applySetting(SettingsUiCommand.AddStylePhrase(phrase))

    fun removeChatStyleGuardPhrase(phrase: String) {
        applySetting(SettingsUiCommand.RemoveStylePhrase(phrase))
    }

    fun clearChatStyleGuardHits() {
        applySetting(SettingsUiCommand.ClearStyleHits)
    }

    /**
     * Copy a picked image into app storage and remember it as the app background.
     *
     * The bytes are copied rather than the URI remembered: a picker grant is temporary, can be
     * revoked, and does not outlive the process, so a remembered URI would produce a background that
     * silently disappears later — indistinguishable, from the outside, from the feature being broken.
     */
    fun setBackgroundImage(uri: Uri, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                // Never replace the image at an unchanged path: Compose caches by path.
                backgroundChangeMutex.withLock {
                    val stored = importBackgroundImage(uri)
                    hostsStore.setSetting { it.copy(backgroundImagePath = stored) }
                    removeInactiveBackgroundFiles(stored)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "import_background_image", error)
                onError(appContext.getString(R.string.settings_background_import_failed))
            }
        }
    }

    /** Clear the persisted reference before removing the managed file. */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundChangeMutex.withLock {
                hostsStore.setSetting { it.copy(backgroundImagePath = null) }
                removeInactiveBackgroundFiles(null)
            }
        }
    }

    private suspend fun importBackgroundImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, APP_BACKGROUND_DIR).apply { mkdirs() }
        // Each import gets a new identity, triggering both settings and wallpaper analysis.
        val target = File(directory, "${APP_BACKGROUND_FILE}-${UUID.randomUUID()}.image")
        val staging = File.createTempFile("background-", ".part", directory)
        try {
            val source = appContext.contentResolver.openInputStream(uri)
                ?: error("Cannot open the picked image")
            source.use { input -> staging.outputStream().use { output -> input.copyTo(output) } }
            // Validate the staged bytes before persisting a reference or deleting the last
            // usable wallpaper. An image/* provider is allowed to return undecodable data.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staging.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid background image" }
            check(staging.renameTo(target)) { "Cannot commit the background image" }
        } finally {
            staging.delete()
        }
        target.absolutePath
    }

    private suspend fun removeInactiveBackgroundFiles(activePath: String?) = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, APP_BACKGROUND_DIR)
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath != activePath &&
                (file.name == APP_BACKGROUND_FILE || file.name.startsWith("${APP_BACKGROUND_FILE}-"))
            ) {
                if (!file.delete()) {
                    AppLog.failure("Settings", "background_cleanup",
                        IllegalStateException("Cannot delete old background"))
                }
            }
        }
    }

    /** Reload every capability-backed Settings section without requiring an app restart. */
    fun refreshAdvancedSettings() {
        refreshDeviceCapabilities()
        refreshMemories()
    }

    fun refreshDeepSeekPricing() {
        viewModelScope.launch { settingsData.refreshDeepSeekPricing() }
    }

    suspend fun saveLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null, credentialSourceProfileId: String? = null) =
        withContext(Dispatchers.IO) {
            when (val receipt = localHarness.save(SettingsUiCommand.SaveModel(apiKey, model, baseUrl, protocol,
                profileId, contextWindowTokensOverride, credentialSourceProfileId))) {
                SettingsUiReceipt.Applied -> Unit
                is SettingsUiReceipt.Failed -> throw IllegalStateException(receipt.message)
                else -> error("Model configuration was not applied")
            }
        }

    internal suspend fun queryMiniMaxQuota(profile: LocalModelProfile): MiniMaxProviderUsage =
        withContext(Dispatchers.IO) { localHarness.queryMiniMaxQuota(profile) }

    fun selectLocalModel(id: String) = applySetting(SettingsUiCommand.SelectModel(id))

    fun connectChatGpt(existingAccountId: String? = null, requestPlanConsent: Boolean = false, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                localHarness.connectChatGpt(existingAccountId, requestPlanConsent)
                onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "connect_chatgpt", error)
                onDone(error.message ?: "ChatGPT 登录失败")
            }
        }
    }

    fun restartChatGptAuthorization(existingAccountId: String? = null, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                localHarness.restartChatGptAuthorization(existingAccountId)
                onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "restart_chatgpt_authorization", error)
                onDone(error.message ?: "重新开始 ChatGPT 授权失败")
            }
        }
    }

    fun cancelChatGptAuthorization() {
        viewModelScope.launch { localHarness.cancelChatGptAuthorization() }
    }

    fun testChatGptAccount(id: String, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(localHarness.testChatGptAccount(id)) }
    }

    private var latestChatGptAccountTap = 0L

    fun selectChatGptAccount(id: String, onDone: (String?) -> Unit = {}) {
        val tap = ++latestChatGptAccountTap
        viewModelScope.launch {
            try {
                localHarness.selectChatGptAccount(id)
                if (tap == latestChatGptAccountTap) onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "select_chatgpt_account", error)
                if (tap == latestChatGptAccountTap) {
                    onDone(error.message ?: "切换 ChatGPT 账户失败")
                }
            }
        }
    }

    fun disconnectChatGptAccount(id: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onDone(localHarness.disconnectChatGptAccount(id))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "disconnect_chatgpt_account", error)
                onDone(error.message ?: "断开 ChatGPT 账户失败")
            }
        }
    }

    fun removeChatGptAccount(id: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onDone(localHarness.removeChatGptAccount(id))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.failure("Settings", "remove_chatgpt_account", error)
                onDone(error.message ?: "移除 ChatGPT 授权记录失败")
            }
        }
    }
    fun refreshChatGpt() {
        viewModelScope.launch { localHarness.refreshChatGpt() }
    }
    fun removeLocalModel(id: String) = applySetting(SettingsUiCommand.RemoveModel(id))

    suspend fun testLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null, credentialSourceProfileId: String? = null): String =
        localHarness.testModel(apiKey, model, baseUrl, protocol, profileId, credentialSourceProfileId)

    suspend fun discoverApiModels(apiKey: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol, credentialSourceProfileId: String? = null): List<String> =
        withContext(Dispatchers.IO) { localHarness.discoverApiModels(apiKey, baseUrl, protocol, credentialSourceProfileId) }

    fun configureLocalMemory(userRules: String, autoRecall: Boolean, autoMemory: Boolean) {
        applySetting(SettingsUiCommand.Personalization(userRules, autoRecall, autoMemory))
    }

    fun refreshMemories() = memorySettingsController.refresh()
    fun loadOlderMemories() = memorySettingsController.loadOlder()
    fun searchMemories(query: String, kinds: Set<com.labteto.dshmobile.local.memory.MemoryKind>) = memorySettingsController.search(query, kinds)
    fun focusMemorySourceMessage(sessionId: String?, messageId: String?) =
        memorySettingsController.focusSourceMessage(sessionId, messageId)

    fun updateMemory(
        id: String,
        content: String,
        pinned: Boolean,
        onDone: (String?) -> Unit = {},
    ) = memorySettingsController.update(id, content, pinned, onDone)

    fun forgetMemory(id: String, onDone: (String?) -> Unit = {}) =
        memorySettingsController.forget(id, onDone)

    fun configureLocalAgent(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int, workerProfileId: String?) {
        applySetting(SettingsUiCommand.RuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts, workerProfileId))
    }

    suspend fun diagnoseNetwork(target: String): String = localHarness.diagnoseNetwork(target)

    suspend fun localSessionStorageStatus(): LocalSessionStorageStatus =
        localHarness.sessionStorageStatus()

    suspend fun compactLocalSessionStorage(): LocalSessionStorageStatus =
        localHarness.compactSessionStorage()

    suspend fun exportLocalSessionStorage(uri: Uri): Long = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri)?.use { output ->
            localHarness.exportSessionStorage(output)
        } ?: error("无法打开会话归档导出文件")
    }

    suspend fun environmentInfo(): String = localHarness.environmentInfo()

    suspend fun diagnosticReport(): String = localHarness.diagnosticReport()

    suspend fun usageGroupDetail(
        kind: TokenUsageGroupKind,
        key: String,
        modelFilter: String? = null,
    ): TokenUsageGroupDetail? = withContext(Dispatchers.IO) {
        usageUi.groupDetail(kind, key, modelFilter)
    }

    suspend fun usageRecord(requestId: String): TokenUsageRecord? = withContext(Dispatchers.IO) {
        usageUi.requestDetail(requestId)
    }

    fun refreshDeviceCapabilities() = deviceCapabilitiesController.refresh()

    fun openAccessibilitySettings() = deviceCapabilitiesController.openAccessibilitySettings()

    fun openNotificationAccessSettings() =
        deviceCapabilitiesController.openNotificationAccessSettings()

    fun openAppNotificationSettings() = deviceCapabilitiesController.openAppNotificationSettings()

}
