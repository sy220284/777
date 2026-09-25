package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.PersonaAppendSuggestion
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaInspectionResult
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun PersonaGallerySavePromptDialog(
    persona: PersonaProfile,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?) -> Result<PersonaGalleryEntry>,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DsDialog(title = "发现新角色", onDismiss = onDismiss) {
        PersonaHero(persona = persona, subtitle = "这次聊天里的人物还没有收入图集")
        Text(
            "保存后会建立这名人物的唯一主档案。以后再次保存同一人物，只会补充新增设定、关系和故事记录，不会重复生成多张卡。",
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )
        DsButton(
            text = if (busy) "正在保存…" else "保存并进入人设图集",
            onClick = {
                busy = true
                error = null
                scope.launch {
                    onSaveCurrent("", null)
                        .onSuccess { onContinue() }
                        .onFailure { error = it.message ?: "保存失败" }
                    busy = false
                }
            },
            enabled = canSave && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            text = "这次先不保存",
            onClick = onContinue,
            variant = DsButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        error?.let {
            Text(it, style = DsType.small13, color = DsTheme.colors.error)
        }
    }
}

@Composable
internal fun PersonaGalleryDialog(
    entries: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    currentSessionId: String,
    canSave: Boolean,
    onSaveCurrent: suspend (String, String?) -> Result<PersonaGalleryEntry>,
    onEditNotes: suspend (String, String) -> Result<Unit>,
    onInspect: suspend (String) -> Result<PersonaInspectionResult>,
    onApplySuggestions: suspend (String, List<PersonaAppendSuggestion>) -> Result<PersonaGalleryEntry>,
    onDelete: suspend (String) -> Result<Unit>,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var search by remember { mutableStateOf("") }
    var notes by remember(selectedId, selected?.storyNotes) { mutableStateOf(selected?.storyNotes.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var visibleHistory by remember(selectedId) { mutableStateOf(8) }
    var showHistory by remember(selectedId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember(selectedId) { mutableStateOf<String?>(null) }
    var inspection by remember(selectedId) { mutableStateOf<PersonaInspectionResult?>(null) }
    var inspecting by remember(selectedId) { mutableStateOf(false) }
    val selectedSuggestionKeys = remember(selectedId) { mutableStateListOf<String>() }

    DsDialog(title = if (selected == null) "人设图集" else selected.persona.name, onDismiss = onDismiss) {
        if (selected == null) {
            GalleryOverviewHeader(entries.size)
            DsButton(
                text = if (currentGalleryId == null) "同步当前人物 · ${currentPersona.name}" else "更新当前人物 · ${currentPersona.name}",
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        onSaveCurrent("", currentGalleryId)
                            .onSuccess {
                                selectedId = it.id
                                notice = "人物资料已合并到同一张主卡"
                            }
                            .onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy,
                variant = DsButtonVariant.Outline,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索人物、身份或剧情") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val query = search.trim()
            val filtered = entries.filter {
                query.isBlank() ||
                    it.persona.name.contains(query, ignoreCase = true) ||
                    it.persona.identity.contains(query, ignoreCase = true) ||
                    it.persona.worldSetting.contains(query, ignoreCase = true) ||
                    it.storyNotes.contains(query, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                DsCard {
                    Text(
                        if (entries.isEmpty()) "图集还空着。进入图集时发现新角色，会先提示你保存。"
                        else "没有找到匹配的人物",
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    filtered.forEach { entry ->
                        GalleryPersonaCard(
                            entry = entry,
                            onClick = {
                                selectedId = entry.id
                                error = null
                                notice = null
                            },
                        )
                    }
                }
            }
        } else {
            PersonaHero(
                persona = selected.persona,
                subtitle = buildString {
                    append("${selected.history.size} 条对白")
                    if (selected.chatState.dynamics.sharedMoments.isNotEmpty()) {
                        append(" · ${selected.chatState.dynamics.sharedMoments.size} 段共同经历")
                    }
                },
            )
            notice?.let {
                Surface(
                    color = DsTheme.colors.accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = DsType.small13,
                        color = DsTheme.colors.labelPrimary,
                        modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                    )
                }
            }

            PersonaDetails(selected.persona)

            if (selected.chatState.updatedAt > 0L) {
                DsCard {
                    Text("故事与关系", style = DsType.std14, color = DsTheme.colors.labelPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "当前关系：${selected.chatState.relationshipState}",
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                    selected.chatState.dynamics.sharedMoments.takeLast(4).takeIf { it.isNotEmpty() }?.let { moments ->
                        Text(
                            "共同经历：${moments.joinToString("；")}",
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                    selected.chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let { threads ->
                        Text(
                            "未完线索：${threads.joinToString("；")}",
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                }
            }

            DsButton(
                text = if (inspecting) "正在检查人物…" else "人物检查",
                onClick = {
                    inspecting = true
                    error = null
                    notice = null
                    selectedSuggestionKeys.clear()
                    scope.launch {
                        onInspect(selected.id)
                            .onSuccess { inspection = it }
                            .onFailure { error = it.message ?: "人物检查失败" }
                        inspecting = false
                    }
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !inspecting,
            )

            inspection?.let { result ->
                PersonaInspectionPanel(
                    result = result,
                    selectedKeys = selectedSuggestionKeys,
                    onToggle = { suggestion ->
                        val key = suggestionKey(suggestion)
                        if (key in selectedSuggestionKeys) selectedSuggestionKeys.remove(key)
                        else selectedSuggestionKeys.add(key)
                    },
                )
                if (result.suggestions.isNotEmpty()) {
                    val chosen = result.suggestions.filter { suggestionKey(it) in selectedSuggestionKeys }
                    DsButton(
                        text = "追加选中内容（${chosen.size}）",
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                onApplySuggestions(selected.id, chosen)
                                    .onSuccess {
                                        inspection = null
                                        selectedSuggestionKeys.clear()
                                        notice = "已追加到固定人设，重复内容已自动忽略"
                                    }
                                    .onFailure { error = it.message ?: "追加失败" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = chosen.isNotEmpty() && !busy,
                    )
                }
            }

            DsButton(
                text = if (showHistory) "收起故事历史" else "查看故事历史",
                onClick = { showHistory = !showHistory },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showHistory) {
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = DsTheme.colors.labelSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("已归档对白", style = DsType.std14, color = DsTheme.colors.labelPrimary)
                    }
                    val history = selected.history.filter { it.role == "user" || it.role == "assistant" }
                    history.takeLast(visibleHistory).forEach { line ->
                        Text(
                            "${if (line.role == "user") "用户" else selected.persona.name}：${line.content}",
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                    if (history.size > visibleHistory) {
                        DsButton(
                            text = "再看较早的对白",
                            onClick = { visibleHistory += 12 },
                            modifier = Modifier.fillMaxWidth(),
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("剧情提要") },
                placeholder = { Text("关键事件、关系变化、未完成的约定……") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                enabled = !busy,
            )
            if (notes != selected.storyNotes) {
                DsButton(
                    text = "保存剧情提要",
                    onClick = {
                        busy = true
                        scope.launch {
                            onEditNotes(selected.id, notes)
                                .onSuccess { notice = "剧情提要已保存" }
                                .onFailure { error = it.message ?: "保存失败" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }

            if (canSave && (currentSessionId == selected.sourceSessionId || currentGalleryId == selected.id)) {
                DsButton(
                    text = "把当前会话新增内容并入这张人物卡",
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            onSaveCurrent(notes, selected.id)
                                .onSuccess { notice = "已合并新增内容，没有重复生成角色卡" }
                                .onFailure { error = it.message ?: "更新失败" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                )
            }

            DsButton(
                text = "用这个人物开启新会话",
                onClick = { onStart(selected.id) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave && !busy && notes == selected.storyNotes,
            )

            if (deleting) {
                DsCard {
                    Text(
                        "删除这张人物卡？已开启的会话不会被删除。",
                        style = DsType.small13,
                        color = DsTheme.colors.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DsButton(
                            text = "取消",
                            onClick = { deleting = false },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.weight(1f),
                        )
                        DsButton(
                            text = "确认删除",
                            onClick = {
                                busy = true
                                scope.launch {
                                    onDelete(selected.id)
                                        .onSuccess {
                                            selectedId = null
                                            deleting = false
                                        }
                                        .onFailure { error = it.message ?: "删除失败" }
                                    busy = false
                                }
                            },
                            variant = DsButtonVariant.Danger,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                DsButton(
                    text = "删除人物卡",
                    onClick = { deleting = true },
                    variant = DsButtonVariant.Ghost,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = "返回图集",
                onClick = {
                    selectedId = null
                    deleting = false
                    error = null
                    notice = null
                },
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Text(
                it,
                style = DsType.small13,
                color = DsTheme.colors.error,
                modifier = Modifier.padding(top = DsSpacing.small),
            )
        }
    }
}

@Composable
private fun GalleryOverviewHeader(count: Int) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = DsTheme.colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = DsTheme.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    "人物主档案",
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "已保存 $count 人 · 同一人物持续增量完善",
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun GalleryPersonaCard(entry: PersonaGalleryEntry, onClick: () -> Unit) {
    DsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(entry.persona.name)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.persona.name,
                    style = DsType.std14,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.persona.identity.ifBlank { entry.persona.personality.ifBlank { "等待补充人物设定" } },
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GalleryPill("${entry.history.size} 条对白")
            if (entry.chatState.dynamics.sharedMoments.isNotEmpty()) {
                GalleryPill("${entry.chatState.dynamics.sharedMoments.size} 段经历")
            }
            if (entry.persona.corrections.isNotEmpty()) {
                GalleryPill("${entry.persona.corrections.size} 条纠正")
            }
        }
    }
}

@Composable
private fun PersonaHero(persona: PersonaProfile, subtitle: String) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonaAvatar(persona.name, large = true)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    persona.name,
                    style = DsType.large20,
                    color = DsTheme.colors.labelPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    persona.identity.ifBlank { subtitle },
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.identity.isNotBlank()) {
                    Text(subtitle, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
                }
            }
        }
    }
}

@Composable
private fun PersonaAvatar(name: String, large: Boolean = false) {
    val size = if (large) 56.dp else 44.dp
    Surface(
        shape = CircleShape,
        color = DsTheme.colors.accent.copy(alpha = 0.13f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.toString().orEmpty().ifBlank { "人" },
                style = if (large) DsType.large20 else DsType.std14,
                color = DsTheme.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GalleryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = DsTheme.colors.bgLayer1,
    ) {
        Text(
            text,
            style = DsType.caption11,
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PersonaDetails(persona: PersonaProfile) {
    val scalarSections = listOf(
        "人物身份" to persona.identity,
        "背景经历" to persona.background,
        "核心性格" to persona.personality,
        "说话方式" to persona.speechStyle,
        "与你的关系" to persona.relationship,
        "世界设定" to persona.worldSetting,
    ).filter { it.second.isNotBlank() }
    val listSections = listOf(
        "硬性设定" to persona.hardConstraints,
        "对白参考" to persona.exampleDialogues,
        "禁用表达" to persona.bannedPhrases,
        "常用表达" to persona.signaturePhrases,
        "已学习纠正" to persona.corrections,
    ).filter { it.second.isNotEmpty() }

    if (scalarSections.isEmpty() && listSections.isEmpty()) return
    DsCard {
        Text(
            "固定人设",
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        scalarSections.forEach { (title, value) ->
            PersonaDetailRow(title, value)
        }
        listSections.forEach { (title, values) ->
            PersonaDetailRow(title, values.joinToString("；"))
        }
    }
}

@Composable
private fun PersonaDetailRow(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        Text(value, style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
}

@Composable
private fun PersonaInspectionPanel(
    result: PersonaInspectionResult,
    selectedKeys: List<String>,
    onToggle: (PersonaAppendSuggestion) -> Unit,
) {
    if (result.conflicts.isEmpty() && result.suggestions.isEmpty()) {
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.PersonSearch,
                    contentDescription = null,
                    tint = DsTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "这轮没有发现明确冲突，也没有新的稳定设定可追加。",
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                )
            }
        }
        return
    }

    if (result.conflicts.isNotEmpty()) {
        Text(
            "发现 ${result.conflicts.size} 处可能冲突",
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.conflicts.forEach { conflict ->
            DsCard {
                Text(
                    personaFieldLabel(conflict.field),
                    style = DsType.caption11,
                    color = DsTheme.colors.error,
                )
                if (conflict.fixedValue.isNotBlank()) {
                    Text(
                        "固定人设：${conflict.fixedValue}",
                        style = DsType.small13,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                Text(
                    "对话表现：${conflict.observedValue}",
                    style = DsType.small13,
                    color = DsTheme.colors.labelPrimary,
                )
                if (conflict.reason.isNotBlank()) {
                    Text(
                        conflict.reason,
                        style = DsType.caption11,
                        color = DsTheme.colors.labelTertiary,
                    )
                }
            }
        }
    }

    if (result.suggestions.isNotEmpty()) {
        Text(
            "可追加到固定人设",
            style = DsType.std14,
            color = DsTheme.colors.labelPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        result.suggestions.forEach { suggestion ->
            val key = suggestionKey(suggestion)
            DsCard(onClick = { onToggle(suggestion) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = key in selectedKeys,
                        onCheckedChange = { onToggle(suggestion) },
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            personaFieldLabel(suggestion.field),
                            style = DsType.caption11,
                            color = DsTheme.colors.accent,
                        )
                        Text(
                            suggestion.value,
                            style = DsType.small13,
                            color = DsTheme.colors.labelPrimary,
                        )
                        if (suggestion.evidence.isNotBlank()) {
                            Text(
                                "依据：${suggestion.evidence}",
                                style = DsType.caption11,
                                color = DsTheme.colors.labelTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun suggestionKey(suggestion: PersonaAppendSuggestion): String =
    "${suggestion.field}|${suggestion.value.trim()}"

private fun personaFieldLabel(field: String): String = when (field) {
    "identity" -> "人物身份"
    "background" -> "背景经历"
    "personality" -> "核心性格"
    "speechStyle" -> "说话方式"
    "relationship" -> "与你的关系"
    "worldSetting" -> "世界设定"
    "hardConstraints" -> "硬性设定"
    "exampleDialogues" -> "对白参考"
    "bannedPhrases" -> "禁用表达"
    "signaturePhrases" -> "常用表达"
    "corrections" -> "人设纠正"
    else -> field
}
