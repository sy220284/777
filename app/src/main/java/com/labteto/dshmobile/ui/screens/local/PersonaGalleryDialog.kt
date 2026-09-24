package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun PersonaGalleryDialog(
    entries: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentSessionId: String,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?) -> Result<PersonaGalleryEntry>,
    onEditNotes: suspend (String, String) -> Result<Unit>,
    onDelete: suspend (String) -> Result<Unit>,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var search by remember { mutableStateOf("") }
    var notes by remember(selectedId, selected?.storyNotes) { mutableStateOf(selected?.storyNotes.orEmpty()) }
    var savingCurrent by remember { mutableStateOf(false) }
    var currentNotes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var visibleHistory by remember(selectedId) { mutableStateOf(8) }
    var showHistory by remember(selectedId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DsDialog(title = if (selected == null) "人设图集" else selected.persona.name, onDismiss = onDismiss) {
        if (selected == null) {
            Text("角色设定与实际故事分开归档。选择一张角色卡，可从保存时的故事继续开启新会话。",
                style = DsType.small13, color = DsTheme.colors.labelSecondary)
            DsButton(
                text = "保存当前人设与故事 · ${currentPersona.name}",
                onClick = { savingCurrent = true; error = null },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy,
            )
            if (savingCurrent) {
                OutlinedTextField(
                    value = currentNotes,
                    onValueChange = { currentNotes = it },
                    label = { Text("剧情提要（可选）") },
                    placeholder = { Text("关键事件、关系变化、未完的约定……") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                Text("会同时保存当前会话的完整用户与角色对白；提要可稍后补写。",
                    style = DsType.caption11, color = DsTheme.colors.labelSecondary)
                DsButton(
                    text = "确认保存",
                    onClick = {
                        busy = true
                        scope.launch {
                            onSaveCurrent(currentNotes, null)
                                .onSuccess { selectedId = it.id; savingCurrent = false; currentNotes = "" }
                                .onFailure { error = it.message ?: "保存失败" }
                            busy = false
                        }
                    },
                    enabled = !busy && canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索角色与剧情") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val filtered = entries.filter { it.persona.name.contains(search, ignoreCase = true) ||
                it.storyNotes.contains(search, ignoreCase = true) }
            if (filtered.isEmpty()) {
                Text(if (entries.isEmpty()) "图集还空着，保存一次聊天就有第一张角色卡。" else "没有匹配的角色",
                    style = DsType.small13, color = DsTheme.colors.labelSecondary)
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                filtered.forEach { entry ->
                    DsButton(
                        text = "${entry.persona.name} · ${entry.history.size} 条对白",
                        onClick = { selectedId = entry.id; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        variant = DsButtonVariant.Outline,
                    )
                }
            }
        } else {
            Text(selected.persona.identity.ifBlank { "角色设定" }, style = DsType.small13,
                color = DsTheme.colors.labelSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("已归档 ${selected.history.size} 条对白。新会话继承角色、剧情提要与最近片段；原会话仍在会话列表中。",
                style = DsType.caption11, color = DsTheme.colors.labelSecondary)
            DsButton(text = if (showHistory) "收起故事历史" else "查看完整故事历史",
                onClick = { showHistory = !showHistory }, variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth())
            if (showHistory) {
                val history = selected.history.filter { it.role == "user" || it.role == "assistant" }
                history.takeLast(visibleHistory).forEach { line ->
                    Text("${if (line.role == "user") "用户" else selected.persona.name}：${line.content}",
                        style = DsType.small13, color = DsTheme.colors.labelSecondary)
                }
                if (history.size > visibleHistory) {
                    DsButton(text = "再看较早的对白", onClick = { visibleHistory += 12 },
                        modifier = Modifier.fillMaxWidth(), variant = DsButtonVariant.Ghost)
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("剧情提要") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                enabled = !busy,
            )
            if (notes != selected.storyNotes) {
                DsButton(text = "保存提要", onClick = {
                    busy = true
                    scope.launch {
                        onEditNotes(selected.id, notes)
                            .onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (canSave && (currentSessionId == selected.sourceSessionId || currentPersona.id == selected.id)) {
                DsButton(text = "用当前会话更新人设与故事", onClick = {
                    busy = true
                    scope.launch {
                        onSaveCurrent(notes, selected.id)
                            .onFailure { error = it.message ?: "更新失败" }
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy, variant = DsButtonVariant.Outline)
            }
            DsButton(text = "用这个人设开启新会话", onClick = { onStart(selected.id) },
                modifier = Modifier.fillMaxWidth(), enabled = canSave && !busy && notes == selected.storyNotes)
            if (deleting) {
                Text("删除图集条目？已开启的会话不会被删除。", style = DsType.small13,
                    color = DsTheme.colors.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DsButton(text = "取消", onClick = { deleting = false }, variant = DsButtonVariant.Ghost)
                    DsButton(text = "确认删除", onClick = {
                        busy = true
                        scope.launch {
                            onDelete(selected.id)
                                .onSuccess { selectedId = null; deleting = false }
                                .onFailure { error = it.message ?: "删除失败" }
                            busy = false
                        }
                    }, variant = DsButtonVariant.Danger, enabled = !busy)
                }
            } else {
                DsButton(text = "删除这张角色卡", onClick = { deleting = true },
                    variant = DsButtonVariant.Ghost, enabled = !busy)
            }
            DsButton(text = "返回图集", onClick = { selectedId = null; deleting = false; error = null },
                variant = DsButtonVariant.Outline, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, style = DsType.small13, color = DsTheme.colors.error,
            modifier = Modifier.padding(top = DsSpacing.small)) }
    }
}
