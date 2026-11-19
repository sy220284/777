package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
        SyInlineBanner(stringResource(R.string.audit_group_partial), SyBannerKind.WARNING)
        group.members.filter { it.galleryId in group.failedReplyMemberIds }.forEach { member ->
            val request = stringResource(R.string.audit_group_retry_request, member.displayName)
            SyButton(
                text = stringResource(R.string.audit_group_retry_member, member.displayName),
                onClick = { retry(request) },
                enabled = !running,
                variant = SyButtonVariant.Ghost,
            )
        }
    }
}
