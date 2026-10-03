package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * Semantic loading state for full pages and large page regions.
 *
 * It names what is loading instead of falling back to a generic spinner. The only continuous
 * motion is the live icon breath and disappears from composition as soon as loading ends.
 */
@Composable
fun DsPageLoadingState(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val transition = rememberInfiniteTransition(label = "semanticPageLoading")
    val pulse by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = DsAnimations.semanticPulse,
            repeatMode = RepeatMode.Reverse,
        ),
        label = "semanticPageLoadingPulse",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.xxlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            DsSpacing.small,
            Alignment.CenterVertically,
        ),
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer { alpha = pulse },
            shape = DsShapes.block,
            color = colors.accentTertiary,
            tonalElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = label,
            modifier = Modifier.widthIn(max = 320.dp),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
