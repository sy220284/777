package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Compact phone-page empty state: explain why the page is empty and offer one useful next action. */
@Composable
fun DsPageEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.xxlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = DsTheme.colors.labelTertiary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            title,
            style = DsType.base16Strong.withReadingWeight(),
            color = DsTheme.colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelTertiary,
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
