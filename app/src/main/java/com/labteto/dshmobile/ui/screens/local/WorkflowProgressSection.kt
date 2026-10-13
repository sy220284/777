package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.shenyan.SyProgressBar
import com.labteto.dshmobile.ui.shenyan.SyProgressStatus
import com.labteto.dshmobile.ui.theme.DsAnimations
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import com.labteto.dshmobile.local.presentation.WorkflowStage
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun workflowStageLabel(stage: String): String = stringResource(when (stage) {
    WorkflowStage.EXECUTING -> R.string.local_workflow_executing
    WorkflowStage.REASSIGNING -> R.string.local_workflow_reassigning
    WorkflowStage.VERIFYING -> R.string.local_workflow_verifying
    WorkflowStage.COMPLETED -> R.string.local_workflow_completed
    WorkflowStage.STALLED -> R.string.local_workflow_stalled
    else -> R.string.local_workflow_stalled
})

/** Explicitly derive color/motion from real execution state, not percentage alone. */
internal fun workflowProgressVisualStatus(progress: LocalWorkflowProgress): SyProgressStatus {
    val blocked = progress.blockedReason != null || progress.stage == WorkflowStage.STALLED
    return when {
        blocked -> SyProgressStatus.Failed
        progress.stage == WorkflowStage.COMPLETED && progress.total > 0 &&
            progress.completed >= progress.total -> SyProgressStatus.Done
        progress.total <= 0 -> SyProgressStatus.Idle
        else -> SyProgressStatus.Running
    }
}

/** Only the same completed Work session owns this card identity. */
internal fun workCompletionCardKey(sessionId: String, progress: LocalWorkflowProgress?): String? =
    if (sessionId.isNotBlank() && progress != null && progress.sessionId == sessionId &&
        workflowProgressVisualStatus(progress) == SyProgressStatus.Done
    ) "work-completion:$sessionId" else null

@Composable
internal fun WorkflowProgressSection(progress: LocalWorkflowProgress) {
    val colors = DsTheme.colors
    val reduced = com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion.current
    val isBlocked = progress.blockedReason != null || progress.stage == WorkflowStage.STALLED
    val progressStatus = workflowProgressVisualStatus(progress)
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        // The stage changes in place rather than posting an unconditional completion toast.
        AnimatedContent(
            targetState = progress.stage,
            transitionSpec = {
                fadeIn(animationSpec = if (reduced) androidx.compose.animation.core.tween(0) else DsAnimations.tabSwap) togetherWith
                    fadeOut(animationSpec = if (reduced) androidx.compose.animation.core.tween(0) else DsAnimations.fade)
            },
            label = "workflowStageMorph",
        ) { stage ->
            Text(
                stringResource(R.string.local_workflow_stage, workflowStageLabel(stage)),
                style = DsType.caption11Strong.withReadingWeight(),
                color = if (isBlocked) colors.warnLabel else colors.labelTertiary,
            )
        }
        Text(progress.task, style = DsType.small13.withReadingWeight(), color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.local_workflow_processed, progress.completed, progress.total), style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
        if (progress.total > 0) {
            SyProgressBar(
                value = progress.completed.coerceAtLeast(0).toFloat() /
                    progress.total.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                status = progressStatus,
            )
        }
        progress.blockedReason?.let { reason ->
            Text(stringResource(R.string.local_workflow_blocked, reason), style = DsType.small13.withReadingWeight(), color = colors.error)
            if (progress.needsUserAction) Text(stringResource(R.string.local_workflow_user_action), style = DsType.caption11.withReadingWeight(), color = colors.error)
        }
    }
}
