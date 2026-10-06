package com.labteto.dshmobile.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.presentation.LocalSettingsDataFacade
import com.labteto.dshmobile.local.presentation.LocalSettingsRuntime
import com.labteto.dshmobile.local.session.LocalSessionStorageStatus
import com.labteto.dshmobile.ui.theme.APP_BACKGROUND_DIR
import com.labteto.dshmobile.ui.theme.APP_BACKGROUND_FILE
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
    private val localHarness: LocalSettingsRuntime,
    private val settingsData: LocalSettingsDataFacade,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val remoteSettingsController = RemoteSettingsController(connectionManager, viewModelScope)
    private val deviceCapabilitiesController = DeviceCapabilitiesController(appContext)
    private val memorySettingsController = MemorySettingsController(localHarness, settingsData)

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
    val deepSeekPricing: StateFlow<DeepSeekPricingState> = settingsData.deepSeekPricing
    val usageAnalytics: StateFlow<TokenUsageAnalyticsSnapshot> = settingsData.usageRevision
        .mapLatest {
            withContext(Dispatchers.IO) { settingsData.usageSnapshot() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TokenUsageAnalyticsSnapshot(),
        )

    val memories: StateFlow<List<MemoryRecord>> = memorySettingsController.memories

    val projectSettings: StateFlow<RemoteProjectSettingsState> =
        remoteSettingsController.projectSettings
    val modelServices: StateFlow<ModelServicesState> =
        remoteSettingsController.modelServices
    val deviceCapabilities: StateFlow<DeviceCapabilitiesState> =
        deviceCapabilitiesController.state

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ConnectionUiState(),
    )

    val sessionSort: StateFlow<String> = hostsStore.sessionSort.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        "manual",
    )

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

    fun configureChatStyleGuard(enabled: Boolean) {
        localHarness.configureChatStyleGuard(enabled)
    }

    fun addChatStyleGuardPhrase(phrase: String): Boolean =
        localHarness.addChatStyleGuardPhrase(phrase)

    fun removeChatStyleGuardPhrase(phrase: String) {
        localHarness.removeChatStyleGuardPhrase(phrase)
    }

    fun clearChatStyleGuardHits() {
        localHarness.clearChatStyleGuardHits()
    }

    /**
     * Copy a picked image into app storage and remember it as the app background.
     *
     * The bytes are copied rather than the URI remembered: a picker grant is temporary, can be
     * revoked, and does not outlive the process, so a remembered URI would produce a background that
     * silently disappears later — indistinguishable, from the outside, from the feature being broken.
     */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            val stored = runCatching { importBackgroundImage(uri) }.getOrNull() ?: return@launch
            hostsStore.setSetting { it.copy(backgroundImagePath = stored) }
        }
    }

    /** Drop the background image. The copied file stays until the next import overwrites it. */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            hostsStore.setSetting { it.copy(backgroundImagePath = null) }
        }
    }

    private suspend fun importBackgroundImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, APP_BACKGROUND_DIR).apply { mkdirs() }
        val target = File(directory, APP_BACKGROUND_FILE)
        val source = appContext.contentResolver.openInputStream(uri)
            ?: error("Cannot open the picked image")
        source.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target.absolutePath
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun setSessionSortByRecency(enabled: Boolean) {
        viewModelScope.launch {
            hostsStore.setSessionSort(if (enabled) "updated" else "manual")
        }
    }

    /** Reload every capability-backed Settings section without requiring an app restart. */
    fun refreshAdvancedSettings() {
        refreshRemoteSettings()
        refreshDeviceCapabilities()
        refreshMemories()
    }

    fun refreshDeepSeekPricing() {
        viewModelScope.launch { settingsData.refreshDeepSeekPricing() }
    }

    fun refreshRemoteSettings() = remoteSettingsController.refresh()

    fun setRemoteSetting(
        namespace: SettingsNamespaceView,
        path: List<String>,
        value: JsonElement,
        onDone: (String?) -> Unit = {},
    ) = remoteSettingsController.set(namespace, path, value, onDone)

    fun unsetRemoteSetting(
        namespace: SettingsNamespaceView,
        path: List<String>,
        onDone: (String?) -> Unit = {},
    ) = remoteSettingsController.unset(namespace, path, onDone)

    fun discoverModels(provider: LlmConfigurableProvider) =
        remoteSettingsController.discoverModels(provider)

    suspend fun saveLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null, contextWindowTokensOverride: Int? = null) =
        withContext(Dispatchers.IO) { localHarness.saveModel(apiKey, model, baseUrl, protocol, profileId, contextWindowTokensOverride) }

    fun selectLocalModel(id: String) = localHarness.selectModel(id)

    fun connectChatGpt(existingAccountId: String? = null, requestPlanConsent: Boolean = false, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                localHarness.connectChatGpt(existingAccountId, requestPlanConsent)
                onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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

    fun selectChatGptAccount(id: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                localHarness.selectChatGptAccount(id)
                onDone(null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onDone(error.message ?: "切换 ChatGPT 账户失败")
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
                onDone(error.message ?: "移除 ChatGPT 授权记录失败")
            }
        }
    }
    fun refreshChatGpt() {
        viewModelScope.launch { localHarness.refreshChatGpt() }
    }
    fun removeLocalModel(id: String) = localHarness.removeModel(id)

    suspend fun testLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.model.LocalModelProtocol? = null, profileId: String? = null): String =
        localHarness.testModel(apiKey, model, baseUrl, protocol, profileId)

    fun configureLocalMemory(userRules: String, autoRecall: Boolean, autoMemory: Boolean) {
        localHarness.configurePersonalization(userRules, autoRecall, autoMemory)
    }

    fun refreshMemories() = memorySettingsController.refresh()

    fun updateMemory(
        id: String,
        content: String,
        pinned: Boolean,
        onDone: (String?) -> Unit = {},
    ) = memorySettingsController.update(id, content, pinned, onDone)

    fun forgetMemory(id: String, onDone: (String?) -> Unit = {}) =
        memorySettingsController.forget(id, onDone)

    fun configureLocalAgent(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int, workerProfileId: String?) {
        localHarness.configureRuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts, workerProfileId)
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
    ): TokenUsageGroupDetail? = withContext(Dispatchers.IO) {
        settingsData.usageGroupDetail(kind, key)
    }

    suspend fun usageRecord(requestId: String): TokenUsageRecord? = withContext(Dispatchers.IO) {
        settingsData.usageRecord(requestId)
    }

    fun refreshDeviceCapabilities() = deviceCapabilitiesController.refresh()

    fun openAccessibilitySettings() = deviceCapabilitiesController.openAccessibilitySettings()

    fun openNotificationAccessSettings() =
        deviceCapabilitiesController.openNotificationAccessSettings()

}
