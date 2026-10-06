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

internal data class RemoteSettingsRequestToken(
    val epoch: Long,
    val channel: String,
    val revision: Long,
)

internal class RemoteSettingsRequestGate {
    private var epoch = 0L
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized
    fun capture(channel: String): RemoteSettingsRequestToken {
        val revision = (revisions[channel] ?: 0L) + 1L
        revisions[channel] = revision
        return RemoteSettingsRequestToken(epoch, channel, revision)
    }

    @Synchronized
    fun reset() {
        epoch += 1L
        revisions.clear()
    }

    @Synchronized
    fun isCurrent(token: RemoteSettingsRequestToken): Boolean =
        token.epoch == epoch && revisions[token.channel] == token.revision
}

/** Owns remote Harness settings discovery, mutation and model-provider discovery. */
internal class RemoteSettingsController(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val _projectSettings = MutableStateFlow(RemoteProjectSettingsState())
    val projectSettings: StateFlow<RemoteProjectSettingsState> = _projectSettings.asStateFlow()

    private val _modelServices = MutableStateFlow(ModelServicesState())
    val modelServices: StateFlow<ModelServicesState> = _modelServices.asStateFlow()
    private val requests = RemoteSettingsRequestGate()

    private fun current(token: RemoteSettingsRequestToken, api: com.labteto.dshmobile.core.wire.DshApiClient): Boolean =
        requests.isCurrent(token) &&
            connectionManager.state.value.phase == ConnectionPhase.CONNECTED &&
            connectionManager.connectedApi === api

    fun refresh() {
        scope.launch {
            val api = connectionManager.connectedApi
            if (api == null || connectionManager.state.value.phase != ConnectionPhase.CONNECTED) {
                requests.reset()
                _projectSettings.value = RemoteProjectSettingsState()
                _modelServices.value = ModelServicesState()
                return@launch
            }

            val projectRequest = requests.capture("project")
            _projectSettings.value = _projectSettings.value.copy(loading = true, error = null)
            when (val result = api.settingsDescribe()) {
                is RpcResult.Ok -> {
                    if (!current(projectRequest, api)) return@launch
                    val value: SettingsDescribeValue = result.value
                    _projectSettings.value = RemoteProjectSettingsState(
                        loading = false,
                        available = true,
                        writable = value.writable,
                        namespaces = value.namespaces,
                    )
                }
                is RpcResult.Err -> {
                    if (!current(projectRequest, api)) return@launch
                    _projectSettings.value = RemoteProjectSettingsState(
                        loading = false,
                        error = result.error.message,
                    )
                }
            }

            if (!current(projectRequest, api)) return@launch
            val modelRequest = requests.capture("models")
            _modelServices.value = _modelServices.value.copy(loading = true, error = null)
            when (val result = api.llmListConfigurableProviders()) {
                is RpcResult.Ok -> {
                    if (!current(modelRequest, api)) return@launch
                    _modelServices.value = _modelServices.value.copy(
                        loading = false,
                        providers = result.value,
                        error = null,
                    )
                }
                is RpcResult.Err -> {
                    if (!current(modelRequest, api)) return@launch
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
            val request = requests.capture("project")
            val revision = namespace.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            when (
                val result = api.settingsMutate(
                    ns = namespace.ns,
                    ops = listOf(SettingsPathOpView.Set(path = path, value = value)),
                    expectedRevision = revision,
                )
            ) {
                is RpcResult.Ok -> {
                    if (!current(request, api)) return@launch
                    replaceNamespace(result.value)
                    onDone(null)
                }
                is RpcResult.Err -> {
                    if (!current(request, api)) return@launch
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
            val request = requests.capture("project")
            val revision = namespace.revision.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            when (
                val result = api.settingsMutate(
                    ns = namespace.ns,
                    ops = listOf(SettingsPathOpView.Unset(path = path)),
                    expectedRevision = revision,
                )
            ) {
                is RpcResult.Ok -> {
                    if (!current(request, api)) return@launch
                    replaceNamespace(result.value)
                    onDone(null)
                }
                is RpcResult.Err -> {
                    if (!current(request, api)) return@launch
                    onDone(result.error.message)
                    refresh()
                }
            }
        }
    }

    fun discoverModels(provider: LlmConfigurableProvider) {
        scope.launch {
            val api = connectionManager.connectedApi ?: return@launch
            if (connectionManager.state.value.phase != ConnectionPhase.CONNECTED) return@launch
            val request = requests.capture("models")
            _modelServices.value = _modelServices.value.copy(loading = true, error = null)
            when (
                val result = api.llmDiscoverModels(
                    settingsNs = provider.settingsNs,
                    request = LlmModelDiscoveryRequest(provider = provider.provider),
                )
            ) {
                is RpcResult.Ok -> {
                    if (!current(request, api)) return@launch
                    _modelServices.value = _modelServices.value.copy(
                        loading = false,
                        discovered = _modelServices.value.discovered +
                            (provider.provider to result.value),
                        error = null,
                    )
                }
                is RpcResult.Err -> {
                    if (!current(request, api)) return@launch
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
