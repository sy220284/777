package com.labteto.dshmobile.ui.screens.tasks

import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalAutomationPolicyProjection
import com.labteto.dshmobile.local.presentation.LocalHarnessTaskState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsSwitch
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsBottomSheet
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
import com.labteto.dshmobile.ui.theme.DsShapes
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
    var policyTaskId by rememberSaveable { mutableStateOf<String?>(null) }
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
                    backIcon = FeatherIcons.X,
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
                        AutomationCardSurface(
                            modifier = Modifier.padding(horizontal = DsSpacing.large),
                        ) {
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
                        val optimizeSeed = stringResource(
                            R.string.tasks_chat_optimization_seed,
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
                            onOptimize = {
                                editingTaskId = task.id
                                draft = "$planMention $optimizeSeed"
                            },
                            onPolicy = { policyTaskId = task.id },
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
                        AutomationSuggestionRow(
                            text = suggestion,
                            onClick = {
                                editingTaskId = null
                                draft = "$planMention $suggestion"
                            },
                            modifier = Modifier.padding(horizontal = DsSpacing.large),
                        )
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
                    val canSubmitPlan = canPlan && draft.isNotBlank() && !state.planning
                    val dark = colors.bgBase.luminance() < 0.5f
                    val sendRes = when {
                        !canSubmitPlan && dark -> R.drawable.ic_ui_button_send_disabled_dark
                        !canSubmitPlan -> R.drawable.ic_ui_button_send_disabled_light
                        dark -> R.drawable.ic_ui_button_send_dark
                        else -> R.drawable.ic_ui_button_send_light
                    }
                    DsComposerAction(
                        icon = null,
                        contentDescription = stringResource(R.string.tasks_chat_plan_send),
                        onClick = { viewModel.submitChatPlan(draft, editingTaskId) },
                        enabled = canSubmitPlan,
                        containerColor = Color.Transparent,
                        visualSize = 32.dp,
                        content = {
                            Image(
                                painter = painterResource(sendRes),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                        },
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

    tasks.firstOrNull { it.id == policyTaskId }?.let { task ->
        ChatAutomationPolicySheet(
            task = task,
            viewModel = viewModel,
            onDismiss = { policyTaskId = null },
        )
    }
}

@Composable
private fun AutomationCardSurface(
    modifier: Modifier = Modifier,
    status: DsStatus = DsStatus.Neutral,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val borderColor = when (status) {
        DsStatus.Running -> colors.accent.copy(alpha = 0.28f)
        DsStatus.Warning -> colors.warn.copy(alpha = 0.32f)
        DsStatus.Failed -> colors.error.copy(alpha = 0.28f)
        else -> colors.borderL1
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
private fun AutomationSuggestionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = DsShapes.row,
                color = colors.bgModulePlatform,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        FeatherIcons.Clock,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    text,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(R.string.tasks_chat_suggestion_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            androidx.compose.material3.Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(16.dp),
            )
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
    onOptimize: () -> Unit,
    onPolicy: () -> Unit,
    onOpenSession: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val visualStatus = chatAutomationVisualStatus(task)
    var confirmDelete by remember { mutableStateOf(false) }
    AutomationCardSurface(
        modifier = Modifier.padding(horizontal = DsSpacing.large),
        status = visualStatus.dsStatus(),
    ) {
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
                if (
                    task.recurringMinutes != null ||
                    task.scheduleType == AutomationScheduleType.SILENCE ||
                    task.scheduleType == AutomationScheduleType.WINDOW
                ) {
                    DsButton(
                        text = stringResource(R.string.tasks_chat_policy_action),
                        onClick = onPolicy,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
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
    if (task.targetSessionId == currentSessionId) {
        DsButton(
            text = stringResource(R.string.tasks_chat_optimize),
            onClick = onOptimize,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )
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
private fun ChatAutomationPolicySheet(
    task: AutomationTask,
    viewModel: TasksViewModel,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var editingQuietStart by rememberSaveable(task.id) { mutableStateOf<Boolean?>(null) }
    val saveFailedMessage = stringResource(R.string.tasks_chat_policy_save_failed)
    var quietEnabled by remember(task.id) { mutableStateOf(task.quietHoursEnabled) }
    var quietStartMinute by remember(task.id) {
        mutableStateOf(task.quietStartHour * 60 + task.quietStartMinute)
    }
    var quietEndMinute by remember(task.id) {
        mutableStateOf(task.quietEndHour * 60 + task.quietEndMinute)
    }
    var minGapMinutes by remember(task.id) { mutableStateOf(task.proactiveMinGapMinutes) }
    var maxUnanswered by remember(task.id) { mutableStateOf(task.proactiveMaxUnanswered) }
    var notify by remember(task.id) { mutableStateOf(task.notify) }
    var saveError by remember(task.id) { mutableStateOf<String?>(null) }
    val gapOptions = listOf(60L, 120L, 240L, 360L, 720L, 1_440L)

    if (editingQuietStart != null) {
        AutomationTimePickerSheet(
            initialMinuteOfDay = if (editingQuietStart == true) quietStartMinute else quietEndMinute,
            onPicked = {
                if (editingQuietStart == true) quietStartMinute = it else quietEndMinute = it
                editingQuietStart = null
            },
            onDismiss = { editingQuietStart = null },
        )
        return
    }
    DsBottomSheet(
        title = stringResource(R.string.tasks_chat_policy_title),
        subtitle = stringResource(R.string.tasks_chat_policy_subtitle),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tasks_chat_policy_quiet_hours),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(
                        R.string.tasks_chat_policy_quiet_hours_value,
                        formatMinute(quietStartMinute),
                        formatMinute(quietEndMinute),
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            DsSwitch(
                checked = quietEnabled,
                onCheckedChange = { quietEnabled = it },
            )
        }
        if (quietEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(
                        R.string.tasks_chat_quiet_start,
                        formatMinute(quietStartMinute),
                    ),
                    onClick = {
                        editingQuietStart = true
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(
                        R.string.tasks_chat_quiet_end,
                        formatMinute(quietEndMinute),
                    ),
                    onClick = {
                        editingQuietStart = false
                    },
                    modifier = Modifier.weight(1f),
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
            }
        }

        Text(
            stringResource(R.string.tasks_chat_policy_min_gap),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
        )
        gapOptions.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                row.forEach { minutes ->
                    DsButton(
                        text = stringResource(R.string.tasks_chat_gap_hours, minutes / 60L),
                        onClick = { minGapMinutes = minutes },
                        modifier = Modifier.weight(1f),
                        variant = if (minGapMinutes == minutes) {
                            DsButtonVariant.Info
                        } else {
                            DsButtonVariant.Outline
                        },
                        size = DsButtonSize.Small,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.tasks_chat_policy_min_gap_hint),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )

        Text(
            stringResource(R.string.tasks_chat_max_unanswered),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.labelPrimary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            (1..5).forEach { count ->
                DsButton(
                    text = count.toString(),
                    onClick = { maxUnanswered = count },
                    modifier = Modifier.weight(1f),
                    variant = if (maxUnanswered == count) {
                        DsButtonVariant.Info
                    } else {
                        DsButtonVariant.Outline
                    },
                    size = DsButtonSize.Small,
                )
            }
        }
        Text(
            stringResource(R.string.tasks_chat_max_unanswered_hint, maxUnanswered),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tasks_notify_title),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(R.string.tasks_notify_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
            DsSwitch(
                checked = notify,
                onCheckedChange = { notify = it },
            )
        }

        }
        saveError?.let {
            Text(
                it,
                style = DsType.caption11.withReadingWeight(),
                color = colors.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            DsButton(
                text = stringResource(R.string.tasks_save),
                onClick = {
                    val saved = viewModel.updateChatPolicy(
                        task = task,
                        quietHoursEnabled = quietEnabled,
                        quietStartHour = quietStartMinute / 60,
                        quietStartMinute = quietStartMinute % 60,
                        quietEndHour = quietEndMinute / 60,
                        quietEndMinute = quietEndMinute % 60,
                        proactiveMinGapMinutes = minGapMinutes,
                        proactiveMaxUnanswered = maxUnanswered,
                        notify = notify,
                    )
                    if (saved) {
                        onDismiss()
                    } else {
                        saveError = saveFailedMessage
                    }
                },
                size = DsButtonSize.Small,
            )
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
        ((task.silenceMinutes ?: LocalAutomationPolicyProjection.minimumSilenceMinutes) / 60L)
            .coerceAtLeast(LocalAutomationPolicyProjection.minimumSilenceMinutes / 60L),
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
    AutomationScheduleType.MONTHLY -> stringResource(R.string.tasks_schedule_monthly) + " · " + shortTime(task.nextRunAt)
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
