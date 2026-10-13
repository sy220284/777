package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.shenyan.designsystem.components.SyCategoryRow
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.uicontract.ProjectUiPort
import com.labteto.dshmobile.uicontract.ProjectUiCommand
import com.labteto.dshmobile.uicontract.ProjectUiReceipt
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal fun localProjectFeatureUiContribution(
    port: ProjectUiPort,
    onCreateWorkSession: () -> Boolean,
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
    LocalProjectScreen(port, onCreateWorkSession, onPopFeature)
}

@Composable
internal fun LocalProjectScreen(
    port: ProjectUiPort,
    onCreateWorkSession: () -> Boolean,
    onBack: () -> Unit,
) {
    val initial = remember(port) { port.snapshot() }
    val state by port.snapshots.collectAsStateWithLifecycle(initialValue = initial)
    val recovery = state.recoveryNotice
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
    val operationFailedMessage = stringResource(R.string.common_error)
    val deleteFailedMessage = stringResource(R.string.local_project_delete_failed)

    fun submit(command: ProjectUiCommand, onApplied: () -> Unit = {}) {
        scope.launch {
            when (val receipt = port.submit(command)) {
                is ProjectUiReceipt.Applied -> { error = null; onApplied() }
                is ProjectUiReceipt.Failed -> { error = receipt.message ?: operationFailedMessage }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SyTopBar(
            title = stringResource(R.string.local_project_management_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.local_project_back),
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.local_project_new_session_hint),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
            if (recovery != null) {
                Text(requireNotNull(recovery))
                SyButton(
                    text = stringResource(R.string.local_project_backup_and_reset),
                    onClick = { submit(ProjectUiCommand.BackupAndResetCatalog) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Danger,
                )
                return@Column
            }
            SyButton(
                text = stringResource(R.string.local_project_start_work_session),
                onClick = {
                    runCatching { onCreateWorkSession() }
                        .onSuccess { if (!it) error = localProjectCannotSwitchMessage }
                        .onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.local_project_select_hint),
                style = DsType.std14Strong.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
            )
            SyGlassCard {
                state.projects.forEach { project ->
                    SyCategoryRow(
                        icon = if (project.id == state.activeId) FeatherIcons.CheckCircle else FeatherIcons.Folder,
                        title = project.name,
                        modifier = Modifier.testTag("projectRow:${project.id}")
                            .semantics { selected = project.id == state.activeId },
                        subtitle = project.instructions.takeIf(String::isNotBlank),
                        onClick = {
                            submit(ProjectUiCommand.Select(project.id))
                        },
                    )
                }
            }
            SyGlassCard {
                SyTextField(
                    value = newName,
                    onValueChange = { newName = it.take(80) },
                    label = { Text(stringResource(R.string.local_project_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SyButton(
                    text = stringResource(R.string.local_project_create),
                    onClick = {
                        submit(ProjectUiCommand.Create(newName)) { newName = "" }
                    },
                    enabled = newName.isNotBlank(),
                    variant = SyButtonVariant.Outline,
                )
            }
            active?.let { project ->
                SyGlassCard {
                    Text(
                        stringResource(R.string.local_project_current_settings),
                        style = DsType.std14Strong.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                    )
                    SyTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(80) },
                        label = { Text(stringResource(R.string.local_project_rename_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    SyButton(
                        text = stringResource(R.string.local_project_rename),
                        onClick = {
                            submit(ProjectUiCommand.Rename(project.id, nameDraft))
                        },
                        enabled = nameDraft.isNotBlank() && nameDraft.trim() != project.name,
                        variant = SyButtonVariant.Outline,
                    )
                    Text(stringResource(R.string.local_project_instruction_label, project.name))
                    SyTextField(
                        value = instructionDraft,
                        onValueChange = { instructionDraft = it.take(8_000) },
                        label = { Text(stringResource(R.string.local_project_instruction_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 12,
                    )
                    SyButton(
                        text = stringResource(R.string.local_project_save_instruction),
                        onClick = {
                            submit(ProjectUiCommand.UpdateInstructions(project.id, instructionDraft))
                        },
                        enabled = instructionDraft != project.instructions,
                        variant = SyButtonVariant.Outline,
                    )
                    if (project.deletable) {
                        SyButton(
                            text = stringResource(R.string.local_project_delete),
                            onClick = { pendingDeleteId = project.id },
                            variant = SyButtonVariant.Danger,
                            enabled = !deleting,
                        )
                    }
                }
            }
        }
        error?.let { message ->
            SyGlassCard(modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(message, style = DsType.small13.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk)
            }
        }
    }
    val target = state.projects.firstOrNull { it.id == pendingDeleteId }
    if (target != null) {
        SyDialog(
            title = stringResource(R.string.local_project_delete),
            onDismiss = { if (!deleting) pendingDeleteId = null },
        ) {
            Text(stringResource(R.string.local_project_delete_confirm, target.name))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDeleteId = null },
                    variant = SyButtonVariant.Ghost,
                    enabled = !deleting,
                )
                SyButton(
                    text = stringResource(R.string.local_project_delete),
                    modifier = Modifier.testTag("local_project_confirm_delete"),
                    variant = SyButtonVariant.Danger,
                    loading = deleting,
                    enabled = !deleting,
                    onClick = {
                        if (deleting) return@SyButton
                        deleting = true
                        scope.launch {
                            try {
                                when (val receipt = port.submit(ProjectUiCommand.Delete(target.id))) {
                                    is ProjectUiReceipt.Applied -> { pendingDeleteId = null; error = null }
                                    is ProjectUiReceipt.Failed -> {
                                        pendingDeleteId = null
                                        error = receipt.message ?: deleteFailedMessage
                                    }
                                }
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
