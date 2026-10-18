package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/** Native vector resources generated from the original E8.17 SVG geometry and fill/stroke styles. */
@Composable
fun ZxIcon(name: String, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(ZhaoxiIconResources.all.getValue(name)), description,
        modifier.size(22.dp), tint = ZhaoxiTheme.colors.ink2)
}
