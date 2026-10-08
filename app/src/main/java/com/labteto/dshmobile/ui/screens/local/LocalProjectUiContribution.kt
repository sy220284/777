package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import kotlinx.coroutines.flow.StateFlow

internal data class LocalProjectUiActions(
    val catalog: StateFlow<LocalProjectCatalogState>,
    val recoveryNotice: StateFlow<String?>,
    val backupAndReset: () -> Unit,
    val createProjectWorkSession: () -> Boolean,
    val onProjectSessionAccepted: () -> Unit,
    val create: (String) -> String,
    val select: (String) -> Unit,
    val updateInstructions: (String, String) -> Unit,
)

internal fun localProjectFeatureUiContribution(
    actions: LocalProjectUiActions,
    onPopFeature: () -> Unit,
    onOpenFromDrawer: (LocalFeaturePage) -> Unit,
    onCloseDrawer: () -> Unit,
): LocalFeatureUiContribution = LocalFeatureUiContribution(
    moduleId = LocalFeatureModuleId.PROJECT,
    drawerActions = mapOf(
        LocalFeatureDrawerEntry.PROJECT to {
            onOpenFromDrawer(LocalFeaturePage.PROJECT)
            onCloseDrawer()
        },
    ),
    backAction = { _, edge -> localFeatureProductBackAction(edge) },
    restorePage = ::localFeatureRestoreOwnedPage,
) { page ->
    check(page == LocalFeaturePage.PROJECT) { "Project received a foreign route: $page" }
    LocalProjectScreen(actions, onPopFeature)
}

@Composable
private fun LocalProjectScreen(
    actions: LocalProjectUiActions,
    onBack: () -> Unit,
) {
    val state by actions.catalog.collectAsStateWithLifecycle()
    val recovery by actions.recoveryNotice.collectAsStateWithLifecycle()
    val active = state.projects.firstOrNull { it.id == state.activeId }
    var newName by remember { mutableStateOf("") }
    var instructionDraft by remember(active?.id, active?.instructions) {
        mutableStateOf(active?.instructions.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text(stringResource(R.string.local_project_back)) }
            Text(stringResource(R.string.local_project_management_title))
        }
        Text(stringResource(R.string.local_project_new_session_hint))
        if (recovery != null) {
            Text(requireNotNull(recovery))
            Button(
                onClick = { runCatching { actions.backupAndReset() }.onFailure { error = it.message } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.local_project_backup_and_reset)) }
            error?.let { Text(it) }
            return@Column
        }
        Button(
            onClick = {
                runCatching { actions.createProjectWorkSession() }
                    .onSuccess { if (it) actions.onProjectSessionAccepted() else error = "会话当前不可切换" }
                    .onFailure { error = it.message }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.local_project_start_work_session)) }
        state.projects.forEach { project ->
            Button(
                onClick = {
                    runCatching { actions.select(project.id) }
                        .onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (project.id == state.activeId) "✓ ${project.name}" else project.name)
            }
        }
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.take(80) },
            label = { Text(stringResource(R.string.local_project_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                runCatching { actions.create(newName) }
                    .onSuccess { newName = ""; error = null }
                    .onFailure { error = it.message }
            },
            enabled = newName.isNotBlank(),
        ) { Text(stringResource(R.string.local_project_create)) }
        active?.let { project ->
            Text(stringResource(R.string.local_project_instruction_label, project.name))
            OutlinedTextField(
                value = instructionDraft,
                onValueChange = { instructionDraft = it.take(8_000) },
                label = { Text(stringResource(R.string.local_project_instruction_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12,
            )
            Button(
                onClick = {
                    runCatching { actions.updateInstructions(project.id, instructionDraft) }
                        .onSuccess { error = null }
                        .onFailure { error = it.message }
                },
                enabled = instructionDraft != project.instructions,
            ) { Text(stringResource(R.string.local_project_save_instruction)) }
        }
        error?.let { Text(it) }
    }
}
