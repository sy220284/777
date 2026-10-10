package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DsCheckbox
import com.labteto.dshmobile.local.presentation.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.presentation.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.CharacterFactCategories
import com.labteto.dshmobile.local.chat.factText
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun GroupChatMemberPickerSheet(
    entries: List<PersonaGalleryEntry>,
    currentIds: List<String>,
    enabled: Boolean,
    onSave: (List<String>) -> Boolean,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    val avatarFallback = stringResource(R.string.persona_gallery_avatar_fallback)
    val availableIds = entries.mapTo(hashSetOf(), PersonaGalleryEntry::id)
    val filteredEntries = remember(entries, query) {
        val needle = query.trim()
        if (needle.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.persona.name.contains(needle, ignoreCase = true) ||
                    entry.persona.coreIdentity.contains(needle, ignoreCase = true) ||
                    entry.persona.facts.any { it.content.contains(needle, ignoreCase = true) }
            }
        }
    }
    LaunchedEffect(currentIds, entries) {
        selected.clear()
        selected.addAll(
            currentIds
                .filter(availableIds::contains)
                .distinct()
                .take(MAX_GROUP_CHAT_MEMBERS),
        )
    }

    DsBottomSheet(
        title = stringResource(R.string.local_group_chat_members_title),
        onDismiss = onDismiss,
        scrollable = true,
        footer = {
        Text(
            stringResource(
                R.string.local_group_chat_selected_count,
                selected.size,
                MAX_GROUP_CHAT_MEMBERS,
            ),
            style = DsType.caption11.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
        )
        DsButton(
            text = stringResource(R.string.local_group_chat_apply_members),
            onClick = {
                if (onSave(selected.toList())) onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && selected.size in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS,
        )
        DsButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
        },
    ) {
        Text(
            stringResource(
                R.string.local_group_chat_members_hint,
                MIN_GROUP_CHAT_MEMBERS,
                MAX_GROUP_CHAT_MEMBERS,
            ),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
        )

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.local_group_chat_no_characters),
                style = DsType.small13.withReadingWeight(),
                color = DsTheme.colors.labelSecondary,
            )
        } else {
            DsTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.local_group_chat_search_members)) },
            )
            if (filteredEntries.isEmpty()) {
                Text(
                    stringResource(R.string.persona_gallery_no_match),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    items(filteredEntries, key = PersonaGalleryEntry::id) { entry ->
                        val checked = entry.id in selected
                        val canAdd = checked || selected.size < MAX_GROUP_CHAT_MEMBERS
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = enabled && canAdd,
                                    role = Role.Checkbox,
                                    onValueChange = { shouldCheck ->
                                        if (shouldCheck) {
                                            if (selected.size < MAX_GROUP_CHAT_MEMBERS) selected.add(entry.id)
                                        } else {
                                            selected.remove(entry.id)
                                        }
                                    },
                                ),
                            color = DsTheme.colors.wallpaperSurface(
                                WallpaperSurfaceLevel.CARD,
                                base = if (checked) DsTheme.colors.accentTertiary else DsTheme.colors.bgLayer1,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = DsTheme.colors.characterAccentTertiary,
                                ) {
                                    Text(
                                        entry.persona.name.trim().take(1).ifBlank { avatarFallback },
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = DsTheme.colors.characterAccent,
                                        modifier = Modifier.padding(DsSpacing.small),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.persona.name,
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = DsTheme.colors.labelPrimary,
                                    )
                                    (entry.persona.coreIdentity.takeIf(String::isNotBlank)
                                        ?: entry.persona.factText(CharacterFactCategories.BIOGRAPHY)
                                            .takeIf(String::isNotBlank))?.let {
                                        Text(
                                            it,
                                            style = DsType.caption11.withReadingWeight(),
                                            color = DsTheme.colors.labelSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                DsCheckbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    enabled = enabled && canAdd,
                                )
                            }
                        }
                    }
                }
            }
        }

    }
}
