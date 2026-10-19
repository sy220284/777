package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.components.*
import com.labteto.dshmobile.uihost.rememberUiPrivateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The undo token comes from Chat's notice projection, never reconstructed from display text. */
@Composable
internal fun ZhaoxiCorrectionNotice(notice: UiPersonaCorrectionUndo, port: PersonaCorrectionUiPort) {
    var busy by remember(notice) { mutableStateOf(false) }
    var feedback by remember(notice) { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    ZxFooterNote("已记下人物修正：${notice.correction}")
    feedback?.let { ZxFooterNote(it) }
    ZxButton("撤销这次人物修正", {
        if (!busy) {
            busy = true
            jobs.launch {
                try {
                    feedback = when (port.undo(notice)) {
                        UiPersonaCorrectionReceipt.Undone -> "修正已撤销"
                        UiPersonaCorrectionReceipt.Stale -> "人物或会话已变化，本次修正无法继续撤销"
                        is UiPersonaCorrectionReceipt.Failed -> "撤销失败，请检查人物资料后重试"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { feedback = "撤销失败，请检查人物资料后重试" }
                finally { busy = false }
            }
        }
    }, enabled = !busy, loading = busy, variant = ZxButtonVariant.Outline)
}

/** Selection is persisted by the existing invocation Owner; it grants no tool permission. */
@Composable
internal fun ZhaoxiPinnedInvocationActions(port: ComposerInvocationUiPort) {
    val pinned by port.pinned.collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<UiCommandReceipt?>(null) }
    val options by produceState<List<UiInvocationOption>?>(null, port, expanded, refresh) {
        if (!expanded) return@produceState
        error = null
        value = null
        try { value = port.options() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取技能与连接器" }
    }
    ZxNavRow("技能与连接器", "已固定 ${pinned.size} 项 · 点击选择调用范围") { expanded = !expanded }
    if (expanded) {
        ZxFooterNote("固定后由现有会话执行器读取；实际调用仍遵守工具权限、审批和模型能力。")
        CommandFeedback(receipt)
        error?.let { ZxBanner(it, ZxTone.Err) }
        if (options == null && error == null) ZxFooterNote("正在读取可用项目…")
        if (options?.isEmpty() == true) ZxEmpty("暂无可选择项目", "可先到工具中心安装技能或连接服务")
        options.orEmpty().forEach { item ->
            val selected = item.ref in pinned
            if (item.available || selected) {
                ZxSwitchRow(item.label, item.description, selected) { receipt = port.toggle(item.ref) }
            } else ZxRow(item.label, item.description)
        }
        ZxButton("刷新可用项目", { refresh++ }, variant = ZxButtonVariant.Outline)
    }
}

/** Archive through Chat's existing maintenance admission; never copy persona/history in the UI. */
@Composable
internal fun ZhaoxiArchiveCurrentChat(selection: PersonaSelectionUiPort, archive: PersonaGallerySaveUiPort) {
    val snapshot by selection.snapshots.collectAsState(initial = selection.snapshot())
    val source = snapshot.identity
    var expanded by remember(source) { mutableStateOf(false) }
    var notes by rememberUiPrivateText("zhaoxi.archive.${source?.sessionId}") { "" }
    var newStory by remember(source) { mutableStateOf(false) }
    var busy by remember(source) { mutableStateOf(false) }
    var feedback by remember(source) { mutableStateOf<String?>(null) }
    val jobs = rememberCoroutineScope()
    ZxNavRow("保存当前人物与故事", "将本次聊天归档到人物图集") { expanded = !expanded }
    feedback?.let { ZxFooterNote(it) }
    if (expanded) {
        ZxFooterNote("归档当前人物、聊天历史与故事状态。运行中的回复或群聊请先结束后再保存。")
        ZxField(notes, { notes = it }, androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 80.dp),
            fieldLabel = "故事备注", placeholder = "可选：给这段故事留个备注")
        ZxSwitchRow("另存为新故事", "关闭时更新当前已绑定的故事", newStory) { newStory = it }
        ZxButton("确认保存到人物图集", {
            if (source != null && !busy) {
                val command = SavePersonaGalleryCommand(source, notes, forceNewStory = newStory)
                busy = true
                jobs.launch {
                    try {
                        when (val result = archive.save(command)) {
                            is UiPersonaGallerySaveReceipt.Saved -> {
                                feedback = if (result.sessionSync == UiStorySessionSync.DEFERRED)
                                    "故事已归档；当前会话绑定尚未同步，可到人物图集查看"
                                else "人物与故事已保存到图集"
                                expanded = false
                                notes = ""
                            }
                            UiPersonaGallerySaveReceipt.Rejected -> feedback = "当前聊天暂时无法归档，请确认会话未切换且没有运行中的回复"
                            is UiPersonaGallerySaveReceipt.Failed -> feedback = result.message ?: "保存失败，备注仍保留"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feedback = failure.message ?: "归档失败，备注仍保留" }
                    finally { busy = false }
                }
            }
        }, enabled = snapshot.canSaveCurrent && source != null && !busy, loading = busy)
        if (!snapshot.canSaveCurrent) ZxFooterNote(snapshot.unavailableReason ?: "请在空闲的单人聊天中保存")
    }
}
