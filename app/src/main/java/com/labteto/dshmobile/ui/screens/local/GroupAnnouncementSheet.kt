package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.shape.RoundedCornerShape

import com.shenyan.designsystem.foundation.SyMetric

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
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

@Composable
internal fun GroupAnnouncementCard(
    announcement: String,
    onClick: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SyMaterialSurface(
        tier = SyMaterialTier.L3_CARD,
        shape = RoundedCornerShape(SyMetric.radiusCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.local_group_announcement_title),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                if (announcement.isNotBlank()) {
                    Text(
                        stringResource(R.string.local_group_announcement_active_short),
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.primary,
                    )
                }
            }
            Text(
                announcement.ifBlank { stringResource(R.string.local_group_announcement_empty) },
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (announcement.isNotBlank()) {
                Text(
                    stringResource(R.string.local_group_announcement_active_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
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
        dismissEnabled = !saving,
        scrollable = true,
    ) {
        Text(stringResource(R.string.local_group_announcement_hint),
            style = DsType.small13.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        SyTextField(
            value = draft,
            onValueChange = { draft = it.take(2_000); error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_group_announcement_content)) },
            minLines = 4,
            maxLines = 9,
            enabled = enabled && !saving,
        )
        error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk) }
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
