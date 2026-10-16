package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.components.SySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The full typed document contract, not a model-authored HTML/WebView.
 * All actions, state and deletion remain with the original UiDocumentUiPort owner.
 */
@Composable
fun V6UiDocumentScene(
    conversations: V6ConversationPorts,
    owner: UiDocumentUiPort,
    forms: UiDocumentFormUiPort,
    media: MediaUiPort?,
    workMode: Boolean,
    onBack: () -> Unit,
    generationOnly: Boolean = false,
) {
    val chat by conversations.chat.snapshots.collectAsStateWithLifecycle(
        initialValue = conversations.chat.snapshot())
    val work by conversations.work.snapshots.collectAsStateWithLifecycle(
        initialValue = conversations.work.snapshot())
    val identity = if (workMode) work.identity else chat.identity
    val sourceMessage = (if (workMode) work.messages else chat.messages).lastOrNull()
    val phase = if (workMode) work.phase else chat.phase
    val documents by owner.snapshots.collectAsStateWithLifecycle(initialValue = owner.snapshot())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (generationOnly) "生成结构化内容" else "会话结构化内容",
            style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        if (identity == null) SyNote("等待有效会话")
        if (identity != null && documents.sessionId != null &&
            documents.sessionId != identity.sessionId) SyNote("文档仍在切换，暂不展示旧会话内容")
        documents.error?.let { SyNote(it) }
        if (documents.generating) SyNote("正在生成文档，以原 Owner 的实际发布结果为准")
        if (generationOnly) {
            V6DocumentGeneration(identity, sourceMessage, workMode,
                phase == UiExecutionPhase.IDLE, owner)
        } else if (identity != null && documents.sessionId == identity.sessionId) {
            val present = documents.documents.filter { it.document.source.sessionId == identity.sessionId }
            if (present.isEmpty()) SyNote("此会话尚无结构化文档")
            present.forEach { item ->
                androidx.compose.runtime.key(item.document.documentId, item.document.revision) {
                    V6DocumentCard(item, identity, owner, forms, media,
                        identityIsCurrent = {
                            (if (workMode) conversations.work.snapshot().identity
                            else conversations.chat.snapshot().identity) == identity
                        })
                }
            }
        }
    }
}

/** Every action gets the Owner's actual receipt, never a fabricated success. */
internal fun describeV6DocumentReceipt(receipt: UiDocumentReceipt): String = when (receipt) {
    UiDocumentReceipt.AdditionalRequestConsentRequired -> "需要明确同意额外模型调用"
    is UiDocumentReceipt.Published -> "文档发布已受理：" + receipt.documentId
    UiDocumentReceipt.Deleted -> "文档已从当前会话删除"
    UiDocumentReceipt.DraftPrepared -> "内容已写入草稿，请在 Composer 检查后手动发送"
    UiDocumentReceipt.NavigationRequested -> "已请求由 Host 导航"
    is UiDocumentReceipt.OwnerReceipt -> describeV6ConversationReceipt(receipt.receipt)
    is UiDocumentReceipt.Rejected -> "操作被拒绝：" + receipt.reason.name
    is UiDocumentReceipt.InvalidFields -> "请检查字段：" + receipt.fieldIds.joinToString()
    is UiDocumentReceipt.Fallback -> "无法使用交互文档：" + receipt.reason + "\n" + receipt.text
    is UiDocumentReceipt.Failed -> receipt.message ?: "文档操作失败"
}

/**
 * Identity, revisions and exact field values are frozen in a pending action preview.
 * The owner still validates every identity, field and permission at dispatch time.
 */
