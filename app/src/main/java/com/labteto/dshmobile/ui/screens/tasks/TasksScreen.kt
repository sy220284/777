package com.labteto.dshmobile.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.automation.HarnessAutomationScheduler
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsGroupCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TasksNotice { CANCELLED, MISSING }

data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val notice: TasksNotice? = null,
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val scheduler: HarnessAutomationScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(tasks = scheduler.list(), notice = null)
    }

    fun cancel(id: String) {
        val removed = scheduler.cancelTask(id)
        _state.value = TasksUiState(
            tasks = scheduler.list(),
            notice = if (removed) TasksNotice.CANCELLED else TasksNotice.MISSING,
        )
    }

    fun create(prompt: String, delayMinutes: Long, recurringMinutes: Long?): Boolean {
        if (prompt.isBlank() || delayMinutes < 0L) return false
        if (recurringMinutes != null && recurringMinutes < 15L) return false
        return runCatching {
            val id = "ui-" + System.currentTimeMillis()
            val firstRun = System.currentTimeMillis() + delayMinutes * 60_000L
            if (recurringMinutes == null) {
                scheduler.scheduleOnce(id, prompt.trim(), firstRun, notify = true)
            } else {
                scheduler.schedulePeriodic(id, prompt.trim(), recurringMinutes, firstRun, notify = true)
            }
            refresh()
        }.isSuccess
    }
}

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val createInvalidMessage = stringResource(R.string.tasks_create_invalid)
    var showCreate by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var delayMinutes by remember { mutableStateOf("0") }
    var recurringMinutes by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onClose,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
                Column(Modifier.weight(1f).padding(horizontal = DsSpacing.medium)) {
                    Text(stringResource(R.string.tasks_title), style = DsType.large20, color = colors.labelPrimary)
                    Text(stringResource(R.string.tasks_subtitle), style = DsType.caption11, color = colors.labelTertiary)
                }
                DsIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.tasks_new),
                    onClick = { showCreate = !showCreate; createError = null },
                    containerColor = colors.bgLayer1,
                )
            }

            if (showCreate) {
                DsGroupCard {
                    Text(stringResource(R.string.tasks_new), style = DsType.base16Strong, color = colors.labelPrimary)
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.tasks_prompt_label)) },
                        placeholder = { Text(stringResource(R.string.tasks_prompt_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        OutlinedTextField(
                            value = delayMinutes,
                            onValueChange = { delayMinutes = it.filter(Char::isDigit).take(8) },
                            label = { Text(stringResource(R.string.tasks_delay_minutes)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = recurringMinutes,
                            onValueChange = { recurringMinutes = it.filter(Char::isDigit).take(8) },
                            label = { Text(stringResource(R.string.tasks_repeat_interval)) },
                            supportingText = { Text(stringResource(R.string.tasks_repeat_hint)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    createError?.let {
                        Text(it, style = DsType.small13, color = colors.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DsButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = { showCreate = false; createError = null },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            text = stringResource(R.string.tasks_create),
                            onClick = {
                                val delay = delayMinutes.toLongOrNull()
                                val recurring = recurringMinutes.toLongOrNull()
                                val ok = delay != null && viewModel.create(prompt, delay, recurring)
                                if (ok) {
                                    showCreate = false
                                    prompt = ""
                                    delayMinutes = "0"
                                    recurringMinutes = ""
                                    createError = null
                                } else {
                                    createError = createInvalidMessage
                                }
                            },
                            size = DsButtonSize.Small,
                        )
                    }
                }
            }

            if (state.tasks.isEmpty() && !showCreate) {
                EmptyHero(
                    headline = stringResource(R.string.tasks_empty_title),
                    subtitle = stringResource(R.string.tasks_empty_subtitle),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    items(state.tasks, key = AutomationTask::id) { task ->
                        TaskCard(task = task, onCancel = { viewModel.cancel(task.id) })
                    }
                }
            }

            state.notice?.let { notice ->
                Text(
                    stringResource(
                        when (notice) {
                            TasksNotice.CANCELLED -> R.string.tasks_cancelled
                            TasksNotice.MISSING -> R.string.tasks_missing
                        },
                    ),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun TaskCard(task: AutomationTask, onCancel: () -> Unit) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(taskStatus(task.status))
            Column(Modifier.weight(1f)) {
                Text(
                    task.prompt.lineSequence().firstOrNull()?.trim()?.take(56).orEmpty()
                        .ifBlank { stringResource(R.string.tasks_background_task) },
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                )
                Text(
                    if (task.recurringMinutes == null) stringResource(R.string.tasks_once)
                    else stringResource(R.string.tasks_every_minutes, task.recurringMinutes),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton(
                text = stringResource(R.string.tasks_cancel),
                onClick = onCancel,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }
        Text(
            stringResource(R.string.tasks_next_run, formatTime(task.nextRunAt)),
            style = DsType.caption11,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = DsSpacing.xlarge),
        )
        task.lastError?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.tasks_last_status, it.take(160)),
                style = DsType.caption11,
                color = colors.error,
                modifier = Modifier.padding(start = DsSpacing.xlarge),
            )
        }
    }
}

private fun taskStatus(status: String): StateDotState = when (status) {
    "running", "queued" -> StateDotState.Running
    "completed", "scheduled" -> StateDotState.Done
    "blocked" -> StateDotState.Warning
    "failed" -> StateDotState.Error
    else -> StateDotState.Idle
}

private fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
