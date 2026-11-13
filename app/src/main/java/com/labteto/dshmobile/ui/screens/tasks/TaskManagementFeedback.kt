package com.labteto.dshmobile.ui.screens.tasks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.foundation.LocalSyColors

@Composable
internal fun TaskManagementFeedback(state: TasksUiState, onRefresh: () -> Unit) {
    val colors = LocalSyColors.current
    if (state.managementFailed) {
        Text(stringResource(R.string.tasks_operation_failed), color = colors.dangerInk)
        SyButton(stringResource(R.string.common_retry), onClick = onRefresh)
    } else state.notice?.let { notice ->
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
    if (state.loading) Text(stringResource(R.string.common_loading), color = colors.textSecondary)
}
