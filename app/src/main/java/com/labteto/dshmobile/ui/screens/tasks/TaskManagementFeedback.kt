package com.labteto.dshmobile.ui.screens.tasks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.foundation.LocalSyColors

@Composable
internal fun TaskManagementFeedback(
    state: TasksUiState,
    onRefresh: () -> Unit,
    showNotice: Boolean = true,
    showLoading: Boolean = true,
) {
    val colors = LocalSyColors.current
    if (state.listLoadFailed) {
        Text(stringResource(R.string.tasks_list_load_failed), color = colors.dangerInk)
        SyButton(stringResource(R.string.tasks_refresh_list), onClick = onRefresh, enabled = !state.loading)
    }
    val failedAction = state.failedAction
    if (state.managementFailed || state.managementError != null || failedAction != null) {
        if (failedAction != null) {
            val label = when (failedAction) {
                TaskManagementAction.DELETE -> R.string.tasks_cancel
                TaskManagementAction.PAUSE -> R.string.tasks_pause
                TaskManagementAction.RESUME -> R.string.tasks_resume
                TaskManagementAction.RUN_NOW -> R.string.tasks_run_now_action
                TaskManagementAction.APPROVE -> R.string.tasks_approve_resume
            }
            Text(stringResource(R.string.tasks_named_action_not_confirmed, stringResource(label)),
                color = colors.dangerInk)
        }
        val detail = state.managementError?.takeIf(String::isNotBlank)
            ?: if (failedAction == null) stringResource(R.string.tasks_operation_failed) else null
        if (detail != null) Text(detail, color = colors.dangerInk)
        if (!state.listLoadFailed && failedAction != null) {
            Text(stringResource(R.string.tasks_verify_then_retry), color = colors.textSecondary)
            SyButton(stringResource(R.string.tasks_refresh_list), onClick = onRefresh, enabled = !state.loading)
        }
    } else if (showNotice) state.notice?.let { notice ->
        Text(stringResource(when (notice) {
            TasksNotice.CANCELLED -> R.string.tasks_cancelled
            TasksNotice.MISSING -> R.string.tasks_missing
            TasksNotice.RUN_STARTED -> R.string.tasks_run_started
            TasksNotice.RUN_FAILED -> R.string.tasks_run_now_failed
            TasksNotice.PAUSED -> R.string.tasks_paused_notice
            TasksNotice.RESUMED -> R.string.tasks_resumed_notice
            TasksNotice.OPERATION_FAILED -> R.string.tasks_operation_failed
            TasksNotice.OPERATION_REJECTED -> R.string.tasks_operation_rejected
        }), color = colors.textSecondary)
    }
    if (showLoading && state.loading) Text(stringResource(R.string.common_loading), color = colors.textSecondary)
}
