package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

@Composable
internal fun LocalUsageModePill(
    selected: LocalUsageMode,
    enabled: Boolean,
    onSelect: (LocalUsageMode) -> Unit,
) {
    val colors = DsTheme.colors
    val backgroundState = LocalAppBackgroundState.current
    val containerColor = colors.wallpaperSurface(
        level = WallpaperSurfaceLevel.FLOATING,
        region = BackgroundRegion.TOP,
        base = colors.bgModulePlatform,
    )

    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 264.dp).height(56.dp),
        shape = DsShapes.pillFull,
        color = containerColor,
        border = BorderStroke(1.dp, colors.borderL2),
        tonalElevation = 0.dp,
        shadowElevation = if (backgroundState.hasImage) 1.dp else 0.dp,
    ) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val segmentWidth = maxWidth / 2
            val indicatorOffset by animateDpAsState(
                targetValue = if (selected == LocalUsageMode.CHAT) 0.dp else segmentWidth,
                animationSpec = DsAnimations.segmentSlide,
                label = "usage mode indicator",
            )
            Surface(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .height(48.dp),
                shape = DsShapes.pillFull,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, BackgroundRegion.TOP, colors.bgLayer1),
                border = BorderStroke(1.dp, colors.borderL1),
                shadowElevation = 2.dp,
            ) {}
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    LocalUsageMode.CHAT to R.string.local_usage_chat,
                    LocalUsageMode.WORK to R.string.local_usage_work,
                ).forEach { (mode, labelRes) ->
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .height(48.dp)
                            .clip(DsShapes.pillFull)
                            .selectable(
                                selected = selected == mode,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(mode) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = (if (selected == mode) DsType.std14Strong else DsType.std14).withReadingWeight(),
                            color = when {
                                !enabled -> colors.labelTertiary
                                selected == mode -> colors.labelPrimary
                                else -> colors.labelSecondary
                            },
                        )
                    }
                }
            }
        }
    }
}
