package com.labteto.dshmobile.ui.screens.settings

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.components.SyIconBox
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * One quiet settings group: a single surface with independent, full-width tap targets.
 * Section headers live outside it so the content reads as a menu, not stacked cards.
 */
@Composable
internal fun AppSettingsSection(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // Only headings and dividers separate entries; no plate or shadow.
    Column(
        modifier = Modifier.fillMaxWidth().testTag("settingsOpenSection")
            .padding(vertical = if (compact) SyMetric.gapXs else SyMetric.gapSm),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
internal fun AppSettingsMenuGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        com.shenyan.designsystem.components.SySectionHeader(title,
            modifier = Modifier.padding(start = SyMetric.gapLg, top = SyMetric.gapMd))
        AppSettingsSection(compact = true, content = content)
    }
}

@Composable
internal fun SettingsGroupTitle(text: String) {
    com.shenyan.designsystem.components.SySectionHeader(text,
        modifier = Modifier.padding(start = SyMetric.gapMd, top = SyMetric.gapLg, bottom = SyMetric.gapSm))
}

@Composable
internal fun AppSettingsDivider() {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = SyMetric.gapSection + 32.dp + SyMetric.gapLg, end = SyMetric.gapSection)
            .height(1.dp)
            .background(colors.border),
    )
}

@Composable
internal fun AppSettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    reservedEntryHeight: Dp? = null,
    showChevron: Boolean = true,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val stackedValue = LocalDensity.current.fontScale >= 1.2f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = reservedEntryHeight ?: if (compact) 56.dp else 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SyMetric.gapSection, vertical = if (compact) SyMetric.gapMd else SyMetric.gapLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
    ) {
        icon?.let { SyIconBox(icon = it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
            Text(
                title,
                style = (if (compact) DsType.settingsItem else DsType.navigationItem).withReadingWeight(),
                color = colors.text,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = DsType.navigationSupporting.withReadingWeight(),
                    color = colors.textSecondary,
                )
            }
            if (stackedValue && !value.isNullOrBlank()) {
                Text(value, style = DsType.navigationSupporting.withReadingWeight(),
                    color = colors.textSecondary)
            }
        }
        value?.takeIf { it.isNotBlank() && !stackedValue }?.let {
            Text(
                it,
                modifier = Modifier.widthIn(max = 108.dp),
                style = DsType.navigationSupporting.withReadingWeight(),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        trailing?.invoke()
        if (onClick != null && showChevron) {
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
