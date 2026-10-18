package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.launch

@Composable
fun ZhaoxiConversationMenu(conv: ZhaoxiConversationPorts?, secondary: ZhaoxiSecondaryPorts?, scope: UiSceneRenderScope, renderSlot: @Composable (UiSceneId) -> Unit = {}) {
    var selectedSlot by remember { mutableStateOf<UiSceneId?>(null) }
    var receipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    CommandFeedback(receipt)
    feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
    secondary?.sessions?.let { sessions ->
        val snap by sessions.snapshots.collectAsState(initial = sessions.snapshot())
        ZxSwitchRow("工作模式", "切换当前会话模式", snap.usageMode == LocalUsageMode.WORK) { work ->
            receipt = sessions.submit(SessionUiCommand.SelectMode(if (work) LocalUsageMode.WORK else LocalUsageMode.CHAT))
            // Wait for the Session owner activation before Host navigation.
        }
    }
    conv?.let { ports ->
        val identity = if (ports.workMode()) ports.work.snapshot().identity else ports.chat.snapshot().identity
        val attachments by ports.drafts.attachments.collectAsState()
        identity?.let { id ->
            attachments[id.sessionId].orEmpty().forEach { attachment ->
                ZxNavRow(attachment.name, sub = "移除附件") { ports.drafts.removeAttachment(id.sessionId, attachment.relativePath) }
            }
        }
        ports.attachments?.let { attachmentOwner ->
            val pick = rememberUiAttachmentPickerFor<UiSessionIdentity>(onRejected = { feedback = it }) { target, uri ->
                if (uri != null) jobs.launch {
                    when (val result = attachmentOwner.import(ImportUiAttachmentCommand(target, uri))) {
                        is UiAttachmentReceipt.Imported -> ports.drafts.addAttachment(target.sessionId, result.attachment)
                        else -> feedback = result.toString()
                    }
                }
            }
            ZxButton("添加附件", { identity?.let(pick) }, enabled = identity != null)
        }
        if (ports.workMode()) {
            ports.workPolicy?.let { policy ->
                val snap by policy.snapshots.collectAsState(initial = policy.snapshot())
                ZxSwitchRow("计划模式", "由工作运行时保存", snap.planMode) { enabled ->
                    snap.identity?.let { receipt = policy.submit(WorkPolicyUiCommand.SetPlanMode(it, enabled)) }
                }
            }
            ports.workReads?.let { reads ->
                val snap by ports.work.snapshots.collectAsState(initial = ports.work.snapshot())
                val plan = snap.identity?.let { reads.planForUi(it.sessionId) }
                plan?.plan?.forEachIndexed { index, step -> ZxRow(step, plan.todos.getOrNull(index)?.ownerStatus.orEmpty()) }
            }
            ZxNavRow("待审批") { scope.onNavigate(UiSceneId("work.approval")) }
            ZxNavRow("待回答") { scope.onNavigate(UiSceneId("work.question")) }
        } else {
            ZxNavRow("选择人物") { scope.onNavigate(UiSceneId("chat.persona_picker")) }
            ZxNavRow("当前人物采样") { scope.onNavigate(UiSceneId("chat.character_tuning")) }
            ZxNavRow("群成员") { scope.onNavigate(UiSceneId("chat.group_members")) }
            ZxNavRow("群公告") { scope.onNavigate(UiSceneId("chat.group_announcement")) }
            ZxNavRow("回复建议") { scope.onNavigate(UiSceneId("chat.reply_suggestions")) }
        }
    }
    val slotChoices = if (conv?.workMode() == true) listOf(
        "conversation.ui_document" to "交互文档", "work.plan_review" to "计划审查", "work.team" to "团队运行",
        "work.requirement_evidence" to "需求与证据", "work.capabilities" to "能力清单",
    ) else listOf("conversation.ui_document" to "交互文档")
    slotChoices.forEach { (id, label) -> ZxNavRow(label) {
        selectedSlot = if (selectedSlot?.value == id) null else UiSceneId(id)
    } }
    selectedSlot?.let { id -> Box(Modifier.fillMaxWidth().height(340.dp)) { renderSlot(id) } }
    ZxNavRow("生成文档") { scope.onNavigate(UiSceneId("conversation.ui_document_generate")) }
    ZxNavRow("媒体生成与结果") { scope.onNavigate(UiSceneId("media.creation")) }
    ZxNavRow("工作区") { scope.onNavigate(UiSceneId("route.workspace")) }
    ZxNavRow("设置") { scope.onNavigate(UiSceneId("route.settings")) }
}
