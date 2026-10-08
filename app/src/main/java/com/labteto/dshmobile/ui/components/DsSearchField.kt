package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
fun DsSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Surface(modifier = modifier.fillMaxWidth(), shape = DsShapes.pillFull, color = colors.bgLayer1) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(FeatherIcons.Search, null, Modifier.size(22.dp), tint = colors.labelPrimary)
            BasicTextField(value, onValueChange, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = DsType.base16.withReadingWeight().copy(color = colors.labelPrimary),
                cursorBrush = SolidColor(colors.accent), decorationBox = { field ->
                    Box { if (value.isEmpty()) Text(placeholder, style = DsType.base16.withReadingWeight(), color = colors.labelTertiary); field() }
                })
        }
    }
}
