package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.project.LocalProjectFeatureRuntime
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow ProjectFeature presentation API: no direct store or mutable domain state is exposed. */
@Singleton
class LocalProjectUiFacade @Inject internal constructor(
    private val runtime: LocalProjectFeatureRuntime,
    private val sessions: LocalSessionStorageRuntime,
    private val state: LocalRuntimeStateStore,
    private val memory: MemoryStore,
) : com.labteto.dshmobile.uicontract.ProjectUiPort {
    override val snapshots: kotlinx.coroutines.flow.Flow<com.labteto.dshmobile.uicontract.ProjectSceneSnapshot> =
        kotlinx.coroutines.flow.combine(runtime.catalog, runtime.recoveryNotice) { catalog, notice ->
            projectSnapshot(catalog, notice)
        }

    override fun snapshot() = projectSnapshot(runtime.catalog.value, runtime.recoveryNotice.value)

    private fun projectSnapshot(
        catalog: com.labteto.dshmobile.local.project.LocalProjectCatalogState,
        notice: String?,
    ) = com.labteto.dshmobile.uicontract.ProjectSceneSnapshot(
        status = if (notice != null) com.labteto.dshmobile.uicontract.UiSceneStatus.ERROR
            else com.labteto.dshmobile.uicontract.UiSceneStatus.CONTENT,
        projects = catalog.projects.map {
            com.labteto.dshmobile.uicontract.UiProjectSnapshot(it.id, it.name, it.instructions,
                it.id != com.labteto.dshmobile.local.project.DEFAULT_PROJECT_ID && notice == null)
        },
        activeId = catalog.activeId,
        recoveryNotice = notice,
    )

    override suspend fun submit(
        command: com.labteto.dshmobile.uicontract.ProjectUiCommand,
    ): com.labteto.dshmobile.uicontract.ProjectUiReceipt = try {
        val createdId = when (command) {
            is com.labteto.dshmobile.uicontract.ProjectUiCommand.Create -> create(command.name)
            is com.labteto.dshmobile.uicontract.ProjectUiCommand.Select -> { select(command.projectId); null }
            is com.labteto.dshmobile.uicontract.ProjectUiCommand.Rename -> { rename(command.projectId, command.name); null }
            is com.labteto.dshmobile.uicontract.ProjectUiCommand.Delete -> { delete(command.projectId); null }
            is com.labteto.dshmobile.uicontract.ProjectUiCommand.UpdateInstructions -> {
                updateInstructions(command.projectId, command.instructions); null
            }
            com.labteto.dshmobile.uicontract.ProjectUiCommand.BackupAndResetCatalog -> { backupAndResetCatalog(); null }
        }
        com.labteto.dshmobile.uicontract.ProjectUiReceipt.Applied(createdId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        com.labteto.dshmobile.uicontract.ProjectUiReceipt.Failed(failure.message)
    }

    internal val catalog get() = runtime.catalog
    internal val recoveryNotice get() = runtime.recoveryNotice
    fun backupAndResetCatalog() = runtime.backupAndResetCatalog()
    fun create(name: String): String = runtime.create(name)
    fun select(id: String) = runtime.select(id)
    /** No deletion while a live/durable session or any active scoped memory owns this project ID. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runtime.delete(id) { projectId ->
            check(state.state.value.projectId != projectId &&
                sessions.coordinator.summaries().none { it.projectId == projectId }) {
                "该项目仍被会话引用，请先处理关联会话后再删除"
            }
            check(memory.listActive(setOf(MemoryScope.PROJECT), projectId, null, limit = 1).isEmpty()) {
                "该项目仍有长期记忆，请先移除关联记忆后再删除"
            }
        }
    }
    fun rename(id: String, name: String) = runtime.rename(id, name)
    fun updateInstructions(id: String, instructions: String) = runtime.updateInstructions(id, instructions)
}
