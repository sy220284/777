package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.PermissionCatalog
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.core.wire.dto.SkillListRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host/session catalogs with one stale-response boundary for every async publication. */
internal class SessionCatalogRuntime(
    private val apiForHost: (String?) -> DshApiClient?,
    private val activeHostKey: () -> String?,
    private val currentSessionId: () -> String?,
    private val onConnectionError: (String) -> Unit,
    private val logger: (String) -> Unit,
) {
    private val _commands = MutableStateFlow<List<CommandDescriptor>>(emptyList())
    val commands: StateFlow<List<CommandDescriptor>> = _commands.asStateFlow()

    private val _commandsAvailable = MutableStateFlow(true)
    val commandsAvailable: StateFlow<Boolean> = _commandsAvailable.asStateFlow()

    private val _agentPresets = MutableStateFlow<AgentPresetListValue?>(null)
    val agentPresets: StateFlow<AgentPresetListValue?> = _agentPresets.asStateFlow()

    private val _plugins = MutableStateFlow<PluginInventorySnapshot?>(null)
    val plugins: StateFlow<PluginInventorySnapshot?> = _plugins.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    private val _skillsLoading = MutableStateFlow(false)
    val skillsLoading: StateFlow<Boolean> = _skillsLoading.asStateFlow()

    private val _models = MutableStateFlow<ModelCatalog?>(null)
    val modelCatalog: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    val permissionCatalog = MutableStateFlow<PermissionCatalog?>(null)
    private var permissionCatalogEpoch = 0L

    private val requests = SessionAsyncRequestRegistry()

    fun resetSession() {
        requests.reset()
        _skills.value = emptyList()
        _skillsLoading.value = true
        _models.value = null
        _modelsLoading.value = true
        _commands.value = emptyList()
    }

    suspend fun refreshCommands(sessionId: String?) {
        val sid = sessionId ?: return
        val scope = requests.capture("refreshCommands", activeHostKey(), sid)
        val api = apiForHost(scope.hostKey) ?: return
        when (val result = api.commandsList(sid)) {
            is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                _commands.value = result.value
                _commandsAvailable.value = true
            }
            is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                markCommandsUnavailable(result.error.code, result.error.message)
            }
        }
    }

    fun markCommandsUnavailable(code: String, message: String) {
        _commandsAvailable.value = false
        _commands.value = emptyList()
        logger("commands unavailable ($code): $message")
    }

    suspend fun refreshPlugins() {
        val scope = requests.capture("refreshPlugins", activeHostKey(), null)
        val api = apiForHost(scope.hostKey) ?: return
        when (val result = api.pluginInventoryList()) {
            is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) _plugins.value = result.value
            is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                _plugins.value = null
                logger("pluginInventory/list unavailable (${result.error.code}): ${result.error.message}")
            }
        }
    }

    suspend fun refreshAgentPresets() {
        val scope = requests.capture("refreshAgentPresets", activeHostKey(), null)
        val api = apiForHost(scope.hostKey) ?: return
        when (val result = api.agentPresetList()) {
            is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) _agentPresets.value = result.value
            is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                logger("agentPreset.list unavailable (${result.error.code}): ${result.error.message}")
            }
        }
    }

    suspend fun loadSkills(sessionId: String) {
        val scope = requests.capture("loadSkills", activeHostKey(), sessionId)
        val api = apiForHost(scope.hostKey)
        if (api == null) {
            if (scope.isCurrent(activeHostKey, currentSessionId)) _skillsLoading.value = false
            return
        }
        try {
            when (val result = api.skillList(SkillListRequest(sessionId))) {
                is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) _skills.value = result.value.skills
                is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) onConnectionError(result.error.message)
            }
        } finally {
            if (scope.isCurrent(activeHostKey, currentSessionId)) _skillsLoading.value = false
        }
    }

    suspend fun loadModels(sessionId: String) {
        val scope = requests.capture("loadModels", activeHostKey(), sessionId)
        val api = apiForHost(scope.hostKey)
        if (api == null) {
            if (scope.isCurrent(activeHostKey, currentSessionId)) _modelsLoading.value = false
            return
        }
        try {
            when (val result = api.sessionModelCatalog()) {
                is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) _models.value = result.value
                is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) onConnectionError(result.error.message)
            }
        } finally {
            if (scope.isCurrent(activeHostKey, currentSessionId)) _modelsLoading.value = false
        }
    }

    suspend fun refreshPermissionCatalog() {
        val epoch = ++permissionCatalogEpoch
        val key = activeHostKey()
        val api = apiForHost(key) ?: return
        val result = api.permissionCatalog()
        if (epoch == permissionCatalogEpoch && key == activeHostKey()) {
            permissionCatalog.value = (result as? RpcResult.Ok)?.value
        }
    }
}
