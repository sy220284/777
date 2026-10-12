package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Reference SearchBar: 14dp glass plate, solid on focus and warm focus outline.
 * This is the common search surface used by both original and retained feature entries.
 * The optional shortcut is displayed only when the parent actually implements it.
 */
@Composable
fun DsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
) {
    val colors = DsTheme.colors
    val sy = LocalSyPalette.current
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        targetValue = if (focused) sy.solid
            else sy.l3,
        animationSpec = DsAnimations.tabBackground, label = "searchGlassFocus",
    )
    val outline by animateColorAsState(
        targetValue = if (focused) sy.peach else sy.border,
        animationSpec = DsAnimations.tabBackground, label = "searchFocusLine",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = fill,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, outline),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(FeatherIcons.Search, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = sy.tertiaryText)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = DsType.std14.withReadingWeight().copy(color = sy.text),
                    cursorBrush = SolidColor(sy.peach),
                    decorationBox = { textField ->
                        Box {
                            if (value.isEmpty() && !focused) {
                                Text(placeholder, style = DsType.std14.withReadingWeight(),
                                    color = sy.tertiaryText)
                            }
                            textField()
                        }
                    },
                )
            }
            if (!shortcut.isNullOrBlank()) {
                Surface(color = sy.l2, shape = DsShapes.chip,
                    border = BorderStroke(1.dp, sy.border)) {
                    Text(shortcut, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = DsType.caption11.copy(fontFamily = DsType.codeFont).withReadingWeight(),
                        color = colors.labelSecondary)
                }
            }
        }
    }
}
