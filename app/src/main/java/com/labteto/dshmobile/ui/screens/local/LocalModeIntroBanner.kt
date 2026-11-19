package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.shenyan.designsystem.components.SyBannerKind
import com.shenyan.designsystem.components.SyInlineBanner
import com.shenyan.designsystem.foundation.SyMetric

/** Lightweight introduction follows the shared notification grammar. */
@Composable
internal fun LocalModeIntroBanner(intro: LocalModeIntro?) {
    if (intro == null) return
    SyInlineBanner(
        message = stringResource(
            if (intro.usageMode == LocalUsageMode.CHAT) R.string.local_mode_intro_chat
            else R.string.local_mode_intro_work,
        ),
        kind = SyBannerKind.INFO,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = SyMetric.gapLg, vertical = SyMetric.gapXs),
    )
}
