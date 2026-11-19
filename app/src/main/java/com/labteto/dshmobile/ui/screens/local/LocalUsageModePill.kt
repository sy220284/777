package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import com.shenyan.designsystem.components.SyFlatSurface
import com.shenyan.designsystem.components.SyMaterialSurface
import com.shenyan.designsystem.components.SyMaterialTier
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
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyColors
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.syReadableInk
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.SyShapes
import com.labteto.dshmobile.ui.theme.SyType
import com.labteto.dshmobile.ui.theme.withReadingWeight

internal fun localHarnessModeSwitchEnabled(
    current: LocalUsageMode,
    running: Boolean,
): Boolean = !running || current == LocalUsageMode.WORK

/** Resolve track and thumb from the active palette and a sampled wallpaper hue. */
internal fun drawerModeSurfaceColors(colors: SyColors, wallpaperTone: Color?): Pair<Color, Color> {
    val neutral = lerp(colors.canvas, colors.selected, .42f)
    val track = wallpaperTone?.let { lerp(neutral, it, .22f) } ?: neutral
    return track to lerp(track, colors.materialL5, .62f)
}

@Composable
internal fun LocalUsageModePill(
    selected: LocalUsageMode,
    enabled: Boolean,
    onSelect: (LocalUsageMode) -> Unit,
) {
    val scene = LocalSySceneBackdrop.current
    val colors = LocalSyColors.current
    val (track, thumb) = drawerModeSurfaceColors(
        colors, scene?.takeIf { it.image != null }?.regionColors?.firstOrNull(),
    )
    val pulse = com.labteto.dshmobile.ui.components.rememberHapticPulse()
    SyMaterialSurface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 216.dp)
            .heightIn(min = SyMetric.touch),
        tier = SyMaterialTier.L2_BAR,
        shape = SyShapes.row,
        tint = track.copy(alpha = .14f),
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
            Box(Modifier.matchParentSize()) {
                SyFlatSurface(
                    modifier = Modifier.offset(x = thumbOffset).width(segmentWidth).fillMaxHeight(),
                    shape = SyShapes.row,
                    color = thumb,
                ) {}
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                listOf(
                    LocalUsageMode.CHAT to R.string.local_usage_chat,
                    LocalUsageMode.WORK to R.string.local_usage_work,
                ).forEach { (mode, labelRes) ->
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .heightIn(min = SyMetric.touch)
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
                            style = SyType.navigationSupporting.copy(
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold,
                            ).withReadingWeight(),
                            color = syReadableInk(colors.text, if (selected == mode) thumb else track),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
