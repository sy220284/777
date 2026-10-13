package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

@Composable
internal fun GroupAnnouncementCard(
    announcement: String,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    SyMaterialSurface(
        tier = SyMaterialTier.L3_CARD,
        shape = DsShapes.block,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.local_group_announcement_title),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (announcement.isNotBlank()) {
                    Text(
                        stringResource(R.string.local_group_announcement_active_short),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.accent,
                    )
                }
            }
            Text(
                announcement.ifBlank { stringResource(R.string.local_group_announcement_empty) },
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (announcement.isNotBlank()) {
                Text(
                    stringResource(R.string.local_group_announcement_active_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            }
        }
    }
}

@Composable
internal fun GroupAnnouncementSheet(
    sessionId: String,
    announcement: String,
    enabled: Boolean,
    onSave: suspend (String) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(sessionId) { mutableStateOf(announcement) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SyResponsiveSheet(
        title = stringResource(R.string.local_group_announcement_title),
        onDismiss = { if (!saving) onDismiss() },
    ) {
        Text(stringResource(R.string.local_group_announcement_hint),
            style = DsType.small13.withReadingWeight(), color = DsTheme.colors.labelSecondary)
        SyTextField(
            value = draft,
            onValueChange = { draft = it.take(2_000); error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_group_announcement_content)) },
            minLines = 4,
            maxLines = 9,
            enabled = enabled && !saving,
        )
        error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.error) }
        SyButton(
            text = stringResource(
                if (saving) R.string.local_group_announcement_saving
                else R.string.local_group_announcement_save,
            ),
            onClick = {
                saving = true
                error = null
                scope.launch {
                    onSave(draft)
                        .onSuccess { onDismiss() }
                        .onFailure { failure ->
                            error = failure.message?.takeIf(String::isNotBlank)
                                ?: "群公告保存失败，请重试"
                        }
                    saving = false
                }
            },
            enabled = enabled && !saving && draft != announcement,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
