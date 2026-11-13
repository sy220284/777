package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric



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
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SyCheckboxControl
import com.labteto.dshmobile.local.presentation.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.presentation.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.presentation.personaMemberSearchText
import com.labteto.dshmobile.local.presentation.personaMemberDescription
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
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
                personaMemberSearchText(entry.persona).contains(needle, ignoreCase = true)
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

    SyResponsiveSheet(
        title = stringResource(R.string.local_group_chat_members_title),
        onDismiss = onDismiss,
        seamlessBackground = true,
        scrollable = true,
        footer = {
        Text(
            stringResource(
                R.string.local_group_chat_selected_count,
                selected.size,
                MAX_GROUP_CHAT_MEMBERS,
            ),
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textTertiary,
        )
        SyButton(
            text = stringResource(R.string.local_group_chat_apply_members),
            onClick = {
                if (onSave(selected.toList())) onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && selected.size in MIN_GROUP_CHAT_MEMBERS..MAX_GROUP_CHAT_MEMBERS,
        )
        SyButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Ghost,
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
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        )

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.local_group_chat_no_characters),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
        } else {
            SyTextField(
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
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
                ) {
                    items(filteredEntries, key = PersonaGalleryEntry::id) { entry ->
                        val checked = entry.id in selected
                        val canAdd = checked || selected.size < MAX_GROUP_CHAT_MEMBERS
                        Surface(
                            shape = DsShapes.row,
                            color = if (checked) com.shenyan.designsystem.foundation.LocalSyColors.current.selected.copy(alpha = .32f)
                                else androidx.compose.ui.graphics.Color.Transparent,
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
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.lilac.copy(alpha = .18f),
                                ) {
                                    Text(
                                        entry.persona.name.trim().take(1).ifBlank { avatarFallback },
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.lilac,
                                        modifier = Modifier.padding(SyMetric.gapMd),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.persona.name,
                                        style = DsType.std14Strong.withReadingWeight(),
                                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.text,
                                    )
                                    personaMemberDescription(entry.persona).takeIf(String::isNotBlank)?.let {
                                        Text(
                                            it,
                                            style = DsType.caption11.withReadingWeight(),
                                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                SyCheckboxControl(
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
