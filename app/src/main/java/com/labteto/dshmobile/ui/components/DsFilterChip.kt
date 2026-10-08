package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Neutral capsule for browsing categories, with a full touch target around its visual surface. */
@Composable
fun DsFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = DsShapes.pillFull,
            color = if (selected) colors.bgModulePlatform else Color.Transparent) {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = DsType.small13.withReadingWeight(),
                color = if (selected) colors.labelPrimary else colors.labelSecondary)
        }
    }
}
