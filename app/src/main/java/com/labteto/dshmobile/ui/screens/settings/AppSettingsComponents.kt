package com.labteto.dshmobile.ui.screens.settings

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * One quiet settings group: a single surface with independent, full-width tap targets.
 * Section headers live outside it so the content reads as a menu, not stacked cards.
 */
@Composable
internal fun AppSettingsSection(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (compact) DsShapes.settingsGroup else DsShapes.block,
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(vertical = DsSpacing.tiny), content = content)
    }
}

@Composable
internal fun AppSettingsMenuGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            text = title,
            style = DsType.navigationSection.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
            modifier = Modifier.padding(start = DsSpacing.medium),
        )
        AppSettingsSection(compact = true, content = content)
    }
}

@Composable
internal fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        style = DsType.navigationSection.withReadingWeight(),
        color = DsTheme.colors.labelSecondary,
        modifier = Modifier.padding(start = DsSpacing.comfortable, top = DsSpacing.medium, bottom = DsSpacing.small),
    )
}

@Composable
internal fun AppSettingsDivider() {
    val colors = DsTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.comfortable + 24.dp + DsSpacing.medium, end = DsSpacing.comfortable)
            .height(1.dp)
            .background(colors.borderL1),
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
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 56.dp else 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DsSpacing.comfortable, vertical = if (compact) DsSpacing.small else DsSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = if (compact) colors.labelSecondary else colors.labelPrimary,
                modifier = Modifier.size(if (compact) 22.dp else 24.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text(
                title,
                style = (if (compact) DsType.settingsItem else DsType.navigationItem).withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = DsType.navigationSupporting.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                modifier = Modifier.widthIn(max = 108.dp),
                style = DsType.navigationSupporting.withReadingWeight(),
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        trailing?.invoke()
        if (onClick != null) {
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
