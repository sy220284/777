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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

data class TasksUiState(
    val tasks: List<AutomationTask> = emptyList(),
    val message: String? = null,
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
        _state.value = _state.value.copy(tasks = scheduler.list(), message = null)
    }

    fun cancel(id: String) {
        val removed = scheduler.cancelTask(id)
        _state.value = TasksUiState(
            tasks = scheduler.list(),
            message = if (removed) "任务已取消" else "任务已经不存在",
        )
    }
}

@Composable
fun TasksScreen(
    onClose: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
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
                    contentDescription = "返回",
                    onClick = onClose,
                    containerColor = colors.bgLayer1,
                    shadowElevation = 3.dp,
                )
                Column(Modifier.weight(1f).padding(horizontal = DsSpacing.medium)) {
                    Text("任务", style = DsType.large20, color = colors.labelPrimary)
                    Text("查看计划任务、周期任务和最近执行结果", style = DsType.caption11, color = colors.labelTertiary)
                }
                DsIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新任务",
                    onClick = viewModel::refresh,
                    containerColor = colors.bgLayer1,
                )
            }

            if (state.tasks.isEmpty()) {
                EmptyHero(
                    headline = "暂无后台任务",
                    subtitle = "直接在对话里告诉智能体“明天提醒我…”或“每周执行…”，任务会出现在这里。",
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

            state.message?.let {
                Text(it, style = DsType.small13, color = colors.labelSecondary)
            }
        }
    }
}

@Composable
private fun TaskCard(task: AutomationTask, onCancel: () -> Unit) {
    val colors = DsTheme.colors
    DsGroupCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(taskStatus(task.status))
            Column(Modifier.weight(1f)) {
                Text(task.id, style = DsType.std14Strong, color = colors.labelPrimary)
                Text(
                    if (task.recurringMinutes == null) "一次性任务" else "每 \${task.recurringMinutes} 分钟",
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            DsButton(
                text = "取消",
                onClick = onCancel,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }
        Text(task.prompt, style = DsType.small13, color = colors.labelSecondary)
        Text(
            "下次执行：\${formatTime(task.nextRunAt)}",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        task.lastResult?.takeIf(String::isNotBlank)?.let {
            Text("最近结果：\${it.take(240)}", style = DsType.caption11, color = colors.labelSecondary)
        }
        task.lastError?.takeIf(String::isNotBlank)?.let {
            Text("最近状态：\${it.take(240)}", style = DsType.caption11, color = colors.error)
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
