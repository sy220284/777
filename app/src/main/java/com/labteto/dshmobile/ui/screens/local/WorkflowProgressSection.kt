package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun workflowStageLabel(stage: String): String = stringResource(when (stage) {
    "执行中" -> R.string.local_workflow_executing
    "重新指派" -> R.string.local_workflow_reassigning
    "验收中" -> R.string.local_workflow_verifying
    "已完成" -> R.string.local_workflow_completed
    "受阻" -> R.string.local_workflow_stalled
    else -> R.string.local_workflow_stalled
})

@Composable
internal fun WorkflowProgressSection(progress: LocalWorkflowProgress) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Text(stringResource(R.string.local_workflow_stage, workflowStageLabel(progress.stage)), style = DsType.caption11Strong.withReadingWeight(), color = colors.labelTertiary)
        Text(progress.task, style = DsType.small13.withReadingWeight(), color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.local_workflow_processed, progress.completed, progress.total), style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
        progress.blockedReason?.let { reason ->
            Text(stringResource(R.string.local_workflow_blocked, reason), style = DsType.small13.withReadingWeight(), color = colors.error)
            if (progress.needsUserAction) Text(stringResource(R.string.local_workflow_user_action), style = DsType.caption11.withReadingWeight(), color = colors.error)
        }
    }
}
