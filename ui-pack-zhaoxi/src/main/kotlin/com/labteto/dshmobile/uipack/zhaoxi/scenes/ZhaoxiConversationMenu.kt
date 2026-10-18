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

/** Attachment import receipts do not include permissioned user data in output. */
internal fun UiAttachmentReceipt.zxAttachmentFeedback(): String = when (this) {
    is UiAttachmentReceipt.Imported -> "附件已添加"
    is UiAttachmentReceipt.Rejected -> "附件未被接受：${com.labteto.dshmobile.uipack.zhaoxi.components.rejectionHint(reason)}"
    UiAttachmentReceipt.GrantRevoked -> "文件读取权限已失效，请重新选择"
    is UiAttachmentReceipt.Failed -> message?.takeIf(String::isNotBlank) ?: "附件导入失败"
}

@Composable
fun ZhaoxiConversationMenu(conv: ZhaoxiConversationPorts?, secondary: ZhaoxiSecondaryPorts?, scope: UiSceneRenderScope, hasSettings: Boolean = false, renderSlot: @Composable (UiSceneId) -> Unit = {}) {
    var selectedSlot by remember { mutableStateOf<UiSceneId?>(null) }
    var receipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
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
                if (uri != null && !importing) jobs.launch {
                    importing = true
                    try {
                        val result = attachmentOwner.import(ImportUiAttachmentCommand(target, uri))
                        when (result) {
                            is UiAttachmentReceipt.Imported -> {
                                ports.drafts.addAttachment(target.sessionId, result.attachment)
                                feedback = "附件已添加"
                            }
                            else -> feedback = result.zxAttachmentFeedback()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        feedback = failure.message ?: "附件导入失败"
                    } finally { importing = false }
                }
            }
            ZxButton("添加附件", { identity?.let(pick) }, enabled = identity != null && !importing, loading = importing)
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
            val pending = ports.work.snapshot()
            if (pending.approval != null) ZxNavRow("待审批") {
                scope.onNavigate(UiSceneId("work.approval"))
            }
            if (pending.question != null) ZxNavRow("待回答") {
                scope.onNavigate(UiSceneId("work.question"))
            }
        } else {
            if (ports.gallery != null && ports.selection != null)
                ZxNavRow("选择人物") { scope.onNavigate(UiSceneId("chat.persona_picker")) }
            if (secondary?.currentPersona != null)
                ZxNavRow("当前人物采样") { scope.onNavigate(UiSceneId("chat.character_tuning")) }
            if (ports.groups != null) {
                ZxNavRow("群成员") { scope.onNavigate(UiSceneId("chat.group_members")) }
                ZxNavRow("群公告") { scope.onNavigate(UiSceneId("chat.group_announcement")) }
            }
            if (ports.replySuggestions != null || secondary?.replySuggestions != null)
                ZxNavRow("回复建议") { scope.onNavigate(UiSceneId("chat.reply_suggestions")) }
        }
    }
    val availableSlots = buildList {
        if (conv?.documents != null && conv.forms != null)
            add("conversation.ui_document" to "交互文档")
        if (conv?.workMode() == true) {
            if (conv.workReads != null && conv.workPolicy != null)
                add("work.plan_review" to "计划审查")
            if (conv.workTeam != null) add("work.team" to "团队运行")
            if (conv.evidence != null) add("work.requirement_evidence" to "需求与证据")
            if (secondary?.tools != null) add("work.capabilities" to "能力清单")
        }
    }
    availableSlots.forEach { (id, label) ->
        ZxNavRow(label) {
            selectedSlot = if (selectedSlot?.value == id) null else UiSceneId(id)
        }
    }
    selectedSlot?.takeIf { selected -> availableSlots.any { it.first == selected.value } }?.let { id ->
        Box(Modifier.fillMaxWidth().height(340.dp)) { renderSlot(id) }
    }
    if (conv?.documents != null && conv.forms != null)
        ZxNavRow("生成文档") { scope.onNavigate(UiSceneId("conversation.ui_document_generate")) }
    if (secondary?.media != null && secondary.settings != null)
        ZxNavRow("媒体生成") { scope.onNavigate(UiSceneId("media.creation")) }
    if (secondary?.files != null)
        ZxNavRow("工作区") { scope.onNavigate(UiSceneId("route.workspace")) }
    if (hasSettings)
        ZxNavRow("设置") { scope.onNavigate(UiSceneId("route.settings")) }

}