@Composable
internal fun V6DocumentCard(
    snapshot: UiDocumentSnapshot,
    identity: UiSessionIdentity,
    owner: UiDocumentUiPort,
    forms: UiDocumentFormUiPort,
    media: MediaUiPort?,
    identityIsCurrent: () -> Boolean,
) {
    val doc = snapshot.document
    val key = remember(doc.documentId, doc.revision, doc.source.sessionId) {
        UiDocumentFormKey(doc.source.sessionId, doc.documentId, doc.revision)
    }
    val persisted by forms.values.collectAsStateWithLifecycle()
    val values = remember(doc, persisted) { doc.initialFields() + persisted[key].orEmpty() }
    var feedback by remember(doc.documentId, doc.revision) { mutableStateOf<String?>(null) }
    var pendingConfirm by remember(doc.documentId, doc.revision) {
        mutableStateOf<Pair<UiDocumentAction, UiDocumentActionRequest>?>(null)
    }
    var confirmDelete by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var deleting by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    var editingText by remember(doc.documentId, doc.revision) { mutableStateOf<UiDocumentNode.Text?>(null) }
    var editDraft by remember(doc.documentId, doc.revision) { mutableStateOf("") }
    var editBusy by remember(doc.documentId, doc.revision) { mutableStateOf(false) }
    val textNodes = remember(doc) {
        val collected = mutableListOf<UiDocumentNode.Text>()
        fun collect(node: UiDocumentNode) {
            when (node) {
                is UiDocumentNode.Text -> collected += node
                is UiDocumentNode.Column -> node.children.forEach(::collect)
                is UiDocumentNode.Row -> node.children.forEach(::collect)
                is UiDocumentNode.Disclosure -> node.children.forEach(::collect)
                else -> Unit
            }
        }
        doc.nodes.forEach(::collect)
        collected.toList()
    }
    val scope = rememberCoroutineScope()
    val validIdentity = snapshot.recoveryNotice == null &&
        identity.sessionId == doc.source.sessionId &&
        identity.contextGeneration == doc.source.contextGeneration &&
        identity.profileId == doc.source.profileId &&
        identity.projectId == doc.source.projectId

    fun dispatch(request: UiDocumentActionRequest) {
        if (!validIdentity || !identityIsCurrent()) {
            feedback = "会话上下文已改变，请刷新文档后重试"
            return
        }
        try { feedback = describeV6DocumentReceipt(owner.submit(identity, request)) }
        catch (failure: Exception) { feedback = failure.message ?: "文档操作失败" }
    }
    fun action(actionId: String) {
        val action = doc.actions.firstOrNull { it.id == actionId }
        if (!validIdentity || action == null || snapshot.actions[actionId]?.available != true ||
            !identityIsCurrent()) {
            feedback = "操作已失效，文档不再有当前会话的权限"
            return
        }
        val request = UiDocumentActionRequest(doc.documentId, doc.revision, doc.source,
            actionId, values.toMap())
        if ((action is UiDocumentAction.SendUserInput && action.submit) ||
            (action is UiDocumentAction.AnswerApproval && action.approved)) {
            pendingConfirm = action to request
        } else dispatch(request)
    }

    SyGlassCard(Modifier.fillMaxWidth()) {
        Text(doc.title, style = MaterialTheme.typography.titleMedium)
        val provenance = when (snapshot.provenance.origin) {
            UiDocumentOrigin.LOCAL -> if (snapshot.provenance.model == "user-edited")
                "用户编辑版本（内容未经业务核验）" else "本地真实数据生成"
            UiDocumentOrigin.MODEL -> "模型生成：" + snapshot.provenance.model
            UiDocumentOrigin.UNKNOWN -> "来源信息不完整"
        }
        Text(provenance, style = MaterialTheme.typography.labelSmall)
        if (!snapshot.provenance.usageReported && snapshot.provenance.origin == UiDocumentOrigin.MODEL)
            SyNote("本次模型额度信息未报告")
        snapshot.recoveryNotice?.let {
            SyNote("历史文档部分恢复失败，交互已禁用：" + it)
        }
        doc.nodes.forEach { node ->
            androidx.compose.runtime.key(node.id) {
                V6DocumentNode(node, values, snapshot, validIdentity,
                    media = media, update = { id, value -> forms.update(key, id, value) },
                    submit = ::action)
            }
        }
        if (validIdentity && textNodes.isNotEmpty() &&
            doc.actions.none { it is UiDocumentAction.AnswerApproval } &&
            !doc.documentId.startsWith("work-live:")) {
            SyButton("编辑文档文本", {
                if (identityIsCurrent()) {
                    editingText = textNodes.first()
                    editDraft = textNodes.first().text
                }
            }, enabled = !editBusy && !deleting, style = SyButtonStyle.SECONDARY)
        }
        if (validIdentity) SyButton("删除这份文档", { confirmDelete = true },
            style = SyButtonStyle.GHOST, enabled = !deleting)
        feedback?.let { SyNote(it) }
    }

    pendingConfirm?.let { (action, request) ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(if (action is UiDocumentAction.AnswerApproval) "确认批准当前工具调用" else "确认发送内容") },
            text = {
                Text(when (action) {
                    is UiDocumentAction.SendUserInput -> doc.preparedInput(action, request.fields)
                    is UiDocumentAction.AnswerApproval -> "仅批准待办请求：" + action.pendingId
                    else -> "确认执行"
                })
            },
            confirmButton = { TextButton(onClick = {
                pendingConfirm = null
                dispatch(request.copy(confirmed = true))
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("取消") } },
        )
    }

    editingText?.let { currentText ->
        AlertDialog(
            onDismissRequest = { if (!editBusy) editingText = null },
            title = { Text("编辑此文档的显示文本") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (textNodes.size > 1) {
                        Text("选择要编辑的文本节点")
                        textNodes.forEach { node ->
                            TextButton(onClick = {
                                editingText = node
                                editDraft = node.text
                            }, enabled = !editBusy) {
                                Text(if (node.id == currentText.id) "✓ " + node.id else node.id)
                            }
                        }
                    }
                    SyInput(editDraft, { editDraft = it.take(16_384) },
                        label = "文本内容（仅影响文档显示）", singleLine = false,
                        enabled = !editBusy)
                    Text("最多 16,384 字符；更改保留为新的用户编辑版本")
                }
            },
            confirmButton = { TextButton(onClick = {
                if (!validIdentity || !identityIsCurrent() || editBusy) {
                    feedback = "会话已变化，请重新打开文档"
                    return@TextButton
                }
                editBusy = true
                val command = EditUiDocumentTextCommand(identity, doc.documentId, doc.revision,
                    doc.source, currentText.id, editDraft)
                scope.launch {
                    try {
                        val receipt = owner.replaceText(command)
                        if (identityIsCurrent()) {
                            feedback = describeV6DocumentReceipt(receipt)
                            if (receipt is UiDocumentReceipt.Published) editingText = null
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (identityIsCurrent()) feedback = failure.message ?: "文档编辑保存失败"
                    } finally { editBusy = false }
                }
            }, enabled = !editBusy) { Text("保存新版本") } },
            dismissButton = { TextButton(onClick = { editingText = null },
                enabled = !editBusy) { Text("取消") } },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("确认删除文档") },
        text = { Text("只删除该版本的文档，不删除所属聊天消息。") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            if (!identityIsCurrent() || !validIdentity) {
                confirmDelete = false
                feedback = "会话已切换，未执行删除"
            } else {
                confirmDelete = false
                deleting = true
                scope.launch {
                    try {
                        val result = owner.delete(DeleteUiDocumentCommand(identity,
                            doc.documentId, doc.revision, doc.source))
                        if (!identityIsCurrent()) return@launch
                        feedback = describeV6DocumentReceipt(result)
                        if (result == UiDocumentReceipt.Deleted) forms.forgetDocument(key)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (identityIsCurrent()) feedback = failure.message ?: "删除失败"
                    } finally { deleting = false }
                }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun V6DocumentNode(
    node: UiDocumentNode,
    values: Map<String, String>,
    snapshot: UiDocumentSnapshot,
    active: Boolean,
    media: MediaUiPort?,
    update: (String, String) -> Unit,
    submit: (String) -> Unit,
) {
    when (node) {
        is UiDocumentNode.Text -> Text(node.text)
        is UiDocumentNode.Media -> V6DocumentMediaNode(node, snapshot.document.source.sessionId, media)
        is UiDocumentNode.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var filter by rememberSaveable(snapshot.document.documentId,
                snapshot.document.revision, node.id) { mutableStateOf("") }
            SyInput(filter, { filter = it }, label = "筛选表格")
            val visible = node.rows.filter { row ->
                filter.isBlank() || row.any { it.contains(filter, ignoreCase = true) }
            }
            if (visible.isEmpty()) SyNote("没有匹配的数据")
            visible.forEach { row ->
                SyGlassCard(Modifier.fillMaxWidth()) {
                    node.headers.zip(row).forEach { (header, value) -> Text("$header：$value") }
                }
            }
        }
        is UiDocumentNode.BarChart -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.title, style = MaterialTheme.typography.titleSmall)
            val max = node.points.maxOfOrNull { it.value } ?: 0.0
            node.points.forEach { point ->
                Text("${point.label}：${point.value} ${node.unit}")
                LinearProgressIndicator(
                    progress = { if (max > 0.0) (point.value / max).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth())
            }
        }
        is UiDocumentNode.LineChart -> V6LineChart(node)
        is UiDocumentNode.BoundText -> {
            val value = when (val bound = snapshot.bindings[node.binding]) {
                is UiDocumentBoundValue.Text -> bound.value
                is UiDocumentBoundValue.Count -> bound.value.toString()
                is UiDocumentBoundValue.Phase -> when (bound.value) {
                    UiExecutionPhase.IDLE -> "待命"
                    UiExecutionPhase.RUNNING -> "执行中"
                    UiExecutionPhase.WAITING_FOR_USER -> "等待用户操作"
                }
                else -> "历史状态不可用"
            }
            Text(node.label + "：" + value)
        }
        is UiDocumentNode.Column -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                androidx.compose.runtime.key(child.id) {
                    V6DocumentNode(child, values, snapshot, active, media, update, submit)
                }
            }
        }
        is UiDocumentNode.Row -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                androidx.compose.runtime.key(child.id) {
                    V6DocumentNode(child, values, snapshot, active, media, update, submit)
                }
            }
        }
        is UiDocumentNode.Field -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val limit = if (node.multiline) MAX_UI_DOCUMENT_MULTILINE_CHARS
                else MAX_UI_DOCUMENT_SINGLE_LINE_CHARS
            SyInput(values[node.id].orEmpty(),
                { update(node.id, it.take(limit)) }, label = node.label,
                enabled = active, singleLine = !node.multiline)
            if (values[node.id].orEmpty().length >= limit)
                SyNote("字段已达到 $limit 字符上限")
        }
        is UiDocumentNode.Select -> Column {
            Text(node.label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                node.options.forEach { option ->
                    SyButton(if (values[node.id] == option) "✓ $option" else option,
                        { update(node.id, option) }, enabled = active,
                        style = SyButtonStyle.GHOST, compact = true)
                }
            }
        }
        is UiDocumentNode.Switch -> SySwitch(node.label, values[node.id] == "true",
            { update(node.id, it.toString()) }, enabled = active)
        is UiDocumentNode.Button -> SyButton(node.label, { submit(node.actionId) },
            enabled = active && snapshot.actions[node.actionId]?.available == true)
        is UiDocumentNode.Disclosure -> {
            var expanded by remember(snapshot.document.documentId, snapshot.document.revision, node.id) {
                mutableStateOf(false)
            }
            SyButton(node.title, { expanded = !expanded },
                style = SyButtonStyle.GHOST)
            if (expanded) node.children.forEach { child ->
                androidx.compose.runtime.key(child.id) {
                    V6DocumentNode(child, values, snapshot, active, media, update, submit)
                }
            }
        }
    }
}

