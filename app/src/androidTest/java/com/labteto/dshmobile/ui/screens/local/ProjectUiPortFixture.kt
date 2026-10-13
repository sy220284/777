package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import kotlinx.coroutines.flow.MutableStateFlow

// Explicit renderer fixtures. Production uses only the neutral ProjectUiPort.
internal data class FixtureProjectActions(
    val port: com.labteto.dshmobile.uicontract.ProjectUiPort,
    val createProjectWorkSession: () -> Boolean,
)

internal fun projectUiFixture(
    catalog: MutableStateFlow<LocalProjectCatalogState>,
    recoveryNotice: MutableStateFlow<String?>,
    backupAndReset: () -> Unit,
    createProjectWorkSession: () -> Boolean,
    create: (String) -> String,
    select: (String) -> Unit,
    rename: (String, String) -> Unit,
    delete: suspend (String) -> Unit = {},
    updateInstructions: (String, String) -> Unit,
): FixtureProjectActions {
    fun fixtureSnapshot(catalogValue: LocalProjectCatalogState, notice: String?) =
        com.labteto.dshmobile.uicontract.ProjectSceneSnapshot(
            if (notice == null) com.labteto.dshmobile.uicontract.UiSceneStatus.CONTENT
                else com.labteto.dshmobile.uicontract.UiSceneStatus.ERROR,
            catalogValue.projects.map { com.labteto.dshmobile.uicontract.UiProjectSnapshot(
                it.id, it.name, it.instructions, it.id != com.labteto.dshmobile.local.project.DEFAULT_PROJECT_ID,
            ) }, catalogValue.activeId, notice,
        )
    val port = object : com.labteto.dshmobile.uicontract.ProjectUiPort {
        override val snapshots = kotlinx.coroutines.flow.combine(catalog, recoveryNotice, ::fixtureSnapshot)
        override fun snapshot() = fixtureSnapshot(catalog.value, recoveryNotice.value)
        override suspend fun submit(command: com.labteto.dshmobile.uicontract.ProjectUiCommand):
            com.labteto.dshmobile.uicontract.ProjectUiReceipt = try {
            val id = when (command) {
                is com.labteto.dshmobile.uicontract.ProjectUiCommand.Create -> create(command.name)
                is com.labteto.dshmobile.uicontract.ProjectUiCommand.Select -> { select(command.projectId); null }
                is com.labteto.dshmobile.uicontract.ProjectUiCommand.Rename -> { rename(command.projectId, command.name); null }
                is com.labteto.dshmobile.uicontract.ProjectUiCommand.Delete -> { delete(command.projectId); null }
                is com.labteto.dshmobile.uicontract.ProjectUiCommand.UpdateInstructions -> {
                    updateInstructions(command.projectId, command.instructions); null
                }
                com.labteto.dshmobile.uicontract.ProjectUiCommand.BackupAndResetCatalog -> { backupAndReset(); null }
            }
            com.labteto.dshmobile.uicontract.ProjectUiReceipt.Applied(id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.labteto.dshmobile.uicontract.ProjectUiReceipt.Failed(failure.message)
        }
    }
    return FixtureProjectActions(port, createProjectWorkSession)
}

@androidx.compose.runtime.Composable
internal fun FixtureProjectScreen(actions: FixtureProjectActions, onBack: () -> Unit) {
    LocalProjectScreen(port = actions.port, onCreateWorkSession = actions.createProjectWorkSession, onBack = onBack)
}
