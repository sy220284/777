package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.LlmDiscoveredModel
import com.labteto.dshmobile.core.wire.dto.LlmModelDiscoveryRequest
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/** Owns remote Harness settings discovery, mutation and model-provider discovery. */
internal class RemoteSettingsController(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val _projectSettings = MutableStateFlow(RemoteProjectSettingsState())
    val projectSettings: StateFlow<RemoteProjectSettingsState> = _projectSettings.asStateFlow()

    private val _modelServices = MutableStateFlow(ModelServicesState())
    val modelServices: StateFlow<ModelServicesState> = _modelServices.asStateFlow()

    fun refresh() {
        scope.launch {
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

    fun set(
        namespace: SettingsNamespaceView,
        path: List<String>,
        value: JsonElement,
        onDone: (String?) -> Unit = {},
    ) {
        scope.launch {
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
                    refresh()
                }
            }
        }
    }

    fun unset(
        namespace: SettingsNamespaceView,
        path: List<String>,
        onDone: (String?) -> Unit = {},
    ) {
        scope.launch {
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
                    refresh()
                }
            }
        }
    }

    fun discoverModels(provider: LlmConfigurableProvider) {
        scope.launch {
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
                        discovered = _modelServices.value.discovered +
                            (provider.provider to result.value),
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

    private fun replaceNamespace(updated: SettingsNamespaceView) {
        _projectSettings.value = _projectSettings.value.copy(
            namespaces = _projectSettings.value.namespaces.map {
                if (it.ns == updated.ns) updated else it
            },
        )
    }
}
