package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

/** A connected dependency is a live registered service, not merely a saved connection record. */
internal fun isRegisteredMcpSkillRequirement(
    name: String,
    servers: List<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot>,
): Boolean = servers.any { server ->
    server.registration == com.labteto.dshmobile.uicontract.UiToolRegistration.REGISTERED &&
        server.connection == com.labteto.dshmobile.uicontract.UiToolConnection.HEALTH_UNCHECKED &&
        server.tools.isNotEmpty() &&
        (server.id == name || name in server.tools)
}

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject internal constructor(
    private val tools: LocalToolsRuntime,
    private val skillManager: com.labteto.dshmobile.local.tools.LocalSkillManager,
    private val agentBackends: com.labteto.dshmobile.local.agent.LocalAgentBackendRuntime,
    private val gitHubSkillImporter: com.labteto.dshmobile.local.tools.LocalGitHubSkillImporter,
    private val codexOfficialPlugins: com.labteto.dshmobile.local.tools.CodexOfficialPluginCatalog,
    private val gitHubBrowser: com.labteto.dshmobile.local.tools.LocalGitHubCredentialManager,
    private val mcpCredentials: com.labteto.dshmobile.local.tools.LocalMcpCredentialStore,
    private val mcpOAuth: com.labteto.dshmobile.local.tools.LocalMcpOAuthCoordinator,
) : com.labteto.dshmobile.uicontract.ToolsManagementUiPort {
    override suspend fun agentBackendStatus(): String = agentBackends.status()
    override suspend fun probeAgentBackend(): String = agentBackends.probe()
    override suspend fun configureAgentBackend(host: String, port: Int, pin: String, token: String, consent: Boolean) =
        agentBackends.configure(host, port, pin, token, consent)
    override suspend fun revokeAgentBackend() = agentBackends.revoke()

    override fun serverChanges(): kotlinx.coroutines.flow.Flow<Unit> = tools.serverChanges()

    override suspend fun lifecycleHistory(
        incarnation: String?, afterSequence: Long,
    ): com.labteto.dshmobile.uicontract.UiMcpLifecycleHistory {
        val page = tools.lifecycleHistory(incarnation, afterSequence)
        return com.labteto.dshmobile.uicontract.UiMcpLifecycleHistory(
            page.incarnation, page.latestSequence,
            page.events.map { change ->
                com.labteto.dshmobile.uicontract.UiMcpLifecycleEvent(
                    change.sequence, change.serverId,
                    when (change.snapshot?.lifecycle) {
                        com.labteto.dshmobile.interop.mcp.McpServerLifecycle.CONNECTING ->
                            com.labteto.dshmobile.uicontract.UiToolConnection.CONNECTING
                        com.labteto.dshmobile.interop.mcp.McpServerLifecycle.RECONNECTING ->
                            com.labteto.dshmobile.uicontract.UiToolConnection.RECONNECTING
                        com.labteto.dshmobile.interop.mcp.McpServerLifecycle.REGISTERED ->
                            com.labteto.dshmobile.uicontract.UiToolConnection.HEALTH_UNCHECKED
                        com.labteto.dshmobile.interop.mcp.McpServerLifecycle.DISCONNECTING ->
                            com.labteto.dshmobile.uicontract.UiToolConnection.DISCONNECTING
                        com.labteto.dshmobile.interop.mcp.McpServerLifecycle.FAILED ->
                            com.labteto.dshmobile.uicontract.UiToolConnection.FAILED
                        null -> null
                    },
                )
            },
            page.requiresSnapshot,
        )
    }

    override suspend fun servers(): List<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot> {
        val selectedIds = tools.selectedConnectorIds()
        return tools.servers().map {
        val lifecycle = it.lifecycle
        val registration = when (lifecycle) {
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.REGISTERED ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.REGISTERED
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.DISCONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.REMOVING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.FAILED ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.FAILED
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.CONNECTING,
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.RECONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolRegistration.PENDING
        }
        val connection = when (lifecycle) {
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.REGISTERED ->
                com.labteto.dshmobile.uicontract.UiToolConnection.HEALTH_UNCHECKED
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.CONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.CONNECTING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.RECONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.RECONNECTING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.DISCONNECTING ->
                com.labteto.dshmobile.uicontract.UiToolConnection.DISCONNECTING
            com.labteto.dshmobile.interop.mcp.McpServerLifecycle.FAILED ->
                com.labteto.dshmobile.uicontract.UiToolConnection.FAILED
        }
        com.labteto.dshmobile.uicontract.UiMcpServerSnapshot(it.id, it.transport, it.target, it.tools,
            registration = registration, connection = connection,
            selection = com.labteto.dshmobile.uicontract.uiMcpToolSelection(it.id, selectedIds))
        }
    }
    override suspend fun checkServerHealth(serverId: String): com.labteto.dshmobile.uicontract.UiMcpHealth =
        when (tools.checkMcpHealth(serverId)) {
            com.labteto.dshmobile.interop.mcp.McpServerHealth.RESPONDED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.RESPONDED
            com.labteto.dshmobile.interop.mcp.McpServerHealth.BUSY ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.BUSY
            com.labteto.dshmobile.interop.mcp.McpServerHealth.NOT_CONNECTED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.NOT_CONNECTED
            com.labteto.dshmobile.interop.mcp.McpServerHealth.UNCONFIRMED ->
                com.labteto.dshmobile.uicontract.UiMcpHealth.UNCONFIRMED
        }
    suspend fun remoteGitBranchesForUi(repository: String): List<String> =
        withContext(Dispatchers.IO) { tools.remoteGitBranches(repository) }

    suspend fun compareLocalGitForUi(repository: String, includeUntracked: Boolean = false): LocalRemoteGitDiffUi = withContext(Dispatchers.IO) {
        val receipt = tools.compareLocalGit(repository, includeUntracked)
        LocalRemoteGitDiffUi(
            trackingRef = (receipt.getValue("tracking_ref") as kotlinx.serialization.json.JsonPrimitive).content,
            statistics = (receipt.getValue("stat") as kotlinx.serialization.json.JsonPrimitive).content,
            patch = (receipt.getValue("patch") as kotlinx.serialization.json.JsonPrimitive).content,
            truncated = receipt.getValue("truncated").toString() == "true",
            evidenceRevision = (receipt.getValue("head_commit") as kotlinx.serialization.json.JsonPrimitive).content,
            untrackedFileCount = receipt.getValue("untracked_file_count").toString().toInt(),
            includesUntrackedFiles = receipt.getValue("includes_untracked_files").toString() == "true",
        )
    }

    suspend fun compareRemoteGitForUi(
        repository: String, remote: String, branch: String,
    ): LocalRemoteGitDiffUi = withContext(Dispatchers.IO) {
        val receipt = tools.compareRemoteGit(repository, remote, branch)
        LocalRemoteGitDiffUi(
            trackingRef = (receipt.getValue("tracking_ref") as kotlinx.serialization.json.JsonPrimitive).content,
            statistics = (receipt.getValue("stat") as kotlinx.serialization.json.JsonPrimitive).content,
            patch = (receipt.getValue("patch") as kotlinx.serialization.json.JsonPrimitive).content,
            truncated = receipt.getValue("truncated").toString() == "true",
            evidenceRevision = listOf("head_commit", "tracking_commit", "merge_base")
                .joinToString(":") { key ->
                    (receipt.getValue(key) as kotlinx.serialization.json.JsonPrimitive).content
                },
        )
    }

    /** Commit review is pinned to its immutable object rather than a mutable branch name. */
    suspend fun compareCommitGitForUi(repository: String, commit: String): LocalRemoteGitDiffUi =
        withContext(Dispatchers.IO) {
            val receipt = tools.compareCommitGit(repository, commit)
            LocalRemoteGitDiffUi(
                trackingRef = (receipt.getValue("tracking_ref") as kotlinx.serialization.json.JsonPrimitive).content,
                statistics = (receipt.getValue("stat") as kotlinx.serialization.json.JsonPrimitive).content,
                patch = (receipt.getValue("patch") as kotlinx.serialization.json.JsonPrimitive).content,
                truncated = receipt.getValue("truncated").toString() == "true",
                evidenceRevision = listOf("commit", "first_parent")
                    .joinToString(":") { key ->
                        (receipt.getValue(key) as kotlinx.serialization.json.JsonPrimitive).content
                    },
            )
        }

    override suspend fun githubConfigured() = tools.githubConfigured()
    override suspend fun githubRepositories() = gitHubBrowser.repositories()
    override suspend fun githubEntries(repoFullName: String, directory: String) =
        gitHubBrowser.entries(repoFullName, directory)

    /** Work handoff UI projection; capability hints do not grant execution permissions. */
    internal fun workHandoffCapabilityReadiness(
        task: String,
        githubConfigured: Boolean?,
        networkSearchEnabled: Boolean,
        showModelStatus: Boolean = false,
        modelConfigured: Boolean? = null,
        mcpToolsAvailable: Boolean? = null,
        pluginsInstalled: Boolean? = null,
        accessibilityActive: Boolean? = null,
        notificationAccessActive: Boolean? = null,
    ) = LocalTaskCapabilityReadinessProjector.project(
        task, githubConfigured, networkSearchEnabled,
        showModelStatus, modelConfigured, mcpToolsAvailable, pluginsInstalled,
        accessibilityActive, notificationAccessActive,
    )
    override suspend fun configureGitHub(token: String) { tools.configureGitHub(token) }
    override suspend fun clearGitHub() = tools.clearGitHub()
    override fun capabilityCatalogue() = tools.capabilityCatalogue().map {
        com.labteto.dshmobile.uicontract.UiToolCapabilitySnapshot(it.name, it.family, it.access, it.approval, it.requirements, it.parameterSchema)
    }
    override fun validateCapabilityDraft(
        name: String, expectedSchema: kotlinx.serialization.json.JsonObject, argumentsJson: String,
    ): com.labteto.dshmobile.uicontract.UiToolDraftValidation =
        tools.validateCapabilityDraft(name, expectedSchema, argumentsJson)

    override fun pluginLifecycleSnapshots(): List<com.labteto.dshmobile.uicontract.UiPluginLifecycle> =
        tools.pluginLifecycleSnapshots().map { state ->
            com.labteto.dshmobile.uicontract.UiPluginLifecycle(
                state.id, com.labteto.dshmobile.uicontract.UiPluginLifecycleState.valueOf(state.state.name),
            )
        }
    override suspend fun recoverFailedPlugin(id: String): List<String> =
        tools.recoverFailedPlugin(id)
    override fun activeToolHookIds() = tools.activeToolHookIds()
    override fun installedPluginIds() = tools.installedPluginIds()
    /** Registry presence is a configuration hint, not an execution grant. */
    internal suspend fun handoffExternalReadiness(): Pair<Boolean, Boolean> {
        val hasMcpTools = tools.servers().any { it.tools.isNotEmpty() }
        val hasThirdPartyPlugin = tools.installedPluginIds().any { it != "android-local-builtins" }
        return hasMcpTools to hasThirdPartyPlugin
    }
    /** Refresh the existing skill inventory when the real on-device skill files change. */
    override fun skillChanges(): kotlinx.coroutines.flow.Flow<Unit> = skillManager.changes()
    override suspend fun installedSkills() = withContext(Dispatchers.IO) {
        val connected = servers()
        val registeredTools = tools.capabilityCatalogue().map { it.name }.toSet()
        tools.installedSkills().map { item ->
            val result = runCatching { gitHubSkillImporter.installedSourceIdentity(item.name) }
            val sourceIdentity = result.getOrNull()?.let { (source, commit) ->
                com.labteto.dshmobile.uicontract.UiSkillSourceIdentity(
                    source.owner, source.repository, commit, source.directory)
            }
            result.exceptionOrNull()?.let { failure ->
                com.labteto.dshmobile.observability.AppLog.warn(
                    "SkillOrigin", "技能来源证据无效：${item.name}", failure,
                )
            }
            val dependencyResult = runCatching { skillManager.dependencies(item.name) }
            dependencyResult.exceptionOrNull()?.let { failure ->
                com.labteto.dshmobile.observability.AppLog.warn(
                    "SkillDependencies", "技能依赖元数据无法校验：${item.name}", failure,
                )
            }
            com.labteto.dshmobile.uicontract.UiSkillSnapshot(
                item.name, item.description, item.modelInvocable, item.displayName,
                sourceUrl = sourceIdentity?.let {
                    "https://github.com/${it.owner}/${it.repository}/tree/${it.commit}/${it.directory}"
                },
                sourceWarning = if (result.isFailure) "技能来源记录校验失败，请检查或重新安装此技能" else null,
                sourceIdentity = sourceIdentity,
                rollbackAvailable = gitHubSkillImporter.canRollback(item.name),
                requirements = dependencyResult.getOrNull()?.map { requirement ->
                    val registered = when (requirement.type.lowercase()) {
                        "mcp" -> isRegisteredMcpSkillRequirement(requirement.value, connected)
                        "tool" -> requirement.value in registeredTools ||
                            connected.any { requirement.value in it.tools }
                        else -> false
                    }
                    com.labteto.dshmobile.uicontract.UiSkillRequirement(
                        requirement.type, requirement.value, requirement.description, registered,
                        requirement.transport, requirement.url, requirement.command,
                    )
                } ?: listOf(
                    com.labteto.dshmobile.uicontract.UiSkillRequirement(
                        "metadata", item.name,
                        "技能依赖文件读取失败；请检查 agents/openai.yaml 的路径和内容", false,
                    )
                ),
            )
        }
    }
    override suspend fun presetSkills() = tools.presetSkills().map {
        com.labteto.dshmobile.uicontract.UiPresetSkillSnapshot(it.id, it.title, it.description, it.installed)
    }
    override suspend fun curatedGitHubSkills() = gitHubSkillImporter.curatedSkills()
    override suspend fun codexOfficialPlugins(forceRefetch: Boolean) =
        codexOfficialPlugins.list(forceRefetch).map { item ->
        com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot(
            item.id, item.name, item.category, item.sourcePath,
            item.installPolicy, item.authPolicy, item.codexOnly,
            item.sourceKind, item.sourceUrl,
        )
    }
    /** Publisher-provided plugin logo, loaded through the catalog owner. */
    override suspend fun codexPluginLogo(id: String): ByteArray? = codexOfficialPlugins.officialLogo(id)

    override suspend fun codexPluginDetail(id: String):
        com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot {
        val info = codexOfficialPlugins.detail(id)
        val remote = if (info.hasMcp) codexOfficialPlugins.mcpEndpoint(id) else null
        return com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot(
            id, info.displayName, info.description, info.developerName, info.version,
            if (info.hasSkills) codexOfficialPlugins.pluginSkills(id) else emptyList(),
            info.hasMcp, info.hasApp, info.hasHooks,
            remote?.serverId, remote?.url,
            if (info.hasSkills) codexOfficialPlugins.pluginSkillLocations(id) else emptyMap(),
            info.samplePrompts,
        )
    }
    override suspend fun installCodexPluginSkill(pluginId: String, skillId: String): String =
        codexOfficialPlugins.installPluginSkill(pluginId, skillId)
    override suspend fun connectCodexPlugin(id: String): String {
        val entry = codexOfficialPlugins.list().firstOrNull { it.id == id }
            ?: error("Codex 官方目录中没有该插件")
        require(entry.offeredOnAndroid) { "该插件不支持在 777 中连接" }
        val endpoint = codexOfficialPlugins.mcpEndpoint(id)
            ?: error("该插件没有可在 Android 使用的 HTTPS MCP 服务，请查看官方能力说明")
        loginMcp(endpoint.serverId, endpoint.url, endpoint.clientId)
        return "已完成 MCP 授权并连接：${endpoint.serverId}"
    }
    /** The feature owns source validation; UI passes only the name from a visible catalog row. */
    override suspend fun installCuratedSkill(id: String) = gitHubSkillImporter.install(
        com.labteto.dshmobile.local.tools.curatedGitHubSkillUrl(id),
    )
    override suspend fun installGitHubSkill(link: String) = gitHubSkillImporter.install(link)
    override suspend fun installPreset(id: String) = tools.installPreset(id)
    override suspend fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        tools.createSkill(id, displayName, description, instructions)
    override suspend fun importSkill(filename: String, bytes: ByteArray) = tools.importSkill(filename, bytes)
    override suspend fun importSkillStream(filename: String, input: java.io.InputStream): String =
        withContext(Dispatchers.IO) {
            val owner = kotlinx.coroutines.currentCoroutineContext()
            val stream = object : java.io.FilterInputStream(input) {
                override fun read(): Int {
                    owner.ensureActive()
                    return super.read().also { owner.ensureActive() }
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    owner.ensureActive()
                    return `in`.read(bytes, offset, length).also { owner.ensureActive() }
                }
            }
            skillManager.importSkill(filename, stream) { owner.ensureActive() }
        }
    suspend fun exportSkillPackage(id: String): ByteArray = tools.exportSkillPackage(id)
    override suspend fun exportSkillPackageToFile(id: String, destination: java.io.File) =
        withContext(Dispatchers.IO) { skillManager.exportSkillPackageToFile(id, destination) }
    override suspend fun checkSkillUpdate(id: String): String? = gitHubSkillImporter.availableUpdate(id)
    override suspend fun upgradeSkill(id: String, revision: String): String =
        gitHubSkillImporter.updateInstalled(id, revision)
    override suspend fun rollbackSkill(id: String): String = gitHubSkillImporter.rollback(id)
    override suspend fun removeSkill(id: String) = tools.removeSkill(id)
    override suspend fun readSkillDocument(id: String) = tools.readSkillDocument(id)
    override suspend fun updateSkillDocument(id: String, document: String) = tools.updateSkillDocument(id, document)
    override suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = tools.setSkillModelInvocable(id, enabled)
    override suspend fun connectHttp(serverId: String, endpoint: String): String =
        mcpConnectionGate.withLock { tools.connectHttp(serverId, endpoint) }
    /** One Feature-owned connection switch for browser OAuth, refresh and manual Bearer.
     * The existing service remains registered until new credentials are ready. */
    private val mcpConnectionGate = kotlinx.coroutines.sync.Mutex()

    private suspend fun switchMcpHttpConnection(
        serverId: String,
        endpoint: String,
        stage: suspend () -> com.labteto.dshmobile.local.tools.LocalMcpCredentialStore.CredentialRotationReceipt,
    ): String = mcpConnectionGate.withLock {
        val existing = tools.servers().firstOrNull { it.id == serverId }
        if (existing != null) {
            val uri = java.net.URI(endpoint)
            val advertised = java.net.URI(
                uri.scheme, null, uri.host, uri.port,
                uri.path?.ifBlank { "/" } ?: "/", null, null,
            ).toString()
            require(existing.transport == "http" && existing.target == advertised) {
                "服务标识已被不同连接占用；请先在连接器中管理原服务"
            }
        }
        // No live service mutation before the remote grant is available.
        val rotation = stage()
        try {
            if (existing != null) tools.disconnect(serverId)
            tools.connectHttp(serverId, endpoint)
        } catch (failure: Throwable) {
            val restorationFailure = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    if (tools.servers().any { it.id == serverId }) tools.disconnect(serverId)
                    mcpCredentials.rollbackCredentialRotation(serverId, rotation)
                    if (existing != null) tools.connectHttp(serverId, endpoint)
                    null
                } catch (restoration: Throwable) {
                    restoration
                }
            }
            if (restorationFailure != null) {
                val error = IllegalStateException(
                    "MCP 连接切换失败，且恢复原服务失败：${restorationFailure.message}", failure)
                error.addSuppressed(restorationFailure)
                throw error
            }
            throw failure
        }
    }

    override suspend fun connectHttpWithCredential(serverId: String, endpoint: String, bearer: String): String =
        if (bearer.isEmpty()) mcpConnectionGate.withLock { tools.connectHttp(serverId, endpoint) }
        else switchMcpHttpConnection(serverId, endpoint) {
            mcpCredentials.rotateBearer(serverId, endpoint, bearer)
        }

    private suspend fun disconnectMcpIfConnected(serverId: String) {
        if (tools.servers().any { it.id == serverId }) tools.disconnect(serverId)
    }

    override suspend fun loginMcp(serverId: String, endpoint: String, clientId: String?): String =
        switchMcpHttpConnection(serverId, endpoint) {
            val grant = mcpOAuth.authorize(serverId, endpoint, clientId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            mcpCredentials.rotateOAuth(serverId, endpoint, grant)
        }

    fun cancelMcpLogin(serverId: String) = mcpOAuth.cancel(serverId)

    override suspend fun refreshMcpLogin(serverId: String, endpoint: String): String =
        switchMcpHttpConnection(serverId, endpoint) {
            val refreshed = mcpCredentials.stageOAuthRefresh(serverId, endpoint)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            mcpCredentials.rotateOAuth(serverId, endpoint, refreshed)
        }

    override suspend fun revokeMcpLogin(serverId: String, endpoint: String): Boolean {
        mcpOAuth.cancel(serverId)
        return mcpConnectionGate.withLock {
            disconnectMcpIfConnected(serverId)
            mcpCredentials.revoke(serverId, endpoint)
        }
    }
    override suspend fun clearMcpCredential(serverId: String) {
        mcpOAuth.cancel(serverId)
        mcpConnectionGate.withLock {
            disconnectMcpIfConnected(serverId)
            mcpCredentials.clear(serverId)
        }
    }
    override suspend fun connectStdio(
        serverId: String, command: List<String>, workingDirectory: String?,
    ): String = mcpConnectionGate.withLock {
        tools.connectStdio(serverId, command, workingDirectory)
    }
    override suspend fun disconnect(serverId: String): String =
        mcpConnectionGate.withLock { tools.disconnect(serverId) }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalToolsUiBindings {
    @dagger.Binds abstract fun bindToolsUiPort(facade: LocalToolsUiFacade): com.labteto.dshmobile.uicontract.ToolsUiPort
    @dagger.Binds abstract fun bindToolsManagementUiPort(facade: LocalToolsUiFacade):
        com.labteto.dshmobile.uicontract.ToolsManagementUiPort

}

/** Read-only Git comparison receipt; process/runtime objects stay behind the UI facade. */
data class LocalRemoteGitDiffUi(
    val trackingRef: String,
    val statistics: String,
    val patch: String,
    val truncated: Boolean,
    /** Version of the Git objects used to produce this patch. */
    val evidenceRevision: String = "",
    val untrackedFileCount: Int = 0,
    val includesUntrackedFiles: Boolean = false,
)
