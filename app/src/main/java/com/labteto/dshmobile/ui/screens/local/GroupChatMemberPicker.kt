package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.MAX_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.MIN_GROUP_CHAT_MEMBERS
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

@Composable
internal fun GroupChatMemberPickerSheet(
    entries: List<PersonaGalleryEntry>,
    currentIds: List<String>,
    enabled: Boolean,
    onSave: (List<String>) -> Boolean,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>() }
    val avatarFallback = stringResource(R.string.persona_gallery_avatar_fallback)
    LaunchedEffect(currentIds) {
        selected.clear()
        selected.addAll(currentIds.distinct().take(MAX_GROUP_CHAT_MEMBERS))
    }

    DsBottomSheet(
        title = stringResource(R.string.local_group_chat_members_title),
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(
                R.string.local_group_chat_members_hint,
                MIN_GROUP_CHAT_MEMBERS,
                MAX_GROUP_CHAT_MEMBERS,
            ),
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )

        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.local_group_chat_no_characters),
                style = DsType.small13,
                color = DsTheme.colors.labelSecondary,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                entries.forEach { entry ->
                    val checked = entry.id in selected
                    val canAdd = checked || selected.size < MAX_GROUP_CHAT_MEMBERS
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled && canAdd) {
                                if (checked) selected.remove(entry.id)
                                else if (selected.size < MAX_GROUP_CHAT_MEMBERS) selected.add(entry.id)
                            },
                        color = if (checked) {
                            DsTheme.colors.accent.copy(alpha = 0.08f)
                        } else {
                            DsTheme.colors.bgLayer1
                        },
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
                                color = DsTheme.colors.accent.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    entry.persona.name.trim().take(1).ifBlank { avatarFallback },
                                    style = DsType.std14Strong,
                                    color = DsTheme.colors.accent,
                                    modifier = Modifier.padding(DsSpacing.small),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.persona.name,
                                    style = DsType.std14Strong,
                                    color = DsTheme.colors.labelPrimary,
                                )
                                entry.persona.identity.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        it,
                                        style = DsType.caption11,
                                        color = DsTheme.colors.labelSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = enabled && canAdd,
                            )
                        }
                    }
                }
            }
        }

        Text(
            stringResource(
                R.string.local_group_chat_selected_count,
                selected.size,
                MAX_GROUP_CHAT_MEMBERS,
            ),
            style = DsType.caption11,
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
    }
}
