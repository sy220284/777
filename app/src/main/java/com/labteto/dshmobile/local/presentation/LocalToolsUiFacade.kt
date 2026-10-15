package com.labteto.dshmobile.local.presentation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.tools.LocalComposerInvocationOption
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation
import com.labteto.dshmobile.local.tools.LocalPinnedInvocationStore

import com.labteto.dshmobile.local.tools.LocalToolsRuntime
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import com.labteto.dshmobile.observability.AppLog

data class LocalCapabilityUiEntry(val name: String, val family: String, val access: String,
    val approval: String, val requirements: List<String>)

data class LocalSkillUiEntry(val name: String, val description: String, val modelInvocable: Boolean, val displayName: String = name)
data class LocalPresetSkillUiEntry(val id: String, val title: String, val description: String, val installed: Boolean)
data class LocalSkillEditorUiEntry(val id: String, val document: String)

/** Tools page boundary; plugin/runtime implementations stay outside UI. */
@Singleton
class LocalToolsUiFacade @Inject internal constructor(
    private val tools: LocalToolsRuntime,
    private val pinnedStore: LocalPinnedInvocationStore,
    @ApplicationContext private val appContext: Context,
) {
    internal val pinnedInvocations get() = pinnedStore.selected

    internal fun togglePinnedInvocation(value: LocalPinnedInvocation) = pinnedStore.toggle(value)

    internal suspend fun composerInvocationOptions(): List<LocalComposerInvocationOption> {
        val available = mutableListOf<LocalComposerInvocationOption>()
        // Failure to inspect one source must not hide every other connected tool or skill.
        suspend fun <T> attempt(source: String, fallback: T, action: suspend () -> T): T =
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AppLog.failure("LocalToolsUiFacade", "composer_inventory_$source", error)
                fallback
            }
        if (attempt("github", false, ::githubConfigured)) {
            available += LocalComposerInvocationOption(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "github"),
                "GitHub", appContext.getString(R.string.composer_invocation_github_ready),
            )
        }
        attempt("mcp", emptyList(), ::servers).filter { it.tools.isNotEmpty() }.forEach { server ->
            available += LocalComposerInvocationOption(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, server.id),
                server.id, appContext.getString(R.string.composer_invocation_mcp_tools, server.tools.size),
            )
        }
        attempt("skills", emptyList(), ::installedSkills).filter { it.modelInvocable }.forEach { skill ->
            available += LocalComposerInvocationOption(
                LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, skill.name),
                skill.displayName, skill.description,
            )
        }
        val availableIds = available.map { it.invocation }.toSet()
        return available + pinnedStore.selected.value.filterNot { it in availableIds }.map { missing ->
            LocalComposerInvocationOption(
                missing, missing.id, appContext.getString(R.string.composer_invocation_unavailable), false,
            )
        }
    }

    internal suspend fun servers() = tools.servers()
    internal suspend fun githubConfigured() = tools.githubConfigured()
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
    internal suspend fun configureGitHub(token: String) = tools.configureGitHub(token)
    internal suspend fun clearGitHub() = tools.clearGitHub()
    internal fun capabilityCatalogue() = tools.capabilityCatalogue().map {
        LocalCapabilityUiEntry(it.name, it.family, it.access, it.approval, it.requirements)
    }
    internal fun installedPluginIds() = tools.installedPluginIds()
    /** Registry presence is a configuration hint, not an execution grant. */
    internal suspend fun handoffExternalReadiness(): Pair<Boolean, Boolean> {
        val hasMcpTools = tools.servers().any { it.tools.isNotEmpty() }
        val hasThirdPartyPlugin = tools.installedPluginIds().any { it != "local-builtin" }
        return hasMcpTools to hasThirdPartyPlugin
    }
    internal suspend fun installedSkills() = tools.installedSkills().map {
        LocalSkillUiEntry(it.name, it.description, it.modelInvocable, it.displayName)
    }
    internal suspend fun presetSkills() = tools.presetSkills().map {
        LocalPresetSkillUiEntry(it.id, it.title, it.description, it.installed)
    }
    internal suspend fun installPreset(id: String) = tools.installPreset(id)
    internal suspend fun createSkill(id: String, displayName: String, description: String, instructions: String) =
        tools.createSkill(id, displayName, description, instructions)
    internal suspend fun importSkill(filename: String, bytes: ByteArray) = tools.importSkill(filename, bytes)
    internal suspend fun removeSkill(id: String) = tools.removeSkill(id)
    internal suspend fun readSkillDocument(id: String) = tools.readSkillDocument(id)
    internal suspend fun updateSkillDocument(id: String, document: String) = tools.updateSkillDocument(id, document)
    internal suspend fun setSkillModelInvocable(id: String, enabled: Boolean) = tools.setSkillModelInvocable(id, enabled)
    internal suspend fun connectHttp(serverId: String, endpoint: String) = tools.connectHttp(serverId, endpoint)
    internal suspend fun connectStdio(serverId: String, command: List<String>, workingDirectory: String?) =
        tools.connectStdio(serverId, command, workingDirectory)
    internal suspend fun disconnect(serverId: String) = tools.disconnect(serverId)
}
