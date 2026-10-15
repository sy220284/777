package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Group editing uses Chat-issued session identities and the current Owner membership bounds.
 * The selection draft never mutates Chat until Save is pressed.
 */
@Composable
internal fun NextChatGroupScene(owner: ChatGroupUiPort, announcementOnly: Boolean, onBack: () -> Unit) {
    val current by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var selected by remember(current.identity) { mutableStateOf(current.memberIds) }
    var announcement by remember(current.identity) { mutableStateOf(current.announcement) }
    var response by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current.memberIds) { selected = current.memberIds }
    LaunchedEffect(current.announcement) { announcement = current.announcement }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (announcementOnly) "群聊公告" else "群聊成员", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        response?.let { Text(it) }
        val identity = current.identity
        if (identity == null) Text("请选择有效聊天会话")
        else if (announcementOnly) {
            OutlinedTextField(announcement, { announcement = it.take(current.maximumAnnouncementChars) },
                modifier = Modifier.fillMaxWidth(), label = { Text("公告内容") }, minLines = 3)
            Button(onClick = {
                val result = owner.submit(ChatGroupUiCommand.SaveAnnouncement(identity, announcement))
                response = when (result) {
                    UiCommandReceipt.Accepted -> "公告已提交，实际内容以群聊状态为准"
                    is UiCommandReceipt.Rejected -> "公告未保存：${result.reason}"
                    is UiCommandReceipt.Failed -> result.message ?: "公告未保存"
                    else -> "操作尚未确认"
                }
            }, enabled = !current.busy) { Text("保存公告") }
        } else {
            Text("已选 ${selected.size} 人，可选 ${current.minimumMembers}–${current.maximumMembers} 人")
            LazyColumn(Modifier.weight(1f)) {
                items(current.available, key = { it.galleryId }) { person ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = person.galleryId in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + person.galleryId else selected - person.galleryId
                            })
                        Text(person.displayName)
                    }
                }
            }
            Button(onClick = {
                val chosen = current.available.filter { it.galleryId in selected }.map { it.galleryId }
                val result = owner.submit(ChatGroupUiCommand.ConfigureMembers(identity, chosen))
                response = when (result) {
                    UiCommandReceipt.Accepted -> "成员配置已提交，请查看最新群聊状态"
                    is UiCommandReceipt.Rejected -> "成员配置未保存：${result.reason}"
                    is UiCommandReceipt.Failed -> result.message ?: "成员配置未保存"
                    else -> "配置尚未确认"
                }
            }, enabled = !current.busy && selected.size in current.minimumMembers..current.maximumMembers) {
                Text("保存成员设置")
            }
        }
    }
}

/** Team commands always carry current Owner identity and member job revision. */
@Composable
internal fun NextWorkTeamScene(owner: WorkTeamUiPort, onBack: () -> Unit) {
    val current by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    var message by rememberSaveable { mutableStateOf("") }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingStop by remember { mutableStateOf<UiWorkTeamMember?>(null) }
    var stopAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun send(command: WorkTeamUiCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val receipt = owner.submit(command)
                result = receipt.message
                if (receipt.receipt == UiCommandReceipt.Accepted) message = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { result = failure.message ?: "成员操作未完成" }
            finally { busy = false }
        }
    }
    val identity = current.identity
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Work 团队", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        current.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { Text(it) }
        if (current.rebuilding) Text("正在读取成员实际状态")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(current.members, key = { it.id + ":" + it.jobId }) { member ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(member.name, style = MaterialTheme.typography.titleMedium)
                        Text("${member.phase} · ${member.activity}")
                        member.task?.let { Text(it) }
                        member.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = {
                            if (identity != null) {
                                val output = owner.readOutput(identity, member.id, member.jobId)
                                result = output.message
                            }
                        }, enabled = identity != null && !busy) { Text("读取真实输出") }
                        TextButton(onClick = { targetId = member.id }, enabled = identity != null && !busy) {
                            Text("发送消息")
                        }
                        TextButton(onClick = { pendingStop = member }, enabled = identity != null && !busy) {
                            Text("停止成员")
                        }
                    }
                }
            }
        }
        if (identity != null && current.members.isNotEmpty()) {
            TextButton(onClick = { stopAll = true }, enabled = !busy) { Text("停止整个团队") }
        }
        val target = current.members.firstOrNull { it.id == targetId }
        if (target != null && identity != null) {
            OutlinedTextField(message, { message = it }, label = { Text("发送给 ${target.name}") },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                send(WorkTeamUiCommand.SendMessage(identity, target.id, target.jobId, message))
                targetId = null
            }, enabled = message.isNotBlank() && !busy) { Text("提交") }
        }
    }
    pendingStop?.let { target ->
        AlertDialog(onDismissRequest = { pendingStop = null }, title = { Text("停止成员？") },
            text = { Text(target.name) },
            confirmButton = { TextButton(onClick = {
                pendingStop = null
                identity?.let { send(WorkTeamUiCommand.StopMember(it, target.id, target.jobId)) }
            }) { Text("停止") } },
            dismissButton = { TextButton(onClick = { pendingStop = null }) { Text("取消") } })
    }
    if (stopAll) AlertDialog(onDismissRequest = { stopAll = false },
        title = { Text("停止整个团队？") }, text = { Text("该操作会通知所有当前成员停止工作。") },
        confirmButton = { TextButton(onClick = {
            stopAll = false
            identity?.let { send(WorkTeamUiCommand.StopTeam(it, current.members.map(UiWorkTeamMember::jobId).toSet())) }
        }) { Text("停止团队") } },
        dismissButton = { TextButton(onClick = { stopAll = false }) { Text("取消") } })
}
