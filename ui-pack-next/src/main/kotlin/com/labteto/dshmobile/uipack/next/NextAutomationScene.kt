package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.automation.AutomationMode
import com.labteto.dshmobile.automation.AutomationScheduleType
import com.labteto.dshmobile.automation.AutomationStatus
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each operation is delegated to the existing Scheduler Owner; this screen owns no schedules. */
@Composable
internal fun NextAutomationScene(
    owner: AutomationUiPort,
    onBack: () -> Unit,
    openEditor: Boolean = false,
    onlyChat: Boolean = false,
) {
    var snapshot by remember(owner) { mutableStateOf<UiAutomationSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AutomationTask?>(null) }
    var deleting by remember { mutableStateOf<AutomationTask?>(null) }
    var editor by rememberSaveable { mutableStateOf(openEditor) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("60") }
    var mode by rememberSaveable { mutableStateOf(if (onlyChat) AutomationMode.CHAT else AutomationMode.WORK) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(owner) {
        try { owner.snapshots.collect { snapshot = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "任务列表读取失败" }
    }

    fun dispatch(command: AutomationUiCommand) {
        if (busy) return
        busy = true
        coroutineScope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) { owner.submit(command) }
                error = when (receipt) {
                    is AutomationUiReceipt.Applied -> {
                        editing = null
                        editor = false
                        prompt = ""
                        "操作已提交给任务管理器；以最新任务状态为准"
                    }
                    is AutomationUiReceipt.Rejected -> "操作未执行：${receipt.reason}"
                    is AutomationUiReceipt.Failed -> receipt.message ?: "操作未完成"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作未完成" }
            finally { busy = false }
        }
    }

    fun beginEdit(task: AutomationTask) {
        editing = task
        prompt = task.prompt
        minutes = ((task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(60_000L) / 60_000L).toString()
        mode = task.mode
        editor = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("任务管理", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = snapshot
        if (current == null) {
            Text("正在读取实际任务…")
        } else {
            if (!editor) {
                Button(onClick = {
                    editing = null
                    prompt = ""
                    minutes = "60"
                    editor = true
                }) { Text("新建任务") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tasks = current.tasks.filter { !onlyChat || it.mode == AutomationMode.CHAT }
                    items(tasks, key = { it.id }) { task ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(task.prompt, style = MaterialTheme.typography.titleSmall)
                                Text("${task.mode} · ${task.status} · ${task.scheduleType}")
                                Text("下次执行：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(task.nextRunAt))}")
                                task.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                task.lastResult?.let { Text(it, maxLines = 3) }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { beginEdit(task) }, enabled = !busy) { Text("编辑") }
                                    TextButton(onClick = { dispatch(if (task.status == AutomationStatus.PAUSED)
                                        AutomationUiCommand.Resume(task.id) else AutomationUiCommand.Pause(task.id)) },
                                        enabled = !busy) {
                                        Text(if (task.status == AutomationStatus.PAUSED) "恢复" else "暂停")
                                    }
                                    TextButton(onClick = { dispatch(AutomationUiCommand.RunNow(task.id)) },
                                        enabled = !busy) { Text("执行") }
                                    TextButton(onClick = { deleting = task }, enabled = !busy) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            } else {
                val target = editing
                Text(if (target == null) "新建任务" else "编辑任务", style = MaterialTheme.typography.titleMedium)
                if (target == null && !onlyChat) {
                    Row {
                        FilterChip(selected = mode == AutomationMode.WORK, onClick = { mode = AutomationMode.WORK },
                            label = { Text("工作") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = mode == AutomationMode.CHAT, onClick = { mode = AutomationMode.CHAT },
                            label = { Text("人物互动") })
                    }
                }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("任务内容") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(8) },
                    label = { Text("距离下一次执行（分钟）") }, modifier = Modifier.fillMaxWidth())
                val interval = minutes.toLongOrNull()?.takeIf { it in 1..525600 }
                val canCreateChat = current.context.canPlanChat && current.context.identity != null
                if (mode == AutomationMode.CHAT && target == null && !canCreateChat) {
                    Text("人物互动仅支持当前有效的单人聊天会话")
                }
                Button(onClick = {
                    val firstRun = System.currentTimeMillis() + (interval ?: 1L) * 60_000L
                    val command = if (target == null) {
                        AutomationUiCommand.Create(
                            if (mode == AutomationMode.CHAT) current.context.identity else null,
                            UiAutomationSchedule(prompt.trim(), firstRun, null, mode, AutomationScheduleType.ONCE))
                    } else {
                        AutomationUiCommand.Update(target.id, target.scheduleGeneration,
                            UiAutomationSchedule(prompt.trim(), firstRun, target.recurringMinutes,
                                target.mode, target.scheduleType, target.silenceMinutes,
                                target.windowStartMinuteOfDay, target.windowEndMinuteOfDay,
                                target.quietHoursEnabled, target.quietStartHour, target.quietStartMinute,
                                target.quietEndHour, target.quietEndMinute,
                                target.proactiveMinGapMinutes, target.proactiveMaxUnanswered, target.notify))
                    }
                    dispatch(command)
                }, enabled = !busy && prompt.isNotBlank() && interval != null &&
                    (target != null || mode != AutomationMode.CHAT || canCreateChat)) {
                    Text(if (target == null) "保存新任务" else "保存修改")
                }
                TextButton(onClick = { editor = false; editing = null }) { Text("取消") }
            }
        }
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("确认删除任务") },
            text = { Text(target.prompt) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                dispatch(AutomationUiCommand.Delete(target.id))
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
