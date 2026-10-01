package com.labteto.dshmobile.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import com.labteto.dshmobile.device.accessibility.HarnessAccessibilityService
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import com.labteto.dshmobile.local.DeepSeekPricingRepository
import com.labteto.dshmobile.local.DeepSeekPricingState
import com.labteto.dshmobile.local.DeepSeekUsageTracker
import com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot
import com.labteto.dshmobile.local.TokenUsageGroupDetail
import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.presentation.LocalSettingsRuntime
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.LocalSessionStorageStatus
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
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

data class RemoteProjectSettingsState(
    val loading: Boolean = false,
    val available: Boolean = false,
    val writable: Boolean = false,
    val namespaces: List<SettingsNamespaceView> = emptyList(),
    val error: String? = null,
)

data class ModelServicesState(
    val loading: Boolean = false,
    val providers: List<LlmConfigurableProvider> = emptyList(),
    val discovered: Map<String, List<LlmDiscoveredModel>> = emptyMap(),
    val error: String? = null,
)

data class DeviceCapabilitiesState(
    val loading: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    val virtualDisplay: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
    private val localHarness: LocalSettingsRuntime,
    private val deepSeekPricingRepository: DeepSeekPricingRepository,
    private val usageTracker: DeepSeekUsageTracker,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val localHarnessState: StateFlow<LocalHarnessSettingsState> = localHarness.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = localHarness.initialState,
        )
    val chatGptState: StateFlow<ChatGptUiState> = localHarness.chatGptState
    val deepSeekPricing: StateFlow<DeepSeekPricingState> = deepSeekPricingRepository.state
    val usageAnalytics: StateFlow<TokenUsageAnalyticsSnapshot> = usageTracker.analyticsRevision
        .mapLatest {
            withContext(Dispatchers.IO) { usageTracker.analyticsSnapshot() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TokenUsageAnalyticsSnapshot(),
        )

    private val _memories = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val memories: StateFlow<List<MemoryRecord>> = _memories.asStateFlow()


    private val _projectSettings = MutableStateFlow(RemoteProjectSettingsState())
    val projectSettings: StateFlow<RemoteProjectSettingsState> = _projectSettings.asStateFlow()

    private val _modelServices = MutableStateFlow(ModelServicesState())
    val modelServices: StateFlow<ModelServicesState> = _modelServices.asStateFlow()

    private val _deviceCapabilities = MutableStateFlow(DeviceCapabilitiesState())
    val deviceCapabilities: StateFlow<DeviceCapabilitiesState> = _deviceCapabilities.asStateFlow()

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
        viewModelScope.launch { localHarness.refreshChatGpt() }
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
        viewModelScope.launch { deepSeekPricingRepository.refreshFromOfficial() }
    }

    fun refreshRemoteSettings() {
        viewModelScope.launch {
            val api = connectionManager.connectedApi
            if (api == null || connectionManager.state.value.phase != ConnectionPhase.CONNECTED) {
                _projectSettings.value = RemoteProjectSettingsState()
                _modelServices.value = ModelServicesState()
                return@launch
            }

            _projectSettings.value = _projectSettings.value.copy(loading = true, error = null)
            when (val result = api.settingsDescribe()) {
                is RpcResult.Ok -> {
                    val value: SettingsDescribeValue = result.value
                    _projectSettings.value = RemoteProjectSettingsState(
                        loading = false,
                        available = true,
                        writable = value.writable,
                        namespaces = value.namespaces,
                    )
                }
                is RpcResult.Err -> {
                    _projectSettings.value = RemoteProjectSettingsState(
                        loading = false,
                        error = result.error.message,
                    )
                }
            }

            _modelServices.value = _modelServices.value.copy(loading = true, error = null)
            when (val result = api.llmListConfigurableProviders()) {
                is RpcResult.Ok -> {
                    _modelServices.value = _modelServices.value.copy(
                        loading = false,
                        providers = result.value,
                        error = null,
                    )
                }
                is RpcResult.Err -> {
                    _modelServices.value = ModelServicesState(
                        loading = false,
                        error = result.error.message,
                    )
                }
            }
        }
    }

    fun setRemoteSetting(
        namespace: SettingsNamespaceView,
        path: List<String>,
        value: JsonElement,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val api = connectionManager.connectedApi
            if (api == null) {
                onDone("当前没有已连接的 Harness")
                return@launch
            }
            val revision = namespace.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            when (
                val result = api.settingsMutate(
                    ns = namespace.ns,
                    ops = listOf(SettingsPathOpView.Set(path = path, value = value)),
                    expectedRevision = revision,
                )
            ) {
                is RpcResult.Ok -> {
                    replaceNamespace(result.value)
                    onDone(null)
                }
                is RpcResult.Err -> {
                    onDone(result.error.message)
                    refreshRemoteSettings()
                }
            }
        }
    }

    fun unsetRemoteSetting(
        namespace: SettingsNamespaceView,
        path: List<String>,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val api = connectionManager.connectedApi
            if (api == null) {
                onDone("当前没有已连接的 Harness")
                return@launch
            }
            val revision = namespace.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            when (
                val result = api.settingsMutate(
                    ns = namespace.ns,
                    ops = listOf(SettingsPathOpView.Unset(path = path)),
                    expectedRevision = revision,
                )
            ) {
                is RpcResult.Ok -> {
                    replaceNamespace(result.value)
                    onDone(null)
                }
                is RpcResult.Err -> {
                    onDone(result.error.message)
                    refreshRemoteSettings()
                }
            }
        }
    }

    fun discoverModels(provider: LlmConfigurableProvider) {
        viewModelScope.launch {
            val api = connectionManager.connectedApi ?: return@launch
            _modelServices.value = _modelServices.value.copy(loading = true, error = null)
            when (
                val result = api.llmDiscoverModels(
                    settingsNs = provider.settingsNs,
                    request = LlmModelDiscoveryRequest(provider = provider.provider),
                )
            ) {
                is RpcResult.Ok -> {
                    _modelServices.value = _modelServices.value.copy(
                        loading = false,
                        discovered = _modelServices.value.discovered + (provider.provider to result.value),
                        error = null,
                    )
                }
                is RpcResult.Err -> {
                    _modelServices.value = _modelServices.value.copy(
                        loading = false,
                        error = result.error.message,
                    )
                }
            }
        }
    }

    suspend fun saveLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.LocalModelProtocol? = null, profileId: String? = null) =
        withContext(Dispatchers.IO) { localHarness.saveModel(apiKey, model, baseUrl, protocol, profileId) }

    fun selectLocalModel(id: String) = localHarness.selectModel(id)

    fun connectChatGpt(existingAccountId: String? = null, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                localHarness.connectChatGpt(existingAccountId)
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
        viewModelScope.launch { runCatching { localHarness.refreshChatGpt() } }
    }

    fun removeLocalModel(id: String) = localHarness.removeModel(id)

    suspend fun testLocalModel(apiKey: String, model: String, baseUrl: String, protocol: com.labteto.dshmobile.local.LocalModelProtocol? = null, profileId: String? = null): String =
        localHarness.testModel(apiKey, model, baseUrl, protocol, profileId)

    fun configureLocalMemory(userRules: String, autoRecall: Boolean, autoMemory: Boolean) {
        localHarness.configurePersonalization(userRules, autoRecall, autoMemory)
    }

    fun refreshMemories() {
        val local = localHarness.memoryContext()
        val scopes = when (local.conversationMode) {
            com.labteto.dshmobile.local.LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            com.labteto.dshmobile.local.LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            com.labteto.dshmobile.local.LocalConversationMode.CONTINUATION ->
                setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.LINEAGE)
        }
        _memories.value = memoryStore.listActive(
            allowedScopes = scopes,
            projectId = local.projectId,
            lineageId = local.lineageId,
            limit = 100,
        )
    }

    fun updateMemory(
        id: String,
        content: String,
        pinned: Boolean,
        onDone: (String?) -> Unit = {},
    ) {
        val current = _memories.value.firstOrNull { it.id == id }
        if (current == null) {
            onDone("记忆已经不存在")
            refreshMemories()
            return
        }
        runCatching {
            memoryManager.update(
                existing = current,
                content = content,
                pinned = pinned,
            )
        }.onSuccess {
            refreshMemories()
            onDone(null)
        }.onFailure { error ->
            onDone(error.message ?: "更新记忆失败")
        }
    }

    fun forgetMemory(id: String, onDone: (String?) -> Unit = {}) {
        runCatching { memoryStore.forget(id) }
            .onSuccess {
                refreshMemories()
                onDone(null)
            }
            .onFailure { error -> onDone(error.message ?: "停用记忆失败") }
    }

    fun configureLocalAgent(mainMaxSteps: Int, subagentMaxSteps: Int, modelAttempts: Int) {
        localHarness.configureRuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts)
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
        usageTracker.analyticsGroupDetail(kind, key)
    }

    suspend fun usageRecord(requestId: String): TokenUsageRecord? = withContext(Dispatchers.IO) {
        usageTracker.analyticsRecord(requestId)
    }

    fun refreshDeviceCapabilities() {
        _deviceCapabilities.value = DeviceCapabilitiesState(
            loading = false,
            accessibility = HarnessAccessibilityService.active() != null,
            notifications = HarnessNotificationListenerService.active() != null,
            virtualDisplay = true,
        )
    }

    fun openAccessibilitySettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openNotificationAccessSettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun replaceNamespace(updated: SettingsNamespaceView) {
        _projectSettings.value = _projectSettings.value.copy(
            namespaces = _projectSettings.value.namespaces.map {
                if (it.ns == updated.ns) updated else it
            },
        )
    }

}
