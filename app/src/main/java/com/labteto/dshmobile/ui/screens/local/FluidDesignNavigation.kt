package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Design 02: five navigable areas, not a progress estimate or five fake execution stages. */
@Composable
internal fun FluidSectionNavigation(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<String> = emptyList(),
    numbered: Boolean = false,
    attention: Int? = null,
    tag: String = "fluidSection",
) {
    val colors = DsTheme.colors
    val pulse = rememberHapticPulse()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val fill by animateColorAsState(
                if (active) colors.accentTertiary
                else colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                tween(140), label = "sectionFill",
            )
            val radius by animateDpAsState(if (active) 13.dp else 8.dp, tween(500), label = "sectionShape")
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("$tag:$index")
                    .selectable(active, role = Role.Tab, onClick = {
                        if (!active) pulse()
                        onSelect(index)
                    }),
                shape = RoundedCornerShape(radius),
                color = fill,
                border = BorderStroke(1.dp, when {
                    active -> colors.accent
                    attention == index -> colors.warnLabel
                    else -> colors.borderL1
                }),
            ) {
                Column(Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (numbered) Text((index + 1).toString(), style = DsType.caption11,
                        color = colors.labelTertiary)
                    Text(label, style = DsType.small13Strong.withReadingWeight(),
                        color = if (active) colors.labelPrimary else colors.labelSecondary)
                    counts.getOrNull(index)?.let { Text(it, style = DsType.caption11,
                        color = colors.labelSecondary) }
                }
            }
        }
    }
}

/** Swap preserves the selected section identity and uses the supplied 380 ms curve. */
@Composable
internal fun FluidSectionSwap(section: Int, modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit) {
    AnimatedContent(targetState = section, modifier = modifier, label = "fluidSectionSwap",
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(DsAnimations.featurePagePeer) { direction * 60 } + fadeIn(tween(380)))
                .togetherWith(slideOutHorizontally(DsAnimations.featurePagePeer) { -direction * 60 } + fadeOut(tween(140)))
        }, content = { content(it) })
}
