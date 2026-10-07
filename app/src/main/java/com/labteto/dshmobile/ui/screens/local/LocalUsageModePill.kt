package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.core.animateDpAsState
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
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

internal fun localHarnessModeSwitchEnabled(
    current: LocalUsageMode,
    running: Boolean,
): Boolean = !running || current == LocalUsageMode.WORK

@Composable
internal fun LocalUsageModePill(
    selected: LocalUsageMode,
    enabled: Boolean,
    onSelect: (LocalUsageMode) -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 216.dp)
            .height(44.dp),
        shape = DsShapes.row,
        color = colors.bgModulePlatform,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.padding(3.dp)) {
            val segmentWidth = maxWidth / 2
            val thumbOffset by animateDpAsState(
                targetValue = if (selected == LocalUsageMode.CHAT) 0.dp else segmentWidth,
                animationSpec = DsAnimations.segmentSlide,
                label = "usageModeThumb",
            )
            Surface(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .fillMaxHeight(),
                shape = DsShapes.row,
                color = colors.bgLayer1,
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
                                onClick = { onSelect(mode) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = (if (selected == mode) DsType.small13Strong else DsType.small13)
                                .withReadingWeight(),
                            color = when {
                                !enabled -> colors.labelCaption
                                selected == mode -> colors.labelPrimary
                                else -> colors.labelTertiary
                            },
                        )
                    }
                }
            }
        }
    }
}
