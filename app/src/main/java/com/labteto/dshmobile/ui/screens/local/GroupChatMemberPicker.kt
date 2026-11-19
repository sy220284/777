package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.presentation.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.presentation.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.presentation.personaMemberSearchText
import com.labteto.dshmobile.local.presentation.personaMemberDescription
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.components.SyMultiSelectSheet
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyOption

/**
 * Shared transactional multi-select, with the same constraints as the previous
 * group-specific picker. Search changes only the visible options, not the draft.
 */
@Composable
internal fun GroupChatMemberPickerSheet(
    entries: List<PersonaGalleryEntry>,
    currentIds: List<String>,
    enabled: Boolean,
    onSave: (List<String>) -> Boolean,
    onCreatePersona: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val available = remember(entries) { entries.mapTo(hashSetOf(), PersonaGalleryEntry::id) }
    val initial = remember(currentIds, available) {
        currentIds.filter(available::contains).distinct().take(MAX_GROUP_CHAT_MEMBERS).toSet()
    }
    val filtered = remember(entries, query) {
        entries.filter { query.isBlank() ||
            personaMemberSearchText(it.persona).contains(query.trim(), ignoreCase = true) }
    }
    val byId = remember(entries) { entries.associateBy(PersonaGalleryEntry::id) }
    val fallback = stringResource(R.string.persona_gallery_avatar_fallback)
    val colors = LocalSyColors.current

    SyMultiSelectSheet(
        title = stringResource(R.string.local_group_chat_members_title),
        options = filtered.map { SyOption(it.id, it.persona.name) },
        selectedIds = initial,
        minSelected = MIN_GROUP_CHAT_MEMBERS,
        maxSelected = MAX_GROUP_CHAT_MEMBERS,
        enabled = enabled,
        confirmLabel = stringResource(R.string.local_group_chat_apply_members),
        onConfirm = { ids ->
            // Preserve the original member order, retaining only explicitly selected entries.
            val ordered = currentIds.filter(ids::contains) +
                entries.map(PersonaGalleryEntry::id).filter { it in ids && it !in currentIds }
            if (onSave(ordered)) onDismiss()
        },
        onDismiss = onDismiss,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                Text(stringResource(R.string.local_group_chat_members_hint,
                    MIN_GROUP_CHAT_MEMBERS, MAX_GROUP_CHAT_MEMBERS),
                    style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
                if (entries.size < MIN_GROUP_CHAT_MEMBERS && onCreatePersona != null) {
                    SyButton(
                        text = if (entries.isEmpty()) "去创建人物" else "再创建一位人物",
                        onClick = onCreatePersona, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("group-chat-create-persona"),
                        variant = SyButtonVariant.Ghost,
                    )
                }
                if (entries.isNotEmpty()) {
                    SyTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.local_group_chat_search_members)) })
                    if (filtered.isEmpty())
                        Text(stringResource(R.string.persona_gallery_no_match),
                            style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
                } else {
                    Text(stringResource(R.string.local_group_chat_no_characters),
                        style = SyType.small13.withReadingWeight(), color = colors.textSecondary)
                }
            }
        },
        summary = { draft ->
            Text(stringResource(R.string.local_group_chat_selected_count,
                draft.size, MAX_GROUP_CHAT_MEMBERS),
                style = SyType.caption11.withReadingWeight(), color = colors.textTertiary)
        },
        itemContent = { option, _ ->
            val entry = requireNotNull(byId[option.id])
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                Surface(shape = CircleShape, color = colors.selected) {
                    Text(entry.persona.name.trim().take(1).ifBlank { fallback },
                        style = SyType.std14Strong.withReadingWeight(), color = colors.text,
                        modifier = Modifier.padding(SyMetric.gapMd))
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.persona.name,
                        style = SyType.std14Strong.withReadingWeight(), color = colors.text)
                    personaMemberDescription(entry.persona).takeIf(String::isNotBlank)?.let {
                        Text(it, style = SyType.caption11.withReadingWeight(),
                            color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
        },
    )
}
