package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

@Composable
internal fun GroupAnnouncementSheet(
    sessionId: String,
    announcement: String,
    enabled: Boolean,
    onSave: (String) -> Boolean,
    onGenerate: suspend (String) -> Result<String>,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(sessionId) { mutableStateOf(announcement) }
    var direction by rememberSaveable(sessionId) { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DsBottomSheet(title = stringResource(R.string.local_group_announcement_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.local_group_announcement_hint),
            style = DsType.small13, color = DsTheme.colors.labelSecondary)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(2_000); error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_group_announcement_content)) },
            minLines = 4,
            maxLines = 9,
            enabled = enabled && !generating,
        )
        OutlinedTextField(
            value = direction,
            onValueChange = { direction = it.take(500) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_group_announcement_direction)) },
            placeholder = { Text(stringResource(R.string.local_group_announcement_direction_hint)) },
            enabled = enabled && !generating,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.local_group_announcement_drama),
                onClick = { direction = "修罗场：几人因为同一件事产生互相矛盾的期待，秘密即将被揭开" },
                enabled = enabled && !generating,
                variant = DsButtonVariant.Outline,
            )
            DsButton(
                text = stringResource(R.string.local_group_announcement_mystery),
                onClick = { direction = "悬疑对峙：有人隐瞒关键线索，众人必须当场作出选择" },
                enabled = enabled && !generating,
                variant = DsButtonVariant.Outline,
            )
        }
        DsButton(
            text = stringResource(R.string.local_group_announcement_generate),
            onClick = {
                generating = true
                error = null
                scope.launch {
                    onGenerate(direction).onSuccess { draft = it }
                        .onFailure { error = it.message }
                    generating = false
                }
            },
            enabled = enabled && !generating,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        error?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.error) }
        DsButton(
            text = stringResource(R.string.local_group_announcement_save),
            onClick = {
                if (onSave(draft)) onDismiss()
                else error = "当前无法保存群公告，请稍后重试"
            },
            enabled = enabled && !generating && draft != announcement,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
