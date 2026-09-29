package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/** Shared conversation composer shell for local Chat, local Work and remote conversations. */
@Composable
fun DsComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .animateContentSize(),
        shape = DsShapes.composer,
        color = colors.wallpaperSurface(
            WallpaperSurfaceLevel.INPUT,
            BackgroundRegion.BOTTOM,
            colors.composerCard,
        ),
        border = BorderStroke(1.dp, colors.borderL1),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = DsSpacing.small,
                vertical = DsSpacing.tiny,
            ),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            content = content,
        )
    }
}
