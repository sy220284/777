package com.labteto.dshmobile.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * Shared phone-page empty/error state.
 *
 * The icon plate gives an otherwise empty page a visual anchor without introducing another card;
 * copy explains the state, and at most one recovery/next-step action stays close to that explanation.
 */
@Composable
fun DsPageEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
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
            modifier = Modifier.size(52.dp),
            shape = DsShapes.block,
            color = colors.hover,
            tonalElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            title,
            modifier = Modifier.widthIn(max = 320.dp),
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            modifier = Modifier.widthIn(max = 320.dp),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            DsButton(
                text = actionText,
                onClick = onAction,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
