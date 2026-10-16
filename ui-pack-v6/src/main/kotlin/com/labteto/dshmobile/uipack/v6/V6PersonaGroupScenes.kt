package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.ChatGroupUiCommand
import com.labteto.dshmobile.uicontract.ChatGroupUiPort
import com.labteto.dshmobile.uicontract.PersonaGalleryUiPort
import com.labteto.dshmobile.uicontract.PersonaSelectionUiCommand
import com.labteto.dshmobile.uicontract.PersonaSelectionUiPort
import com.labteto.dshmobile.uicontract.UiChatGroupManagementSnapshot
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiPersonaGalleryEntry
import com.labteto.dshmobile.uicontract.UiPersonaGalleryReadReceipt
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.components.SySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The owner retains the actual gallery and selection identities; this is a UI-only read. */
@Composable
fun V6PersonaPickerScene(
    gallery: PersonaGalleryUiPort,
    selection: PersonaSelectionUiPort,
    onBack: () -> Unit,
) {
    val selectionState by selection.snapshots.collectAsStateWithLifecycle(
        initialValue = remember(selection) { selection.snapshot() })
    var entries by remember(gallery) { mutableStateOf<List<UiPersonaGalleryEntry>>(emptyList()) }
    var loading by remember(gallery) { mutableStateOf(true) }
    var error by remember(gallery) { mutableStateOf<String?>(null) }
    var filter by remember(gallery) { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(gallery) {
        loading = true
        try {
            when (val result = withContext(Dispatchers.IO) { gallery.read() }) {
                is UiPersonaGalleryReadReceipt.Available -> { entries = result.entries; error = null }
                is UiPersonaGalleryReadReceipt.Failed -> error = result.message ?: "人物资料读取失败"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "人物资料读取失败" }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("选择当前人物", style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        if (loading) SyNote("正在读取真实人物档案")
        error?.let { SyNote(it) }
        selectionState.unavailableReason?.let { SyNote(it) }
        val currentIdentity = selectionState.identity
        if (currentIdentity == null || !selectionState.canSelectForCurrentChat)
            SyNote("当前会话无法更换人物")
        SyInput(filter, { filter = it }, label = "搜索人物")
        val visible = entries.filter { it.name.contains(filter, ignoreCase = true) }
        if (!loading && visible.isEmpty()) Text("没有匹配的人物")
        visible.forEach { person ->
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(person.name, style = MaterialTheme.typography.titleMedium)
                if (person.stories.isNotEmpty())
                    Text("已保存故事：" + person.stories.size, style = MaterialTheme.typography.bodySmall)
                SyButton("设为当前人物", onClick = {
                    val expected = currentIdentity ?: return@SyButton
                    if (selection.snapshot().identity != expected) {
                        error = "会话已经切换，请刷新后再操作"
                        return@SyButton
                    }
                    submitting = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                selection.submit(PersonaSelectionUiCommand.SelectForCurrentChat(expected, person.id))
                            }
                            if (selection.snapshot().identity == expected) {
                                error = describeV6ConversationReceipt(result)
                                if (result == UiCommandReceipt.Accepted) onBack()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (selection.snapshot().identity == expected)
                                error = failure.message ?: "人物切换失败"
                        } finally { submitting = false }
                    }
                }, enabled = currentIdentity != null && selectionState.canSelectForCurrentChat &&
                    !submitting && !loading)
            }
        }
    }
}

/** Owner-issued membership count is authoritative; the view never stores group members. */
internal fun validV6GroupSelection(snapshot: UiChatGroupManagementSnapshot, selected: Set<String>): Boolean =
    !snapshot.busy &&
        selected.size in snapshot.minimumMembers..snapshot.maximumMembers &&
        selected.all { id -> snapshot.available.any { it.galleryId == id } }

