package com.labteto.dshmobile.ui.screens.settings

import android.content.Context
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
import com.labteto.dshmobile.local.LocalHarnessEngine
import com.labteto.dshmobile.local.LocalHarnessState
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

/** App-language choices: the 11 shipped locales, plus following the system. */
data class LanguageOption(val tag: String?, val label: String?, val labelRes: Int? = null)

val LanguageOptions = listOf(
    LanguageOption(null, null, com.labteto.dshmobile.R.string.settings_language_system),
    LanguageOption("en", "English"),
    LanguageOption("zh", "中文"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("ar", "العربية"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ur", "اردو"),
    LanguageOption("th", "ไทย"),
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
    @ApplicationContext context: Context,
) : ViewModel() {

    private val deviceProvider = AndroidDeviceProvider(context)

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val localHarnessState: StateFlow<LocalHarnessState> = localHarness.state

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
    ) {
        localHarness.configure(apiKey, model, baseUrl)
        localHarness.configureRuntimeLimits(mainMaxSteps, subagentMaxSteps, modelAttempts)
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

    private fun replaceNamespace(updated: SettingsNamespaceView) {
        _projectSettings.value = _projectSettings.value.copy(
            namespaces = _projectSettings.value.namespaces.map {
                if (it.ns == updated.ns) updated else it
            },
        )
    }

    /** Forget every remembered harness and its locally stored relay credential. */
    fun forgetHosts(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.hosts.first().forEach { hostsStore.removeHost(it.id) }
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
