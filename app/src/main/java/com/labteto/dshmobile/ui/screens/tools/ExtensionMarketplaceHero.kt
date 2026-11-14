package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.foundation.SyMetric

/** A single compact entrance instead of several disconnected management forms. */
@Composable
internal fun ExtensionMarketplaceHero(
    skillsOnly: Boolean,
    installedCount: Int,
    discoverableCount: Int,
    connectedCount: Int,
) {
    Box(
        Modifier.fillMaxWidth()
            .testTag("extension-market-hero")
            .background(
                Brush.linearGradient(listOf(Color(0xFF172C38), Color(0xFF244D50), Color(0xFF34575A))),
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                Icon(
                    FeatherIcons.Sparkles, contentDescription = null,
                    tint = Color(0xFFE8C99D),
                )
                Text("777  EXTENSION STUDIO", style = DsType.caption11, color = Color(0xFFC9E2DB))
            }
            Text(
                if (skillsOnly) "为你的工作添加新技能" else "让工具随手可用",
                style = DsType.title20,
                color = Color.White,
            )
            Text(
                if (skillsOnly) "官方精选 · 本地安装 · 按需调用"
                else "发现、安装、连接，一处完成",
                style = DsType.small13,
                color = Color(0xFFD5E4DF),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MarketHeroMetric("$installedCount", "已安装")
                MarketHeroMetric("$discoverableCount", "待发现")
                if (!skillsOnly) MarketHeroMetric("$connectedCount", "服务扩展")
            }
        }
    }
}

@Composable
private fun MarketHeroMetric(count: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(count, style = DsType.std14Strong, color = Color.White)
        Text(description, style = DsType.caption11, color = Color(0xFFB9D3CD))
    }
}
