package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.ui.components.FeatherIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Miniature Clear Realm preview: canvas, grouped surface, content hierarchy and composer. */
@Composable
internal fun ThemePreviewBlock(
    themeKey: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val palette = when (themeKey) {
        "dark" -> com.labteto.dshmobile.ui.theme.DsThemeTokens.dark
        "matte_black" -> com.labteto.dshmobile.ui.theme.DsThemeTokens.matteBlack
        else -> com.labteto.dshmobile.ui.theme.DsThemeTokens.light
    }
    Column(
        modifier = modifier
            .clip(DsShapes.cube)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.borderL2,
                shape = DsShapes.cube,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            if (themeKey == "system") {
                Row(Modifier.fillMaxSize()) {
                    ThemeMiniCanvas(
                        background = com.labteto.dshmobile.ui.theme.DsThemeTokens.light.bgBase,
                        surface = com.labteto.dshmobile.ui.theme.DsThemeTokens.light.bgLayer1,
                        modifier = Modifier.weight(1f),
                    )
                    ThemeMiniCanvas(
                        background = com.labteto.dshmobile.ui.theme.DsThemeTokens.dark.bgBase,
                        surface = com.labteto.dshmobile.ui.theme.DsThemeTokens.dark.bgLayer1,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                ThemeMiniCanvas(
                    background = palette.bgBase,
                    surface = palette.bgLayer1,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Icon(
                    FeatherIcons.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp),
                )
            }
        }
        Text(
            label,
            style = DsType.small13.withReadingWeight(),
            color = if (selected) colors.accent else colors.labelSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ThemeMiniCanvas(
    background: Color,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(6.dp)
                    .background(surface, RoundedCornerShape(3.dp)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(surface, RoundedCornerShape(8.dp)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(10.dp)
                    .background(surface, RoundedCornerShape(6.dp)),
            )
        }
    }
}