/** Separate actual member and announcement surfaces, backed by the SAME Chat Group Owner. */
@Composable
fun V6ChatGroupScene(groups: ChatGroupUiPort, announcementOnly: Boolean, onBack: () -> Unit) {
    val state by groups.snapshots.collectAsStateWithLifecycle(initialValue = remember(groups) { groups.snapshot() })
    val identity = state.identity
    var selected by remember(identity, announcementOnly) { mutableStateOf(state.memberIds) }
    var createNew by remember(identity) { mutableStateOf(false) }
    var draft by remember(identity) { mutableStateOf(state.announcement) }
    var feedback by remember(identity) { mutableStateOf<String?>(null) }
    var pending by remember(identity) { mutableStateOf(false) }
    var confirmCreate by remember(identity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Owner updates (membership / announcement) are mirrored only when no editor is open.
    LaunchedEffect(identity, state.memberIds, createNew) {
        if (!createNew && !pending) selected = state.memberIds
    }
    LaunchedEffect(identity, state.announcement) {
        if (!pending) draft = state.announcement
    }
    fun submit(command: ChatGroupUiCommand, afterAccepted: () -> Unit = {}) {
        val expected = identity ?: return
        if (state.busy || pending || groups.snapshot().identity != expected) {
            feedback = "当前群聊正在切换，请重试"
            return
        }
        pending = true
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) { groups.submit(command) }
                if (groups.snapshot().identity == expected) {
                    feedback = describeV6ConversationReceipt(receipt)
                    if (receipt == UiCommandReceipt.Accepted) afterAccepted()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (groups.snapshot().identity == expected)
                    feedback = failure.message ?: "群聊操作失败"
            } finally { pending = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (announcementOnly) "群聊公告" else "群聊成员",
                style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        feedback?.let { SyNote(it) }
        if (identity == null) {
            SyNote("尚未进入有效会话")
        } else if (state.busy) {
            SyNote("群聊 Owner 正在处理其他操作")
        }
        if (announcementOnly) {
            if (!state.active) SyNote("当前会话尚未启用群聊")
            else {
                SyGlassCard(Modifier.fillMaxWidth()) {
                    Text("公告最多 " + state.maximumAnnouncementChars + " 字")
                    SyInput(draft, { draft = it.take(state.maximumAnnouncementChars) },
                        label = "群聊公告", singleLine = false)
                    Text("${draft.length}/${state.maximumAnnouncementChars}")
                    SyButton("保存公告", {
                        identity?.let { submit(ChatGroupUiCommand.SaveAnnouncement(it, draft)) }
                    }, enabled = identity != null && !pending && !state.busy &&
                        draft.length <= state.maximumAnnouncementChars)
                }
            }
        } else {
            if (identity != null) {
                SySwitch("群聊模式", state.active, onCheckedChange = { enabled ->
                    submit(ChatGroupUiCommand.SetGroupMode(identity, enabled))
                }, enabled = !state.busy && !pending)
                SyButton("创建新的群聊会话", {
                    createNew = true
                    selected = emptySet()
                    feedback = null
                }, enabled = !state.busy && !pending, style = SyButtonStyle.SECONDARY)
                if (state.active && !createNew) {
                    SyButton("重新配置当前群聊成员", {
                        selected = state.memberIds
                    }, style = SyButtonStyle.GHOST)
                }
            }
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text(if (createNew) "选择新群聊成员" else "选择群聊成员",
                    style = MaterialTheme.typography.titleMedium)
                Text("请选 " + state.minimumMembers + "–" + state.maximumMembers +
                    " 人，已选择 " + selected.size)
                state.available.forEach { person ->
                    SySwitch(person.displayName, person.galleryId in selected, onCheckedChange = { on ->
                        selected = if (on) selected + person.galleryId else selected - person.galleryId
                    }, enabled = identity != null && !pending && !state.busy &&
                        (person.galleryId in selected || selected.size < state.maximumMembers))
                }
                if (state.available.isEmpty()) SyNote("当前人物库没有可加入群聊的人物")
                if (identity != null) {
                    SyButton(if (createNew) "创建群聊" else "保存成员", {
                        val current = groups.snapshot()
                        if (current.identity != identity || !validV6GroupSelection(current, selected)) {
                            feedback = "成员选择已失效，请重新检查"
                        } else {
                            confirmCreate = true
                        }
                    }, enabled = validV6GroupSelection(state, selected) &&
                        !pending && (createNew || state.active))
                    if (state.active && !createNew) state.available
                        .filter { it.galleryId in state.memberIds }.forEach { member ->
                            SyButton("移除 " + member.displayName, onClick = {
                                submit(ChatGroupUiCommand.RemoveMember(identity, member.galleryId))
                            }, enabled = !pending && !state.busy, style = SyButtonStyle.GHOST)
                        }
                }
            }
        }
    }
    if (confirmCreate && identity != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!pending) confirmCreate = false },
        title = { Text(if (createNew) "确认创建群聊" else "确认更新成员") },
        text = { Text("将交由 Chat Owner 操作以下人物：\n" +
            state.available.filter { it.galleryId in selected }.joinToString("、") { it.displayName }) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            val cmd = if (createNew) ChatGroupUiCommand.CreateGroupSession(identity, selected.toList())
                else ChatGroupUiCommand.ConfigureMembers(identity, selected.toList())
            confirmCreate = false
            submit(cmd) { createNew = false }
        }, enabled = !pending) { Text("确认") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmCreate = false }) {
            Text("取消")
        } },
    )
}
