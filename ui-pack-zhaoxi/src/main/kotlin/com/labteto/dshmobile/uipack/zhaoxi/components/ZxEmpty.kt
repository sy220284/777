package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/**
 * ZxEmpty — 空态组件（E8.8 实装，作为骨架期占位组件同步使用）。
 * 规格：52dp 图标容器 + 一句结论 + 一句引导（compose/朝夕-Compose实施规格.md C 节）。
 */
@Composable
fun ZxEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 15.sp, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 12.5.sp, color = c.ink2, lineHeight = 19.sp)
    }
}
