package com.labteto.dshmobile.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.LocalChatAutomationPolicy
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCategoryRow
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsComposerMetrics
import com.labteto.dshmobile.ui.components.DsConversationComposer
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.Date

internal enum class ChatAutomationVisualStatus {
    PENDING, RUNNING, ONGOING, COMPLETED, PAUSED, WAITING, BLOCKED, FAILED,
}

internal fun chatAutomationVisualStatus(task: AutomationTask): ChatAutomationVisualStatus = when (task.status) {
    AutomationStatus.RUNNING, AutomationStatus.QUEUED -> ChatAutomationVisualStatus.RUNNING
    AutomationStatus.COMPLETED -> ChatAutomationVisualStatus.COMPLETED
    AutomationStatus.PAUSED -> ChatAutomationVisualStatus.PAUSED
    AutomationStatus.WAITING_USER -> ChatAutomationVisualStatus.WAITING
    AutomationStatus.BLOCKED -> ChatAutomationVisualStatus.BLOCKED
    AutomationStatus.FAILED -> ChatAutomationVisualStatus.FAILED
    else -> if (
        task.lastRunAt != null &&
        (task.recurringMinutes != null ||
            task.scheduleType == AutomationScheduleType.SILENCE ||
            task.scheduleType == AutomationScheduleType.WINDOW)
    ) ChatAutomationVisualStatus.ONGOING else ChatAutomationVisualStatus.PENDING
}

internal fun sortChatAutomationTasks(tasks: List<AutomationTask>): List<AutomationTask> =
    tasks.sortedWith(
        compareBy<AutomationTask> {
            when (chatAutomationVisualStatus(it)) {
                ChatAutomationVisualStatus.RUNNING -> 0
                ChatAutomationVisualStatus.PENDING, ChatAutomationVisualStatus.ONGOING -> 1
                ChatAutomationVisualStatus.WAITING, ChatAutomationVisualStatus.PAUSED -> 2
                ChatAutomationVisualStatus.BLOCKED, ChatAutomationVisualStatus.FAILED -> 3
                ChatAutomationVisualStatus.COMPLETED -> 4
            }
        }.thenBy { it.nextRunAt },
    )

