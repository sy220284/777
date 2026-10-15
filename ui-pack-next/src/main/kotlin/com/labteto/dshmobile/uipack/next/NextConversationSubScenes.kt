package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*

/**
 * Each Host SLOT/OVERLAY projects exactly the real Owner action it represents.
 * No V6 screen, private callback registry or fake Scene count is injected.
 */
@Composable
internal fun NextConversationSubScene(
    id: UiSceneId,
    ports: NextApplicationFeaturePorts,
    onBack: () -> Unit,
) {
    val workMode = ports.workModeUpdates?.collectAsStateWithLifecycle(
        initialValue = ports.workMode())?.value ?: ports.workMode()
    val chat by ports.chat.snapshots.collectAsStateWithLifecycle(initialValue = ports.chat.snapshot())
    val work by ports.work.snapshots.collectAsStateWithLifecycle(initialValue = ports.work.snapshot())
    val identity = if (workMode) work.identity else chat.identity
    val messages = if (workMode) work.messages else chat.messages
    val status = if (workMode) work.status else chat.status
    val phase = if (workMode) work.phase else chat.phase
    val draftValues by ports.drafts.drafts.collectAsStateWithLifecycle()
    val attachments by ports.drafts.attachments.collectAsStateWithLifecycle()
    val text = identity?.sessionId?.let(draftValues::get).orEmpty()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val selected = messages.firstOrNull { it.id == selectedId } ?: messages.lastOrNull()
    fun receiptText(result: UiCommandReceipt): String = when (result) {
        UiCommandReceipt.Accepted -> "操作已受理"
        UiCommandReceipt.Queued -> "已排队，未完成"
        UiCommandReceipt.NotAdmitted -> "操作未被受理"
        UiCommandReceipt.OutcomeUnknown -> "结果暂时未知，请查看实际消息，不要重复发送"
        is UiCommandReceipt.Rejected -> "操作被拒绝：${result.reason}"
        is UiCommandReceipt.Failed -> result.message ?: "操作失败"
        is UiCommandReceipt.MessageEdited -> "编辑已提交：${result.result}"
    }
    fun send() {
        val idNow = identity ?: return
        if (text.isBlank()) return
        val files = attachments[idNow.sessionId].orEmpty()
        val command = if (workMode)
            ports.work.submit(WorkConversationUiCommand.SendMessage(idNow, text, files))
        else ports.chat.submit(ChatUiCommand.SendMessage(idNow, text, files))
        feedback = receiptText(command)
        if (command == UiCommandReceipt.Accepted) {
            ports.drafts.updateDraft(idNow.sessionId, "")
            ports.drafts.clearAttachments(idNow.sessionId)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (id.value) {
            "conversation.header" -> "当前会话"
            "conversation.timeline" -> "消息时间线"
            "conversation.composer" -> "输入与发送"
            "conversation.ui_document" -> "结构化内容"
            "conversation.ui_document_generate" -> "生成结构化内容"
            "conversation.reply_status" -> "回复状态"
            "conversation.message_actions" -> "消息操作"
            "conversation.edit" -> "编辑消息"
            "conversation.reader" -> "阅读"
            "chat.branches" -> "回复分支"
            else -> id.value
        }, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        feedback?.let { Text(it) }
        when (id.value) {
            "conversation.header", "conversation.reply_status" -> {
                Text(if (workMode) "工作" else "人物聊天")
                Text("状态：${status.name} · 阶段：${phase.name}")
                Text("当前会话：${identity?.sessionId ?: "不可用"}")
                Text("消息数（当前窗口）：${messages.size}")
                if (workMode) {
                    Text("待发送：${work.queuedInputCount}")
                    work.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("待发送：${chat.queuedInputCount}")
                    chat.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            "conversation.timeline", "conversation.reader", "conversation.message_actions",
            "conversation.edit", "chat.branches" -> {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages.takeLast(120), key = { it.id }) { message ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${message.role.name} · ${message.createdAt}")
                                SelectionContainer { Text(message.text.take(5000)) }
                                if (id.value != "conversation.timeline") {
                                    TextButton(onClick = { selectedId = message.id }) {
                                        Text(if (selectedId == message.id) "当前选择" else "选择消息")
                                    }
                                }
                                val branch = message.branch
                                if (id.value == "chat.branches" && branch != null &&
                                    !workMode && identity != null) {
                                    Text("分支 ${branch.index + 1}/${branch.count}")
                                    Row {
                                        for (target in 0 until branch.count.coerceAtMost(32)) {
                                            TextButton(onClick = {
                                                feedback = receiptText(ports.chat.submit(
                                                    ChatUiCommand.SelectVariant(identity, message.id, target)))
                                            }, enabled = target != branch.index) { Text("${target + 1}") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (selected != null && identity != null &&
                    id.value in setOf("conversation.edit", "conversation.message_actions")) {
                    var edited by remember(selected.id) { mutableStateOf(selected.editableText ?: selected.text) }
                    if (selected.role == UiMessageRole.USER) {
                        OutlinedTextField(edited, { edited = it }, label = { Text("编辑用户消息") },
                            modifier = Modifier.fillMaxWidth())
                        Button(enabled = edited.isNotBlank(), onClick = {
                            feedback = receiptText(if (workMode) ports.work.submit(
                                WorkConversationUiCommand.EditUserMessage(identity, selected.id, edited))
                            else ports.chat.submit(ChatUiCommand.EditUserMessage(identity, selected.id, edited)))
                        }) { Text("保存并提交编辑") }
                    }
                    if (selected.role == UiMessageRole.ASSISTANT) {
                        TextButton(onClick = {
                            feedback = receiptText(if (workMode) ports.work.submit(
                                WorkConversationUiCommand.RegenerateReply(identity, selected.id))
                            else ports.chat.submit(ChatUiCommand.RegenerateReply(identity, selected.id)))
                        }) { Text("重新生成回复") }
                    }
                }
            }
            "conversation.composer" -> {
                Text("发送仍由当前会话 Owner 决定是否受理")
                OutlinedTextField(text,
                    onValueChange = { value ->
                        identity?.let { ports.drafts.updateDraft(it.sessionId, value) }
                    }, label = { Text("消息") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Text("附件：${identity?.sessionId?.let { attachments[it]?.size } ?: 0}")
                Button(onClick = { send() }, enabled = identity != null && text.isNotBlank()) { Text("发送") }
                if (identity != null) TextButton(onClick = {
                    feedback = receiptText(if (workMode)
                        ports.work.submit(WorkConversationUiCommand.Stop(identity))
                    else ports.chat.submit(ChatUiCommand.Stop(identity)))
                }) { Text("停止当前回复") }
            }
            "conversation.ui_document", "conversation.ui_document_generate" -> {
                val doc = ports.documents
                val forms = ports.forms
                if (doc == null || forms == null) Text("文档管理服务不可用")
                else {
                    val current by doc.snapshots.collectAsStateWithLifecycle(initialValue = doc.snapshot())
                    if (id.value == "conversation.ui_document_generate") {
                        val last = messages.lastOrNull()
                        if (last != null) NextDocumentGenerateButton(identity, workMode, last.id,
                            last.text, doc, allowAiDesign = phase == UiExecutionPhase.IDLE)
                    } else {
                        if (current.documents.isEmpty()) Text("本会话尚无结构化文档")
                        LazyColumn(Modifier.weight(1f)) {
                            items(current.documents, key = { it.document.documentId }) { item ->
                                NextUiDocument(item, identity, doc, forms)
                            }
                        }
                    }
                }
            }
            else -> Text("此页面没有连接的业务处理器")
        }
    }
}

/** A genuine character picker; selection is not inferred from an archive visit. */
@Composable
internal fun NextPersonaPickerScene(gallery: PersonaGalleryUiPort,
    selection: PersonaSelectionUiPort, onBack: () -> Unit,
) {
    val live by selection.snapshots.collectAsStateWithLifecycle(initialValue = selection.snapshot())
    var entries by remember { mutableStateOf(emptyList<UiPersonaGalleryEntry>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gallery) {
        try {
            when (val response = gallery.read()) {
                is UiPersonaGalleryReadReceipt.Available -> entries = response.entries
                is UiPersonaGalleryReadReceipt.Failed -> notice = response.message ?: "人物列表不可用"
            }
        } catch (failure: Exception) { notice = failure.message ?: "人物列表不可用" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择当前人物", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        notice?.let { Text(it) }
        LazyColumn {
            items(entries, key = { it.id }) { person ->
                Button(enabled = live.identity != null && live.canSelectForCurrentChat,
                    onClick = {
                        val identity = live.identity ?: return@Button
                        notice = when (val receipt = selection.submit(
                            PersonaSelectionUiCommand.SelectForCurrentChat(identity, person.id))) {
                            UiCommandReceipt.Accepted -> { onBack(); "已切换人物" }
                            is UiCommandReceipt.Rejected -> "操作未受理：${receipt.reason}"
                            is UiCommandReceipt.Failed -> receipt.message ?: "人物未切换"
                            else -> "人物切换结果未知，请检查当前会话"
                        }
                    }) { Text(person.name) }
            }
        }
    }
}
