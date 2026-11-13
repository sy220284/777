package com.labteto.dshmobile.ui.screens.tasks

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.components.SySwitchControl
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyComposerAction
import com.labteto.dshmobile.ui.shenyan.SyComposerField
import com.labteto.dshmobile.ui.shenyan.SyConversationComposer
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.shenyan.designsystem.components.SyPill
import com.labteto.dshmobile.ui.shenyan.SyStatus
import com.labteto.dshmobile.ui.shenyan.SyStatusPill
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.shenyan.SyTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
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

/** UI-only story/session filter; it does not mutate the task's persisted target. */
internal fun chatAutomationInSession(
    targetSessionId: String?,
    currentSessionId: String,
    currentOnly: Boolean,
): Boolean = !currentOnly || (currentSessionId.isNotBlank() && targetSessionId == currentSessionId)

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
    initialSelectedTaskId: String? = null,
    entryRequest: Int = 0,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val canPlan = harnessState.usageMode == LocalUsageMode.CHAT &&
        !harnessState.groupChat.enabled &&
        harnessState.sessionId.isNotBlank()
    val tasks = remember(state.tasks) {
        sortChatAutomationTasks(state.tasks.filter { it.mode == AutomationMode.CHAT })
    }
    var draft by rememberSaveable { mutableStateOf("") }
    // This filter is a view concern, not an automation policy; ALL always stays available.
    var currentSessionOnly by com.labteto.dshmobile.ui.components.rememberNavigationEntryState(
        false, entryRequest, harnessState.sessionId,
    )
    val currentSessionEvents = tasks.count { it.targetSessionId == harnessState.sessionId }
    val listState = rememberLazyListState()
    val visibleTasks = tasks.filter {
        chatAutomationInSession(it.targetSessionId, harnessState.sessionId, currentSessionOnly)
    }
    var locatedEntry by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(initialSelectedTaskId, visibleTasks, entryRequest) {
        if (locatedEntry == entryRequest || initialSelectedTaskId == null) return@LaunchedEffect
        val index = visibleTasks.indexOfFirst { it.id == initialSelectedTaskId }
        if (index >= 0) {
            listState.scrollToItem(1 +
                (if (tasks.isNotEmpty() && harnessState.sessionId.isNotBlank()) 1 else 0) + index)
            locatedEntry = entryRequest
        }
    }
    var editingTaskId by com.labteto.dshmobile.ui.components.rememberNavigationEntryState<String?>(null, entryRequest)
    var policyTaskId by com.labteto.dshmobile.ui.components.rememberNavigationEntryState<String?>(null, entryRequest)
    var observedSaveRevision by remember { mutableLongStateOf(state.saveRevision) }
    val planMention = stringResource(R.string.tasks_chat_plan_mention)

    BackHandler(enabled = handleRootSystemBack, onBack = onClose)
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
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) {
            Box(Modifier.fillMaxWidth()) {
                SyTopBar(
                    title = stringResource(R.string.tasks_chat_title),
                    subtitle = stringResource(R.string.tasks_chat_subtitle),
                    onBack = onClose,
                    backContentDescription = stringResource(R.string.common_back),
                    largeTitle = true,
                    backIcon = FeatherIcons.X,
                )
            }
            TaskManagementFeedback(state, onRefresh = { viewModel.refresh() })
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
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
                if (tasks.isNotEmpty() && harnessState.sessionId.isNotBlank()) {
                    item("event-session-filter") {
                        SySegmented(
                            segments = listOf(
                                SySegment("all", stringResource(R.string.tasks_chat_filter_all, tasks.size)),
                                SySegment(
                                    "current",
                                    stringResource(R.string.tasks_chat_filter_current, currentSessionEvents),
                                ),
                            ),
                            selectedKey = if (currentSessionOnly) "current" else "all",
                            onSelect = { currentSessionOnly = it == "current" },
                            role = Role.Tab,
                            hapticOnChange = true,
                            stretch = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapXs),
                        )
                    }
                }
                if (visibleTasks.isEmpty()) {
                    item("events-empty") {
                        AutomationCardSurface(
                            modifier = Modifier.padding(horizontal = SyMetric.gapSection),
                        ) {
                            Text(
                                stringResource(
                                    if (tasks.isEmpty()) R.string.tasks_chat_events_empty
                                    else R.string.tasks_chat_filter_empty,
                                ),
                                style = DsType.std14.withReadingWeight(),
                                color = colors.textSecondary,
                            )
                        }
                    }
                } else {
                    items(visibleTasks, key = AutomationTask::id) { task ->
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
                            enabled = task.id !in state.busyIds,
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

            }

            state.plannerError?.let {
                Text(
                    stringResource(it.messageRes()),
                    modifier = Modifier.padding(horizontal = SyMetric.gapSection),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.dangerInk,
                )
            }
            SyConversationComposer {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SyPill(text = stringResource(R.string.tasks_chat_plan_mention))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                ) {
                    SyComposerField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = stringResource(R.string.tasks_chat_plan_placeholder),
                        modifier = Modifier.weight(1f),
                        enabled = canPlan && !state.planning,
                        maxLines = 5,
                    )
                    val canSubmitPlan = canPlan && draft.isNotBlank() && !state.planning
                    SyComposerAction(
                        icon = null,
                        iconPainter = painterResource(R.drawable.ic_ui_button_send_glyph),
                        iconSize = 32.dp,
                        tint = if (canSubmitPlan) colors.onPrimary else colors.text,
                        contentDescription = stringResource(R.string.tasks_chat_plan_send),
                        onClick = { viewModel.submitChatPlan(draft, editingTaskId) },
                        enabled = canSubmitPlan,
                        containerColor = colors.primary,
                        visualSize = 32.dp,
                    )
                }
                if (state.planning) {
                    Text(
                        stringResource(R.string.tasks_chat_planning),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
                    )
                } else if (editingTaskId != null) {
                    Text(
                        stringResource(R.string.tasks_chat_editing_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.textSecondary,
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
    status: SyStatus = SyStatus.Neutral,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val borderColor = when (status) {
        SyStatus.Running -> colors.primary.copy(alpha = 0.28f)
        SyStatus.Warning -> colors.warning.copy(alpha = 0.32f)
        SyStatus.Failed -> colors.dangerInk.copy(alpha = 0.28f)
        else -> colors.border
    }
    SyMaterialSurface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        tier = SyMaterialTier.L3_CARD,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(SyMetric.gapSection),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(
        Modifier.padding(horizontal = SyMetric.gapSection, vertical = SyMetric.gapXs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = DsType.base16Strong.withReadingWeight(), color = colors.text)
        Text(subtitle, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
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
    enabled: Boolean = true,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val visualStatus = chatAutomationVisualStatus(task)
    var confirmDelete by remember { mutableStateOf(false) }
    AutomationCardSurface(
        modifier = Modifier.padding(horizontal = SyMetric.gapSection),
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
                color = colors.textSecondary,
            )
            SyStatusPill(
                state = visualStatus.dsStatus(),
                label = visualStatus.label(),
            )
        }
        Text(
            task.prompt,
            style = DsType.std14.withReadingWeight(),
            color = colors.text,
            maxLines = 3,
        )
        Text(
            scheduleDescription,
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (task.recurringMinutes != null ||
                task.scheduleType == AutomationScheduleType.SILENCE ||
                task.scheduleType == AutomationScheduleType.WINDOW
            ) {
                SyButton(
                    text = stringResource(
                        if (task.status == AutomationStatus.PAUSED) R.string.tasks_resume else R.string.tasks_pause,
                    ),
                    onClick = if (task.status == AutomationStatus.PAUSED) onResume else onPause,
                    enabled = enabled,
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                )
            }
            if (task.targetSessionId == currentSessionId) {
                if (
                    task.recurringMinutes != null ||
                    task.scheduleType == AutomationScheduleType.SILENCE ||
                    task.scheduleType == AutomationScheduleType.WINDOW
                ) {
                    SyButton(
                        text = stringResource(R.string.tasks_chat_policy_action),
                        onClick = onPolicy,
                        variant = SyButtonVariant.Ghost,
                        size = SyButtonSize.Small,
                    )
                }
                SyButton(
                    text = stringResource(R.string.tasks_edit),
                    onClick = onEdit,
                    variant = SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                )
            } else {
                task.targetSessionId?.let { sessionId ->
                    SyButton(
                        text = stringResource(R.string.tasks_chat_open_conversation),
                        onClick = { onOpenSession(sessionId) },
                        variant = SyButtonVariant.Ghost,
                        size = SyButtonSize.Small,
                    )
                }
            }
            SyButton(
                text = stringResource(R.string.tasks_delete_confirm),
                onClick = { confirmDelete = true },
                enabled = enabled,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
        }
    }
    if (task.targetSessionId == currentSessionId) {
        SyButton(
            text = stringResource(R.string.tasks_chat_optimize),
            onClick = onOptimize,
            variant = SyButtonVariant.Ghost,
            size = SyButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (confirmDelete) {
        SyDialog(
            title = stringResource(R.string.tasks_delete_confirm_title),
            onDismiss = { confirmDelete = false },
        ) {
            Text(
                stringResource(R.string.tasks_chat_delete_confirm_body),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmDelete = false },
                    variant = SyButtonVariant.Ghost,
                )
                SyButton(
                    text = stringResource(R.string.tasks_delete_confirm),
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    variant = SyButtonVariant.Danger,
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
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
    SyResponsiveSheet(
        title = stringResource(R.string.tasks_chat_policy_title),
        subtitle = stringResource(R.string.tasks_chat_policy_subtitle),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
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
                    color = colors.text,
                )
                Text(
                    stringResource(
                        R.string.tasks_chat_policy_quiet_hours_value,
                        formatMinute(quietStartMinute),
                        formatMinute(quietEndMinute),
                    ),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
            }
            SySwitchControl(
                checked = quietEnabled,
                onCheckedChange = { quietEnabled = it },
            )
        }
        if (quietEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyButton(
                    text = stringResource(
                        R.string.tasks_chat_quiet_start,
                        formatMinute(quietStartMinute),
                    ),
                    onClick = {
                        editingQuietStart = true
                    },
                    modifier = Modifier.weight(1f),
                    variant = SyButtonVariant.Outline,
                    size = SyButtonSize.Small,
                )
                SyButton(
                    text = stringResource(
                        R.string.tasks_chat_quiet_end,
                        formatMinute(quietEndMinute),
                    ),
                    onClick = {
                        editingQuietStart = false
                    },
                    modifier = Modifier.weight(1f),
                    variant = SyButtonVariant.Outline,
                    size = SyButtonSize.Small,
                )
            }
        }

        Text(
            stringResource(R.string.tasks_chat_policy_min_gap),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
        gapOptions.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                row.forEach { minutes ->
                    SyButton(
                        text = stringResource(R.string.tasks_chat_gap_hours, minutes / 60L),
                        onClick = { minGapMinutes = minutes },
                        modifier = Modifier.weight(1f),
                        variant = if (minGapMinutes == minutes) {
                            SyButtonVariant.Info
                        } else {
                            SyButtonVariant.Outline
                        },
                        size = SyButtonSize.Small,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.tasks_chat_policy_min_gap_hint),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
        )

        Text(
            stringResource(R.string.tasks_chat_max_unanswered),
            style = DsType.small13Strong.withReadingWeight(),
            color = colors.text,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        ) {
            (1..5).forEach { count ->
                SyButton(
                    text = count.toString(),
                    onClick = { maxUnanswered = count },
                    modifier = Modifier.weight(1f),
                    variant = if (maxUnanswered == count) {
                        SyButtonVariant.Info
                    } else {
                        SyButtonVariant.Outline
                    },
                    size = SyButtonSize.Small,
                )
            }
        }
        Text(
            stringResource(R.string.tasks_chat_max_unanswered_hint, maxUnanswered),
            style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary,
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
                    color = colors.text,
                )
                Text(
                    stringResource(R.string.tasks_notify_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
            }
            SySwitchControl(
                checked = notify,
                onCheckedChange = { notify = it },
            )
        }

        }
        saveError?.let {
            Text(
                it,
                style = DsType.caption11.withReadingWeight(),
                color = colors.dangerInk,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SyButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
            SyButton(
                text = stringResource(R.string.tasks_save),
                onClick = {
                    viewModel.saveChatPolicyAsync(
                        task = task,
                        quietHoursEnabled = quietEnabled,
                        quietStartHour = quietStartMinute / 60,
                        quietStartMinute = quietStartMinute % 60,
                        quietEndHour = quietEndMinute / 60,
                        quietEndMinute = quietEndMinute % 60,
                        proactiveMinGapMinutes = minGapMinutes,
                        proactiveMaxUnanswered = maxUnanswered,
                        notify = notify,
                    ) { saved ->
                        if (saved) onDismiss() else saveError = saveFailedMessage
                    }
                },
                size = SyButtonSize.Small,
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

private fun ChatAutomationVisualStatus.dsStatus(): SyStatus = when (this) {
    ChatAutomationVisualStatus.RUNNING -> SyStatus.Running
    ChatAutomationVisualStatus.COMPLETED -> SyStatus.Done
    ChatAutomationVisualStatus.BLOCKED -> SyStatus.Warning
    ChatAutomationVisualStatus.FAILED -> SyStatus.Failed
    else -> SyStatus.Neutral
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
    PlannerUiError.SESSION_CHANGED -> R.string.tasks_chat_session_changed
    PlannerUiError.SAVE_FAILED -> R.string.tasks_chat_save_failed
    PlannerUiError.PLAN_FAILED -> R.string.tasks_chat_plan_failed
}

private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private fun shortTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