/** Explicit local typed templates or a separately consented additional model request. */
@Composable
private fun V6DocumentGeneration(
    identity: UiSessionIdentity?,
    message: UiMessageSnapshot?,
    workMode: Boolean,
    allowAi: Boolean,
    owner: UiDocumentUiPort,
) {
    var busy by remember(identity, message?.id) { mutableStateOf(false) }
    var feedback by remember(identity, message?.id) { mutableStateOf<String?>(null) }
    var confirmAi by remember(identity, message?.id) { mutableStateOf(false) }
    var design by rememberSaveable(identity?.sessionId, message?.id) {
        mutableStateOf(message?.text.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val target = identity
    val msg = message
    if (target == null || msg == null) {
        SyNote("请选择存在消息的有效会话")
        return
    }
    fun generateLocal(template: UiLocalDocumentTemplate) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val receipt = owner.generateLocal(GenerateLocalUiDocumentCommand(
                    target, workMode, msg.id, template))
                feedback = describeV6DocumentReceipt(receipt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "本地文档生成失败" }
            finally { busy = false }
        }
    }
    listOf(
        UiLocalDocumentTemplate.STATUS to "会话实时状态",
        UiLocalDocumentTemplate.MESSAGE to "消息内容",
        UiLocalDocumentTemplate.REPLY_FORM to "回复表单",
        UiLocalDocumentTemplate.FACTS_TABLE to "已有事实表格",
        UiLocalDocumentTemplate.MEDIA_RESULTS to "当前媒体成果",
    ).forEach { (template, title) ->
        SyButton(title, { generateLocal(template) },
            enabled = !busy, style = SyButtonStyle.SECONDARY)
    }
    if (workMode) listOf(
        UiLocalDocumentTemplate.WORK_TOOL_ACTIVITY to "工具事件记录",
        UiLocalDocumentTemplate.WORK_TOOL_COUNTS to "工具调用统计",
        UiLocalDocumentTemplate.WORK_EVIDENCE to "Work 证据来源",
        UiLocalDocumentTemplate.WORK_TOOL_RESULT to "已有工具结果",
    ).forEach { (template, title) ->
        SyButton(title, { generateLocal(template) }, enabled = !busy,
            style = SyButtonStyle.SECONDARY)
    }
    SyButton("用模型生成交互文档", { confirmAi = true }, enabled = allowAi && !busy)
    feedback?.let { SyNote(it) }
    if (confirmAi) AlertDialog(
        onDismissRequest = { if (!busy) confirmAi = false },
        title = { Text("确认额外模型请求") },
        text = { Column {
            Text("此操作会额外调用模型，可能产生额度消耗；不是本地排版。")
            SyInput(design, { design = it }, label = "希望生成的内容", singleLine = false)
        } },
        confirmButton = { TextButton(enabled = !busy && design.isNotBlank(), onClick = {
            if (busy) return@TextButton
            busy = true
            scope.launch {
                try {
                    val result = owner.generate(GenerateUiDocumentCommand(
                        target, workMode, msg.id, design, true))
                    feedback = describeV6DocumentReceipt(result)
                    confirmAi = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = failure.message ?: "模型文档生成失败" }
                finally { busy = false }
            }
        }) { Text("确认并生成") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmAi = false }) { Text("取消") } },
    )
}
