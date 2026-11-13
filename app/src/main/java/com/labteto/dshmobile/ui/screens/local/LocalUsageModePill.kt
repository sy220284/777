package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import com.shenyan.designsystem.foundation.LocalSySceneBackdrop
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

internal fun localHarnessModeSwitchEnabled(
    current: LocalUsageMode,
    running: Boolean,
): Boolean = !running || current == LocalUsageMode.WORK

/** Borrow the wallpaper's hue while keeping both black mode labels legible. */
internal fun drawerModeSurfaceColors(wallpaperTone: Color?): Pair<Color, Color> {
    val neutral = Color(0xFFF1EDE7)
    val track = if (wallpaperTone == null) neutral else lerp(neutral, wallpaperTone, 0.22f)
    return track to lerp(track, Color.White, 0.28f)
}

@Composable
internal fun LocalUsageModePill(
    selected: LocalUsageMode,
    enabled: Boolean,
    onSelect: (LocalUsageMode) -> Unit,
) {
    val scene = LocalSySceneBackdrop.current
    val hasWallpaper = scene?.image != null
    val (track, thumb) = drawerModeSurfaceColors(
        scene?.takeIf { it.image != null }?.regionColors?.firstOrNull(),
    )
    val pulse = com.labteto.dshmobile.ui.components.rememberHapticPulse()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 216.dp)
            .height(48.dp),
        shape = DsShapes.row,
        color = track.copy(alpha = if (hasWallpaper) 0.92f else 1f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.padding(3.dp)) {
            val segmentWidth = maxWidth / 2
            val thumbOffset by animateDpAsState(
                targetValue = if (selected == LocalUsageMode.CHAT) 0.dp else segmentWidth,
                animationSpec = tween(
                    com.shenyan.designsystem.foundation.SyMotion.duration(
                        com.shenyan.designsystem.foundation.SyMotion.selectMs,
                        com.shenyan.designsystem.foundation.LocalSyReduceMotion.current,
                        com.shenyan.designsystem.foundation.LocalSyMotionTempo.current,
                    ),
                    easing = com.shenyan.designsystem.foundation.SyMotion.ease,
                ),
                label = "usageModeThumb",
            )
            Surface(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .fillMaxHeight(),
                shape = DsShapes.row,
                color = thumb.copy(alpha = if (hasWallpaper) 0.96f else 1f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {}
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                listOf(
                    LocalUsageMode.CHAT to R.string.local_usage_chat,
                    LocalUsageMode.WORK to R.string.local_usage_work,
                ).forEach { (mode, labelRes) ->
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected == mode,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { if (selected != mode) pulse(); onSelect(mode) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = DsType.navigationSupporting.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold,
                            ).withReadingWeight(),
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }
}
