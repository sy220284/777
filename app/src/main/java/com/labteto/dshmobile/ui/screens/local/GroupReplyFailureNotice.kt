package com.labteto.dshmobile.ui.screens.local


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
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun GroupReplyFailureNotice(group: LocalGroupChatState, running: Boolean, retry: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.audit_group_partial), style = DsType.small13.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.warningInk)
        group.members.filter { it.galleryId in group.failedReplyMemberIds }.forEach { member ->
            val request = stringResource(R.string.audit_group_retry_request, member.displayName)
            SyButton(text = stringResource(R.string.audit_group_retry_member, member.displayName),
                onClick = { retry(request) }, enabled = !running, variant = SyButtonVariant.Ghost)
        }
    }
}
