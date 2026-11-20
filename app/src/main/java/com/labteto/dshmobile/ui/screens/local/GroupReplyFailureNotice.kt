package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyBannerKind
import com.shenyan.designsystem.components.SyInlineBanner
import com.shenyan.designsystem.foundation.SyMetric

@Composable
internal fun GroupReplyFailureNotice(
    group: LocalGroupChatState,
    running: Boolean,
    retry: (String) -> Unit,
    latestUserMessageId: String? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
        SyInlineBanner(stringResource(R.string.audit_group_partial), SyBannerKind.WARNING)
        group.members.filter { it.galleryId in group.failedReplyMemberIds }.forEach { member ->
            val source = group.failedReplySources[member.galleryId]
            val canRetry = source != null && (latestUserMessageId == null ||
                source.sourceMessageId == latestUserMessageId)
            if (!canRetry) Text("原请求已不在当前最新回合中，请从历史记录查看这次失败。",
                style = com.labteto.dshmobile.ui.theme.SyType.small13,
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            group.failedReplySources[member.galleryId]?.partialContent
                ?.takeIf(String::isNotBlank)?.let { partial ->
                    Text("未完成的回复：" + partial.take(240),
                        style = com.labteto.dshmobile.ui.theme.SyType.small13,
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
                }
            SyButton(
                text = stringResource(R.string.audit_group_retry_member, member.displayName),
                onClick = { retry(member.galleryId) },
                enabled = !running && canRetry,
                variant = SyButtonVariant.Ghost,
            )
        }
    }
}
