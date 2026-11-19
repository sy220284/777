package com.labteto.dshmobile.ui.screens.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyBannerKind
import com.shenyan.designsystem.components.SyInlineBanner
import com.shenyan.designsystem.components.SyProgressRing

@Composable
internal fun TaskManagementFeedback(
    state: TasksUiState,
    onRefresh: () -> Unit,
    showNotice: Boolean = true,
    showLoading: Boolean = true,
) {
    val refresh = stringResource(R.string.tasks_refresh_list)
    if (state.listLoadFailed) {
        SyInlineBanner(
            message = stringResource(R.string.tasks_list_load_failed),
            kind = SyBannerKind.ERROR,
            actionLabel = if (state.loading) null else refresh,
            onAction = if (state.loading) null else onRefresh,
        )
    }
    val failedAction = state.failedAction
    if (state.managementFailed || state.managementError != null || failedAction != null) {
        val actionLabel = when (failedAction) {
            TaskManagementAction.DELETE -> stringResource(R.string.tasks_cancel)
            TaskManagementAction.PAUSE -> stringResource(R.string.tasks_pause)
            TaskManagementAction.RESUME -> stringResource(R.string.tasks_resume)
            TaskManagementAction.RUN_NOW -> stringResource(R.string.tasks_run_now_action)
            TaskManagementAction.APPROVE -> stringResource(R.string.tasks_approve_resume)
            null -> null
        }
        val actionMessage = actionLabel?.let {
            stringResource(R.string.tasks_named_action_not_confirmed, it)
        }
        val detail = state.managementError?.takeIf(String::isNotBlank)
            ?: if (failedAction == null) stringResource(R.string.tasks_operation_failed) else null
        SyInlineBanner(
            message = listOfNotNull(actionMessage, detail).joinToString(" · ")
                .ifEmpty { stringResource(R.string.tasks_operation_failed) },
            kind = SyBannerKind.ERROR,
            actionLabel = if (state.listLoadFailed || failedAction == null || state.loading) null else refresh,
            onAction = if (state.listLoadFailed || failedAction == null || state.loading) null else onRefresh,
        )
    } else if (showNotice) {
        state.notice?.let { notice ->
            val res = when (notice) {
                TasksNotice.CANCELLED -> R.string.tasks_cancelled
                TasksNotice.MISSING -> R.string.tasks_missing
                TasksNotice.RUN_STARTED -> R.string.tasks_run_started
                TasksNotice.RUN_FAILED -> R.string.tasks_run_now_failed
                TasksNotice.PAUSED -> R.string.tasks_paused_notice
                TasksNotice.RESUMED -> R.string.tasks_resumed_notice
                TasksNotice.OPERATION_FAILED -> R.string.tasks_operation_failed
                TasksNotice.OPERATION_REJECTED -> R.string.tasks_operation_rejected
            }
            val kind = when (notice) {
                TasksNotice.RUN_FAILED, TasksNotice.OPERATION_FAILED -> SyBannerKind.ERROR
                TasksNotice.MISSING, TasksNotice.OPERATION_REJECTED -> SyBannerKind.WARNING
                TasksNotice.RUN_STARTED -> SyBannerKind.INFO
                else -> SyBannerKind.SUCCESS
            }
            SyInlineBanner(stringResource(res), kind)
        }
    }
    if (showLoading && state.loading) {
        SyProgressRing(progress = null, label = stringResource(R.string.common_loading))
    }
}
