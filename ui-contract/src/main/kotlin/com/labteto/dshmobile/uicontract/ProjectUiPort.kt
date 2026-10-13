package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

data class UiProjectSnapshot(val id: String, val name: String, val instructions: String, val deletable: Boolean)
data class ProjectSceneSnapshot(
    val status: UiSceneStatus,
    val projects: List<UiProjectSnapshot>,
    val activeId: String,
    val recoveryNotice: String?,
)

sealed interface ProjectUiCommand {
    data class Create(val name: String) : ProjectUiCommand
    data class Select(val projectId: String) : ProjectUiCommand
    data class Rename(val projectId: String, val name: String) : ProjectUiCommand
    data class Delete(val projectId: String) : ProjectUiCommand
    data class UpdateInstructions(val projectId: String, val instructions: String) : ProjectUiCommand
    data object BackupAndResetCatalog : ProjectUiCommand
}

sealed interface ProjectUiReceipt {
    /** The owning catalog has actually applied and persisted this command. */
    data class Applied(val createdProjectId: String? = null) : ProjectUiReceipt
    data class Failed(val message: String?) : ProjectUiReceipt
}

interface ProjectUiPort {
    val snapshots: Flow<ProjectSceneSnapshot>
    fun snapshot(): ProjectSceneSnapshot
    suspend fun submit(command: ProjectUiCommand): ProjectUiReceipt
}
