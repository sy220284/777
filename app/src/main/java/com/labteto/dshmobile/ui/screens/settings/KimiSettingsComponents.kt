package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

@Composable
internal fun KimiSettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
internal fun KimiSettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = DsType.std14, color = colors.labelPrimary)
            subtitle?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary, maxLines = 2) }
        }
        value?.let {
            Text(it, modifier = Modifier.weight(0.45f), style = DsType.caption11, color = colors.labelTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (onClick != null) Icon(FeatherIcons.ChevronRight, contentDescription = null,
            tint = colors.labelCaption, modifier = Modifier.size(16.dp))
    }
}
