package com.labteto.dshmobile.ui.screens.local


import com.shenyan.designsystem.foundation.SyMetric



import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Keep optional intro rendering outside the conversation's large inline layout/register frame. */
@Composable
internal fun LocalModeIntroBanner(intro: LocalModeIntro?) {
    if (intro == null) return
    val mode = intro.usageMode
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
    ) {
        Text(
            stringResource(
                if (mode == LocalUsageMode.CHAT) R.string.local_mode_intro_chat
                else R.string.local_mode_intro_work,
            ),
            style = DsType.small13.withReadingWeight(),
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
        )
    }
}
