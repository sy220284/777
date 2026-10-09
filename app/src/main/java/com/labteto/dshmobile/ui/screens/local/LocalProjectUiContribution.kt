package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal data class LocalProjectUiActions(
    val catalog: StateFlow<LocalProjectCatalogState>,
    val recoveryNotice: StateFlow<String?>,
    val backupAndReset: () -> Unit,
    val createProjectWorkSession: () -> Boolean,
    val onProjectSessionAccepted: () -> Unit,
    val create: (String) -> String,
    val select: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: suspend (String) -> Unit = {},
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
internal fun LocalProjectScreen(
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
    var nameDraft by remember(active?.id, active?.name) {
        mutableStateOf(active?.name.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val localProjectCannotSwitchMessage = stringResource(R.string.local_project_cannot_switch_session)
    val deleteFailedMessage = stringResource(R.string.local_project_delete_failed)

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsButton(text = stringResource(R.string.local_project_back), onClick = onBack, variant = DsButtonVariant.Ghost)
            Text(
                stringResource(R.string.local_project_management_title),
                style = DsType.base16Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
            )
        }
        Text(
            stringResource(R.string.local_project_new_session_hint),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
        )
        if (recovery != null) {
            Text(requireNotNull(recovery))
            DsButton(
                text = stringResource(R.string.local_project_backup_and_reset),
                onClick = { runCatching { actions.backupAndReset() }.onFailure { error = it.message } },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Danger,
            )
            error?.let { Text(it) }
            return@Column
        }
        DsButton(
            text = stringResource(R.string.local_project_start_work_session),
            onClick = {
                runCatching { actions.createProjectWorkSession() }
                    .onSuccess { if (it) actions.onProjectSessionAccepted() else error = localProjectCannotSwitchMessage }
                    .onFailure { error = it.message }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.local_project_select_hint),
            style = DsType.std14Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
        )
        state.projects.forEach { project ->
            DsButton(
                text = if (project.id == state.activeId) "✓ ${project.name}" else project.name,
                onClick = {
                    runCatching { actions.select(project.id) }
                        .onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Outline,
            )
        }
        DsTextField(
            value = newName,
            onValueChange = { newName = it.take(80) },
            label = { Text(stringResource(R.string.local_project_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        DsButton(
            text = stringResource(R.string.local_project_create),
            onClick = {
                runCatching { actions.create(newName) }
                    .onSuccess { newName = ""; error = null }
                    .onFailure { error = it.message }
            },
            enabled = newName.isNotBlank(),
        )
        active?.let { project ->
            Text(
                stringResource(R.string.local_project_current_settings),
                style = DsType.std14Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
            )
            DsTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(80) },
                label = { Text(stringResource(R.string.local_project_rename_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            DsButton(
                text = stringResource(R.string.local_project_rename),
                onClick = {
                    runCatching { actions.rename(project.id, nameDraft) }
                        .onSuccess { error = null }
                        .onFailure { error = it.message }
                },
                enabled = nameDraft.isNotBlank() && nameDraft.trim() != project.name,
            )
            Text(stringResource(R.string.local_project_instruction_label, project.name))
            DsTextField(
                value = instructionDraft,
                onValueChange = { instructionDraft = it.take(8_000) },
                label = { Text(stringResource(R.string.local_project_instruction_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12,
            )
            DsButton(
                text = stringResource(R.string.local_project_save_instruction),
                onClick = {
                    runCatching { actions.updateInstructions(project.id, instructionDraft) }
                        .onSuccess { error = null }
                        .onFailure { error = it.message }
                },
                enabled = instructionDraft != project.instructions,
            )
            if (project.id != com.labteto.dshmobile.local.project.DEFAULT_PROJECT_ID) {
                DsButton(
                    text = stringResource(R.string.local_project_delete),
                    onClick = { pendingDeleteId = project.id },
                    variant = DsButtonVariant.Danger,
                    enabled = !deleting,
                )
            }
        }
        error?.let {
            Text(it, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error)
        }
    }
    val target = state.projects.firstOrNull { it.id == pendingDeleteId }
    if (target != null) {
        DsDialog(
            title = stringResource(R.string.local_project_delete),
            onDismiss = { if (!deleting) pendingDeleteId = null },
        ) {
            Text(stringResource(R.string.local_project_delete_confirm, target.name))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDeleteId = null },
                    variant = DsButtonVariant.Ghost,
                    enabled = !deleting,
                )
                DsButton(
                    text = stringResource(R.string.local_project_delete),
                    variant = DsButtonVariant.Danger,
                    loading = deleting,
                    enabled = !deleting,
                    onClick = {
                        if (deleting) return@DsButton
                        deleting = true
                        scope.launch {
                            try {
                                actions.delete(target.id)
                                pendingDeleteId = null
                                error = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                pendingDeleteId = null
                                error = failure.message ?: deleteFailedMessage
                            } finally {
                                deleting = false
                            }
                        }
                    },
                )
            }
        }
    }
}
