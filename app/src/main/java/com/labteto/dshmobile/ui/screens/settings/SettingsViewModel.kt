package com.labteto.dshmobile.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.RelayCredentialStore
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import com.labteto.dshmobile.device.AndroidDeviceProvider
import com.labteto.dshmobile.device.accessibility.HarnessAccessibilityService
import com.labteto.dshmobile.device.notifications.HarnessNotificationListenerService
import com.labteto.dshmobile.local.DeepSeekPricingRepository
import com.labteto.dshmobile.local.DeepSeekPricingState
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalImageInputMode
import com.labteto.dshmobile.local.LocalVisionSettings
import com.labteto.dshmobile.local.LocalVisionSettingsSnapshot
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** App-language choices deliberately limited to the two maintained translations. */
data class LanguageOption(val tag: String, val label: String)

val LanguageOptions = listOf(
    LanguageOption("en", "English"),
    LanguageOption("zh-CN", "中文"),
)

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
    val shizukuAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    val virtualDisplay: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val credentials: RelayCredentialStore,
    private val connectionManager: ConnectionManager,
    private val localHarness: LocalHarnessEngine,
    private val deepSeekPricingRepository: DeepSeekPricingRepository,
    private val localVisionSettings: LocalVisionSettings,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val appContext = context.applicationContext
    private val deviceProvider = AndroidDeviceProvider(appContext)

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val localHarnessState: StateFlow<LocalHarnessState> = localHarness.state
    val deepSeekPricing: StateFlow<DeepSeekPricingState> = deepSeekPricingRepository.state

    private val _memories = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val memories: StateFlow<List<MemoryRecord>> = _memories.asStateFlow()

    private val _visionSettings = MutableStateFlow(LocalVisionSettingsSnapshot())
    val visionSettings: StateFlow<LocalVisionSettingsSnapshot> = _visionSettings.asStateFlow()

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
    }

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { hostsStore.setSetting(transform) }
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
        refreshVisionSettings()
    }

    fun refreshDeepSeekPricing() {
        viewModelScope.launch { deepSeekPricingRepository.refreshFromOfficial() }
    }

    fun refreshVisionSettings() {
        viewModelScope.launch {
            _visionSettings.value = runCatching { localVisionSettings.snapshot() }
                .getOrElse { LocalVisionSettingsSnapshot() }
        }
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

    fun configureLocalHarness(
        apiKey: String,
        model: String,
        baseUrl: String,
        mainMaxSteps: Int,
        subagentMaxSteps: Int,
        modelAttempts: Int,
        userRules: String,
        autoRecall: Boolean,
        autoMemory: Boolean,
    ) {
        localHarness.configure(apiKey, model, baseUrl)
        localHarness.configureRuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts)
        localHarness.configurePersonalization(userRules, autoRecall, autoMemory)
    }

    fun configureLocalModel(apiKey: String, model: String, baseUrl: String) {
        localHarness.configure(apiKey, model, baseUrl)
    }

    fun configureLocalImageInputMode(mode: LocalImageInputMode) {
        localHarness.configureImageInputMode(mode)
    }

    fun configureLocalMemory(userRules: String, autoRecall: Boolean, autoMemory: Boolean) {
        localHarness.configurePersonalization(userRules, autoRecall, autoMemory)
    }

    fun refreshMemories() {
        val local = localHarness.state.value
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

    fun environmentInfo(): String = localHarness.environmentInfoForUi()

    fun configureLocalVision(
        apiKey: String,
        model: String,
        baseUrl: String,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                localVisionSettings.configure(apiKey, model, baseUrl)
            }.onSuccess { snapshot ->
                _visionSettings.value = snapshot
                onDone(null)
            }.onFailure { error ->
                onDone(error.message ?: "视觉模型配置失败")
            }
        }
    }

    fun clearLocalVisionCredential(onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { localVisionSettings.clearCredential() }
                .onSuccess { snapshot ->
                    _visionSettings.value = snapshot
                    onDone(null)
                }
                .onFailure { error -> onDone(error.message ?: "清除视觉模型密钥失败") }
        }
    }

    fun clearLocalCredential() {
        localHarness.clearCredential()
    }

    fun refreshDeviceCapabilities() {
        viewModelScope.launch {
            _deviceCapabilities.value = _deviceCapabilities.value.copy(loading = true, error = null)
            runCatching {
                val raw = deviceProvider.invoke("shizuku_status", emptyMap())
                fun flag(name: String): Boolean =
                    raw.lineSequence().firstOrNull { it.startsWith("$name=") }
                        ?.substringAfter('=')
                        ?.toBooleanStrictOrNull() == true
                DeviceCapabilitiesState(
                    loading = false,
                    shizukuAlive = flag("binder_alive"),
                    shizukuGranted = flag("permission"),
                    accessibility = HarnessAccessibilityService.active() != null,
                    notifications = HarnessNotificationListenerService.active() != null,
                    virtualDisplay = true,
                )
            }.onSuccess {
                _deviceCapabilities.value = it
            }.onFailure {
                _deviceCapabilities.value = DeviceCapabilitiesState(
                    loading = false,
                    accessibility = HarnessAccessibilityService.active() != null,
                    notifications = HarnessNotificationListenerService.active() != null,
                    virtualDisplay = true,
                    error = it.message,
                )
            }
        }
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            runCatching { deviceProvider.invoke("shizuku_request_permission", emptyMap()) }
            refreshDeviceCapabilities()
        }
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

    /** Forget every remembered harness and its locally stored relay credential. */
    fun forgetHosts(onDone: () -> Unit = {}) {
        connectionManager.disconnect()
        viewModelScope.launch {
            hostsStore.clearHosts()
            credentials.clear()
            onDone()
        }
    }

    /** Forget which session to reopen per harness; the app lands on the newest one next time. */
    fun clearLastSessions(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.clearLastSessions()
            onDone()
        }
    }
}
