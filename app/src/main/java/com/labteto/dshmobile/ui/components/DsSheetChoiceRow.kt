package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * Kimi-like single-choice row for short-lived bottom sheets.
 * The explanation belongs to the same touch target as the action.
 */
@Composable
fun DsSheetChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.row,
        color = if (selected) colors.bgModulePlatform else colors.bgBase,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(20.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    title,
                    style = DsType.std14Strong.withReadingWeight(),
                    color = if (danger) colors.error else colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = when {
                    danger -> colors.error
                    selected -> colors.accent
                    else -> colors.labelCaption
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
