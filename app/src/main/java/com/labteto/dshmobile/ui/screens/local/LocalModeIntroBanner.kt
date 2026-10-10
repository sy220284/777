package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Keep optional intro rendering outside the conversation's large inline layout/register frame. */
@Composable
internal fun LocalModeIntroBanner(intro: LocalModeIntro?) {
    if (intro == null) return
    val mode = intro.usageMode
    val colors = DsTheme.colors
    Surface(
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        shape = DsShapes.block,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
    ) {
        Text(
            stringResource(
                if (mode == LocalUsageMode.CHAT) R.string.local_mode_intro_chat
                else R.string.local_mode_intro_work,
            ),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        )
    }
}
