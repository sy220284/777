package com.labteto.dshmobile.ui.screens.tasks

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationRunReceipt
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.presentation.LocalTaskRuntime
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.DsTimeline
import com.labteto.dshmobile.ui.components.DsTimelineItem
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.rootSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

@Composable
private fun TaskCardSurface(
    status: AutomationStatus,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val borderColor = when (status) {
        AutomationStatus.RUNNING, AutomationStatus.QUEUED -> colors.accent.copy(alpha = 0.28f)
        AutomationStatus.WAITING_USER -> colors.warn.copy(alpha = 0.34f)
        AutomationStatus.FAILED, AutomationStatus.BLOCKED -> colors.error.copy(alpha = 0.28f)
        else -> colors.borderL1
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            content = content,
        )
    }
}

@Composable
internal fun TaskCard(
    task: AutomationTask,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    var actionsOpen by remember(task.id) { mutableStateOf(false) }
    val chatCharacterFallback = stringResource(R.string.tasks_chat_character_fallback)
    val backgroundTaskLabel = stringResource(R.string.tasks_background_task)
    val scheduleLabel = when (task.scheduleType) {
        AutomationScheduleType.SILENCE -> {
            val minutes = task.silenceMinutes ?: requireNotNull(task.recurringMinutes) {
                "Normalized silence task requires a duration"
            }
            stringResource(R.string.tasks_schedule_silence_value, minutes / 60L)
        }
        AutomationScheduleType.DAILY -> stringResource(R.string.tasks_schedule_daily)
        AutomationScheduleType.WINDOW -> stringResource(
            R.string.tasks_schedule_window_value,
            formatMinuteOfDay(task.windowStartMinuteOfDay ?: 20 * 60),
            formatMinuteOfDay(task.windowEndMinuteOfDay ?: 22 * 60),
        )
        AutomationScheduleType.WEEKLY -> stringResource(R.string.tasks_schedule_weekly)
        AutomationScheduleType.INTERVAL -> {
            val minutes = task.recurringMinutes ?: 0L
            if (minutes % 60L == 0L) {
                stringResource(R.string.tasks_every_hours, minutes / 60L)
            } else {
                stringResource(R.string.tasks_every_minutes, minutes)
            }
        }
        AutomationScheduleType.ONCE -> stringResource(R.string.tasks_once)
        AutomationScheduleType.LEGACY -> when (task.recurringMinutes) {
            null -> stringResource(R.string.tasks_once)
            24L * 60L -> stringResource(R.string.tasks_schedule_daily)
            7L * 24L * 60L -> stringResource(R.string.tasks_schedule_weekly)
            else -> {
                val minutes = task.recurringMinutes ?: 0L
                if (minutes % 60L == 0L) {
                    stringResource(R.string.tasks_every_hours, minutes / 60L)
                } else {
                    stringResource(R.string.tasks_every_minutes, minutes)
                }
            }
        }
    }
    val title = if (task.mode == AutomationMode.CHAT) {
        val actor = task.actorName?.takeIf(String::isNotBlank) ?: chatCharacterFallback
        "$actor · ${task.prompt.lineSequence().firstOrNull()?.trim()?.take(42).orEmpty()}"
    } else {
        task.prompt.lineSequence().firstOrNull()?.trim()?.take(56).orEmpty()
            .ifBlank { backgroundTaskLabel }
    }
    val terminalOneShot = task.recurringMinutes == null &&
        task.status in setOf(AutomationStatus.COMPLETED, AutomationStatus.FAILED, AutomationStatus.BLOCKED)
    val timing = when {
        task.status == AutomationStatus.WAITING_USER ->
            stringResource(R.string.tasks_waiting_user_timing)
        terminalOneShot && task.lastRunAt != null ->
            stringResource(R.string.tasks_last_run, formatTime(task.lastRunAt))
        else ->
            stringResource(R.string.tasks_next_run, formatTime(task.nextRunAt))
    }

    TaskCardSurface(status = task.status) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    timing,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            Box {
                DsIconButton(
                    icon = FeatherIcons.MoreVertical,
                    contentDescription = stringResource(R.string.tasks_actions),
                    onClick = { actionsOpen = true },
                )
                DsPopupMenu(
                    expanded = actionsOpen,
                    onDismiss = { actionsOpen = false },
                    items = listOf(
                        MenuItem(stringResource(R.string.tasks_edit), FeatherIcons.Edit3, onClick = onEdit),
                        MenuItem(stringResource(R.string.tasks_try_now), FeatherIcons.Activity, onClick = onRunNow),
                        MenuItem(
                            stringResource(if (task.status == AutomationStatus.PAUSED) R.string.tasks_resume else R.string.tasks_pause),
                            FeatherIcons.Clock,
                            onClick = if (task.status == AutomationStatus.PAUSED) onResume else onPause,
                        ),
                        MenuItem(stringResource(R.string.tasks_delete_confirm), FeatherIcons.Trash2, danger = true, onClick = { confirmDelete = true }),
                    ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsStatusPill(
                state = taskStatus(task.status),
                label = taskStatusLabel(task.status),
            )
            DsPill(text = scheduleLabel)
            if (task.mode == AutomationMode.CHAT && task.quietHoursEnabled) {
                DsPill(
                    text = stringResource(
                        R.string.tasks_chat_quiet_hours_value,
                        formatMinuteOfDay(task.quietStartHour * 60 + task.quietStartMinute),
                        formatMinuteOfDay(task.quietEndHour * 60 + task.quietEndMinute),
                    ),
                )
            }
        }

        task.lastError?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_status, it.take(160)),
                style = DsType.caption11.withReadingWeight(),
                color = colors.error,
            )
        }
        task.lastResult?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_result, it.replace("\n", " ").take(180)),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelSecondary,
                maxLines = 2,
            )
        }

        if (task.runReceipts.isNotEmpty()) {
            Text(
                stringResource(R.string.tasks_run_history, task.runReceipts.size),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
            val timelineItems = mutableListOf<DsTimelineItem>()
            for (receipt in task.runReceipts.asReversed()) {
                timelineItems += DsTimelineItem(
                    text = stringResource(
                        R.string.tasks_run_history_item,
                        formatTime(receipt.finishedAt),
                        receiptStatusLabel(receipt),
                    ),
                    state = receiptStatus(receipt.status),
                )
            }
            DsTimeline(items = timelineItems, modifier = Modifier.fillMaxWidth())
        }

        if (task.mode == AutomationMode.CHAT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.tasks_try_now),
                    onClick = onRunNow,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tasks_edit),
                    onClick = onEdit,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }

        (task.targetSessionId ?: task.workSessionId)?.takeIf(String::isNotBlank)?.let { sessionId ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (task.mode == AutomationMode.CHAT && task.recurringMinutes != null) {
                    DsButton(
                        text = stringResource(
                            if (task.status == AutomationStatus.PAUSED) R.string.tasks_resume
                            else R.string.tasks_pause,
                        ),
                        onClick = if (task.status == AutomationStatus.PAUSED) onResume else onPause,
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Ghost,
                    )
                }
                DsButton(
                    text = stringResource(R.string.tasks_open_result),
                    onClick = { onOpenSession(sessionId) },
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
        }
    }

    if (confirmDelete) {
        DsDialog(
            title = stringResource(R.string.tasks_delete_confirm_title),
            onDismiss = { confirmDelete = false },
        ) {
            Text(
                stringResource(
                    if (task.mode == AutomationMode.CHAT) R.string.tasks_chat_delete_confirm_body
                    else R.string.tasks_delete_confirm_body,
                ),
                style = DsType.std14.withReadingWeight(),
                color = colors.labelSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmDelete = false },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.tasks_delete_confirm),
                    onClick = {
                        confirmDelete = false
                        onCancel()
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun receiptStatusLabel(receipt: AutomationRunReceipt): String {
    val state = when (receipt.status) {
        AutomationStatus.COMPLETED -> stringResource(R.string.tasks_run_completed)
        AutomationStatus.BLOCKED -> stringResource(R.string.tasks_run_blocked)
        AutomationStatus.FAILED -> stringResource(R.string.tasks_run_failed)
        AutomationStatus.CANCELLED -> stringResource(R.string.local_execution_notification_cancelled)
        AutomationStatus.SKIPPED -> stringResource(R.string.tasks_run_skipped)
        AutomationStatus.RUNNING, AutomationStatus.QUEUED -> stringResource(R.string.tasks_status_running)
        AutomationStatus.SCHEDULED -> stringResource(R.string.tasks_status_scheduled)
        AutomationStatus.PAUSED -> stringResource(R.string.tasks_status_paused)
        AutomationStatus.WAITING_USER -> stringResource(R.string.tasks_status_waiting_user)
    }
    val detail = receipt.errorPreview ?: receipt.resultPreview
    return if (detail.isNullOrBlank()) state else "$state · $detail"
}

private fun receiptStatus(status: AutomationStatus): DsStatus = when (status) {
    AutomationStatus.COMPLETED -> DsStatus.Done
    AutomationStatus.BLOCKED -> DsStatus.Warning
    AutomationStatus.FAILED -> DsStatus.Failed
    AutomationStatus.RUNNING, AutomationStatus.QUEUED -> DsStatus.Running
    else -> DsStatus.Neutral
}

private fun taskStatus(status: AutomationStatus): DsStatus = when (status) {
    AutomationStatus.RUNNING, AutomationStatus.QUEUED -> DsStatus.Running
    AutomationStatus.COMPLETED -> DsStatus.Done
    AutomationStatus.BLOCKED -> DsStatus.Warning
    AutomationStatus.FAILED -> DsStatus.Failed
    else -> DsStatus.Neutral
}

@Composable
private fun taskStatusLabel(status: AutomationStatus): String = when (status) {
    AutomationStatus.RUNNING -> stringResource(R.string.tasks_status_running)
    AutomationStatus.QUEUED -> stringResource(R.string.tasks_status_queued)
    AutomationStatus.SCHEDULED -> stringResource(R.string.tasks_status_scheduled)
    AutomationStatus.COMPLETED -> stringResource(R.string.tasks_run_completed)
    AutomationStatus.BLOCKED -> stringResource(R.string.tasks_run_blocked)
    AutomationStatus.FAILED -> stringResource(R.string.tasks_run_failed)
    AutomationStatus.PAUSED -> stringResource(R.string.tasks_status_paused)
    AutomationStatus.WAITING_USER -> stringResource(R.string.tasks_status_waiting_user)
    AutomationStatus.CANCELLED -> stringResource(R.string.local_execution_notification_cancelled)
    AutomationStatus.SKIPPED -> stringResource(R.string.tasks_run_skipped)
}

@Composable
internal fun TaskSummaryRow(task: AutomationTask, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
    ) {
        Row(
            modifier = Modifier.padding(DsSpacing.comfortable),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(task.prompt.lineSequence().firstOrNull().orEmpty(), style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary, maxLines = 2)
                Text(formatTime(task.nextRunAt), style = DsType.small13.withReadingWeight(), color = colors.labelTertiary)
                DsStatusPill(state = taskStatus(task.status), label = taskStatusLabel(task.status))
            }
            androidx.compose.material3.Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = colors.labelTertiary)
        }
    }
}