@Composable
internal fun ChatAutomationScreen(
    state: TasksUiState,
    harnessState: LocalHarnessTaskState,
    viewModel: TasksViewModel,
    onClose: () -> Unit,
    onOpenSession: (String) -> Unit,
    handleRootSystemBack: Boolean,
) {
    val colors = DsTheme.colors
    val canPlan = harnessState.usageMode == LocalUsageMode.CHAT &&
        !harnessState.groupChat.enabled &&
        harnessState.sessionId.isNotBlank()
    val tasks = remember(state.tasks) {
        sortChatAutomationTasks(state.tasks.filter { it.mode == AutomationMode.CHAT })
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var observedSaveRevision by remember { mutableLongStateOf(state.saveRevision) }
    val planMention = stringResource(R.string.tasks_chat_plan_mention)

    BackHandler(enabled = handleRootSystemBack, onBack = onClose)
    LaunchedEffect(harnessState.sessionId, canPlan) {
        if (canPlan) viewModel.loadChatSuggestions()
    }
    LaunchedEffect(state.saveRevision) {
        if (state.saveRevision > observedSaveRevision) {
            draft = ""
            editingTaskId = null
        }
        observedSaveRevision = state.saveRevision
    }

    Surface(Modifier.fillMaxSize(), color = colors.rootSurface()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Box(Modifier.padding(horizontal = DsSpacing.large)) {
                DsTopBar(
                    title = stringResource(R.string.tasks_chat_title),
                    subtitle = stringResource(R.string.tasks_chat_subtitle),
                    onBack = onClose,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = true,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                item("events-heading") {
                    SectionTitle(
                        title = stringResource(R.string.tasks_chat_events),
                        subtitle = if (canPlan) {
                            stringResource(
                                R.string.tasks_chat_target,
                                harnessState.chatPersona.name.ifBlank {
                                    stringResource(R.string.tasks_chat_character_fallback)
                                },
                            )
                        } else {
                            stringResource(R.string.tasks_chat_mode_unavailable)
                        },
                    )
                }
                if (tasks.isEmpty()) {
                    item("events-empty") {
                        DsGroupCard(Modifier.padding(horizontal = DsSpacing.large)) {
                            Text(
                                stringResource(R.string.tasks_chat_events_empty),
                                style = DsType.std14.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                    }
                } else {
                    items(tasks, key = AutomationTask::id) { task ->
                        val scheduleDescription = chatScheduleDescription(task)
                        val editSeed = stringResource(
                            R.string.tasks_chat_edit_seed,
                            task.prompt,
                            scheduleDescription,
                        )
                        ChatAutomationEventRow(
                            task = task,
                            scheduleDescription = scheduleDescription,
                            currentSessionId = harnessState.sessionId,
                            onEdit = {
                                editingTaskId = task.id
                                draft = "$planMention $editSeed"
                            },
                            onOpenSession = onOpenSession,
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onDelete = { viewModel.cancel(task.id) },
                        )
                    }
                }

                item("suggestions-heading") {
                    SectionTitle(
                        title = stringResource(R.string.tasks_chat_suggestions_title),
                        subtitle = stringResource(R.string.tasks_chat_suggestions_subtitle),
                    )
                }
                when {
                    state.suggestionsLoading -> item("suggestions-loading") {
                        Text(
                            stringResource(R.string.tasks_chat_suggestions_loading),
                            modifier = Modifier.padding(horizontal = DsSpacing.large),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelSecondary,
                        )
                    }
                    state.plannerSuggestions.isEmpty() -> item("suggestions-empty") {
                        Text(
                            stringResource(R.string.tasks_chat_suggestions_empty),
                            modifier = Modifier.padding(horizontal = DsSpacing.large),
                            style = DsType.small13.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                    }
                    else -> items(
                        state.plannerSuggestions.take(3),
                        key = { "suggestion|$it" },
                    ) { suggestion ->
                        DsGroupCard(Modifier.padding(horizontal = DsSpacing.large)) {
                            DsCategoryRow(
                                icon = FeatherIcons.Clock,
                                title = suggestion,
                                subtitle = stringResource(R.string.tasks_chat_suggestion_hint),
                                onClick = {
                                    editingTaskId = null
                                    draft = "$planMention $suggestion"
                                },
                            )
                        }
                    }
                }
            }

            state.plannerError?.let {
                Text(
                    stringResource(it.messageRes()),
                    modifier = Modifier.padding(horizontal = DsSpacing.large),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.error,
                )
            }
            DsConversationComposer {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsPill(text = stringResource(R.string.tasks_chat_plan_mention))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    DsComposerField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = stringResource(R.string.tasks_chat_plan_placeholder),
                        modifier = Modifier.weight(1f),
                        enabled = canPlan && !state.planning,
                        maxLines = 5,
                    )
                    DsComposerAction(
                        icon = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.tasks_chat_plan_send),
                        onClick = { viewModel.submitChatPlan(draft, editingTaskId) },
                        enabled = canPlan && draft.isNotBlank() && !state.planning,
                        tint = if (canPlan && draft.isNotBlank() && !state.planning) {
                            colors.onAccent
                        } else {
                            colors.labelTertiary
                        },
                        containerColor = if (canPlan && draft.isNotBlank() && !state.planning) {
                            colors.buttonInfoFill
                        } else {
                            colors.buttonPrimaryDimmed
                        },
                        visualSize = DsComposerMetrics.primaryActionVisualSize,
                    )
                }
                if (state.planning) {
                    Text(
                        stringResource(R.string.tasks_chat_planning),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                } else if (editingTaskId != null) {
                    Text(
                        stringResource(R.string.tasks_chat_editing_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = DsTheme.colors
    Column(
        Modifier.padding(horizontal = DsSpacing.large, vertical = DsSpacing.tiny),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = DsType.base16Strong.withReadingWeight(), color = colors.labelPrimary)
        Text(subtitle, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
    }
}

@Composable
private fun ChatAutomationEventRow(
    task: AutomationTask,
    scheduleDescription: String,
    currentSessionId: String,
    onEdit: () -> Unit,
    onOpenSession: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val visualStatus = chatAutomationVisualStatus(task)
    var confirmDelete by remember { mutableStateOf(false) }
    DsGroupCard(Modifier.padding(horizontal = DsSpacing.large)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                task.actorName?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.tasks_chat_character_fallback),
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.labelSecondary,
            )
            DsStatusPill(
                state = visualStatus.dsStatus(),
                label = visualStatus.label(),
            )
        }
        Text(
            task.prompt,
            style = DsType.std14.withReadingWeight(),
            color = colors.labelPrimary,
            maxLines = 3,
        )
        Text(
            scheduleDescription,
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (task.recurringMinutes != null ||
                task.scheduleType == AutomationScheduleType.SILENCE ||
                task.scheduleType == AutomationScheduleType.WINDOW
            ) {
                DsButton(
                    text = stringResource(
                        if (task.status == AutomationStatus.PAUSED) R.string.tasks_resume else R.string.tasks_pause,
                    ),
                    onClick = if (task.status == AutomationStatus.PAUSED) onResume else onPause,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            if (task.targetSessionId == currentSessionId) {
                DsButton(
                    text = stringResource(R.string.tasks_edit),
                    onClick = onEdit,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            } else {
                task.targetSessionId?.let { sessionId ->
                    DsButton(
                        text = stringResource(R.string.tasks_chat_open_conversation),
                        onClick = { onOpenSession(sessionId) },
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
            DsButton(
                text = stringResource(R.string.tasks_delete_confirm),
                onClick = { confirmDelete = true },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
    if (confirmDelete) {
        DsDialog(
            title = stringResource(R.string.tasks_delete_confirm_title),
            onDismiss = { confirmDelete = false },
        ) {
            Text(
                stringResource(R.string.tasks_chat_delete_confirm_body),
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
                        onDelete()
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

@Composable
private fun ChatAutomationVisualStatus.label(): String = stringResource(
    when (this) {
        ChatAutomationVisualStatus.PENDING -> R.string.tasks_chat_status_pending
        ChatAutomationVisualStatus.RUNNING -> R.string.tasks_chat_status_running
        ChatAutomationVisualStatus.ONGOING -> R.string.tasks_chat_status_ongoing
        ChatAutomationVisualStatus.COMPLETED -> R.string.tasks_chat_status_completed
        ChatAutomationVisualStatus.PAUSED -> R.string.tasks_chat_status_paused
        ChatAutomationVisualStatus.WAITING -> R.string.tasks_chat_status_waiting
        ChatAutomationVisualStatus.BLOCKED -> R.string.tasks_chat_status_blocked
        ChatAutomationVisualStatus.FAILED -> R.string.tasks_chat_status_failed
    },
)

private fun ChatAutomationVisualStatus.dsStatus(): DsStatus = when (this) {
    ChatAutomationVisualStatus.RUNNING -> DsStatus.Running
    ChatAutomationVisualStatus.COMPLETED -> DsStatus.Done
    ChatAutomationVisualStatus.BLOCKED -> DsStatus.Warning
    ChatAutomationVisualStatus.FAILED -> DsStatus.Failed
    else -> DsStatus.Neutral
}

@Composable
private fun chatScheduleDescription(task: AutomationTask): String = when (task.scheduleType) {
    AutomationScheduleType.SILENCE -> stringResource(
        R.string.tasks_chat_schedule_silence,
        ((task.silenceMinutes ?: LocalChatAutomationPolicy.MIN_SILENCE_MINUTES) / 60L)
            .coerceAtLeast(LocalChatAutomationPolicy.MIN_SILENCE_MINUTES / 60L),
    )
    AutomationScheduleType.WINDOW -> stringResource(
        R.string.tasks_chat_schedule_window,
        formatMinute(task.windowStartMinuteOfDay ?: 20 * 60),
        formatMinute(task.windowEndMinuteOfDay ?: 22 * 60),
    )
    AutomationScheduleType.DAILY -> stringResource(
        R.string.tasks_chat_schedule_daily,
        shortTime(task.nextRunAt),
    )
    AutomationScheduleType.WEEKLY -> stringResource(
        R.string.tasks_chat_schedule_weekly,
        shortTime(task.nextRunAt),
    )
    AutomationScheduleType.INTERVAL, AutomationScheduleType.LEGACY -> {
        val minutes = task.recurringMinutes
        when {
            minutes == null -> stringResource(R.string.tasks_chat_schedule_once, shortTime(task.nextRunAt))
            minutes % 60L == 0L -> stringResource(
                R.string.tasks_chat_schedule_interval_hours,
                minutes / 60L,
                shortTime(task.nextRunAt),
            )
            else -> stringResource(
                R.string.tasks_chat_schedule_interval_minutes,
                minutes,
                shortTime(task.nextRunAt),
            )
        }
    }
    AutomationScheduleType.ONCE ->
        stringResource(R.string.tasks_chat_schedule_once, shortTime(task.nextRunAt))
}

private fun PlannerUiError.messageRes(): Int = when (this) {
    PlannerUiError.SUGGESTIONS_FAILED -> R.string.tasks_chat_suggestions_failed
    PlannerUiError.SESSION_CHANGED -> R.string.tasks_chat_session_changed
    PlannerUiError.SAVE_FAILED -> R.string.tasks_chat_save_failed
    PlannerUiError.PLAN_FAILED -> R.string.tasks_chat_plan_failed
}

private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private fun shortTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
