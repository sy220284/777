package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
 * 统一单选行 for short-lived bottom sheets.
 * The explanation belongs to the same touch target as the action.
 */
@Composable
fun DsSheetChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    switchChecked: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().then(
            if (switchChecked != null) Modifier.toggleable(
                value = switchChecked, enabled = enabled, role = Role.Switch,
                onValueChange = { onClick() },
            ) else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        ),
        shape = DsShapes.row,
        color = if (selected) colors.bgModulePlatform else androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (subtitle == null) 56.dp else 72.dp)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(24.dp))
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
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (switchChecked != null) {
                DsSwitch(
                    checked = switchChecked,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                    enabled = enabled,
                )
            } else {
                trailingText?.let {
                    Text(it, style = DsType.std14.withReadingWeight(), color = colors.labelTertiary)
                }
                if (enabled || trailingText == null) Icon(
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
}
