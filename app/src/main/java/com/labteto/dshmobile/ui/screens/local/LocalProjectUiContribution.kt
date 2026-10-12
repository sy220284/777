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
import com.labteto.dshmobile.local.project.LocalProjectCatalogState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal data class LocalProjectUiActions(
    val catalog: StateFlow<LocalProjectCatalogState>,
    val recoveryNotice: StateFlow<String?>,
    val backupAndReset: () -> Unit,
    val createProjectWorkSession: () -> Boolean,
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

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyTopBar(
                title = stringResource(R.string.local_project_management_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.local_project_back),
            )
            Text(
                stringResource(R.string.local_project_new_session_hint),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
            )
            if (recovery != null) {
                Text(requireNotNull(recovery))
                SyButton(
                    text = stringResource(R.string.local_project_backup_and_reset),
                    onClick = { runCatching { actions.backupAndReset() }.onFailure { error = it.message } },
                    modifier = Modifier.fillMaxWidth(),
                    variant = SyButtonVariant.Danger,
                )
                return@Column
            }
            SyButton(
                text = stringResource(R.string.local_project_start_work_session),
                onClick = {
                    runCatching { actions.createProjectWorkSession() }
                        .onSuccess { if (!it) error = localProjectCannotSwitchMessage }
                        .onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.local_project_select_hint),
                style = DsType.std14Strong.withReadingWeight(),
                color = DsTheme.colors.labelPrimary,
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
                            runCatching { actions.select(project.id) }
                                .onSuccess { error = null }
                                .onFailure { error = it.message }
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
                        runCatching { actions.create(newName) }
                            .onSuccess { newName = ""; error = null }
                            .onFailure { error = it.message }
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
                        color = DsTheme.colors.labelPrimary,
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
                            runCatching { actions.rename(project.id, nameDraft) }
                                .onSuccess { error = null }
                                .onFailure { error = it.message }
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
                            runCatching { actions.updateInstructions(project.id, instructionDraft) }
                                .onSuccess { error = null }
                                .onFailure { error = it.message }
                        },
                        enabled = instructionDraft != project.instructions,
                        variant = SyButtonVariant.Outline,
                    )
                    if (project.id != com.labteto.dshmobile.local.project.DEFAULT_PROJECT_ID) {
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
                Text(message, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.error)
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
