package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiSceneStatus
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxIcon
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeleton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSkeletonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTopBar
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/**
 * 朝夕场景公共骨架（E8.14）：页面壳 + 页面级状态机 + 设置行系列。
 * 页面级骨架统一：LOADING→ZxSkeleton，EMPTY→ZxEmpty，ERROR→ZxBanner(Err)+重试。
 */

@Composable
fun ZxPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    menu: Boolean = false,
    topRight: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        // Keep the scene surface transparent so E8.17 ambient light remains visible.
        ZxTopBar(title, onNav = { onBack?.invoke() }, canBack = !menu, showNav = onBack != null) { topRight() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            content()
        }
    }
}

/** 页面级状态机：CONTENT 才渲染业务内容。 */
@Composable
fun ZxStateContent(
    status: UiSceneStatus,
    error: String?,
    emptyTitle: String,
    emptyHint: String,
    onRetry: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    when (status) {
        UiSceneStatus.LOADING -> Column { repeat(4) { ZxSkeleton(ZxSkeletonVariant.Row, Modifier.padding(vertical = 6.dp)) } }
        UiSceneStatus.EMPTY -> Box(Modifier.fillMaxWidth().padding(top = 96.dp)) { ZxEmpty(emptyTitle, emptyHint) }
        UiSceneStatus.ERROR -> Column {
            ZxBanner(error ?: "加载失败", ZxTone.Err, action = "重试" to onRetry)
            Box(Modifier.fillMaxWidth().padding(top = 64.dp)) {
                ZxEmpty("没连上", "已保留本地草稿与账本 · 恢复后自动续传")
            }
        }
        UiSceneStatus.CONTENT -> Column(content = content)
    }
}

// ---- 设置行系列（不设新组件；供 settings 22 目的地复用）----

@Composable
fun ZxSettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ZhaoxiTheme.colors.bg2)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
fun ZxNavRow(label: String, sub: String = "", value: String = "", onClick: () -> Unit) {
    val c = ZhaoxiTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val designIcon = when {
            label.contains("模型") -> "sparkle"
            label.contains("通知") -> "alert"
            label.contains("外观") -> "palette"
            label.contains("用量") || label.contains("费用") -> "usage"
            label.contains("记忆") -> "diary"
            label.contains("备份") || label.contains("迁移") -> "files"
            label.contains("诊断") || label.contains("更新") -> "info"
            label.contains("工具") -> "tools"
            label.contains("任务") -> "tasks"
            label.contains("人物") -> "user"
            else -> null
        }
        if (designIcon != null) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxIcon(designIcon)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = c.ink3, lineHeight = 16.sp)
        }
        if (value.isNotEmpty()) {
            Text(value, fontSize = 12.sp, color = c.ink2,
                fontFamily = FontFamily.Monospace)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        ZxIcon("chevron-right", modifier = Modifier.width(18.dp), description = null)
    }
}

@Composable
fun ZxSwitchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ZhaoxiTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = c.ink3, lineHeight = 16.sp)
        }
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxSwitch(checked, onChange, contentDescription = label)
    }
}

@Composable
fun ZxValueRow(label: String, sub: String = "", value: String, mono: Boolean = false) {
    val c = ZhaoxiTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = c.ink)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = c.ink3, lineHeight = 16.sp)
        }
        Text(value, fontSize = 12.sp, color = c.ink3,
            fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
fun ZxDangerRow(label: String, sub: String, onClick: () -> Unit) {
    val c = ZhaoxiTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = c.err)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = c.ink3, lineHeight = 16.sp)
        }
        ZxIcon("chevron-right", modifier = Modifier.width(18.dp), description = null)
    }
}

/** 通用行（会话/文件/任务列表共用）。 */
@Composable
fun ZxRow(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = { Text("›", fontSize = 12.sp, color = ZhaoxiTheme.colors.ink3) },
) {
    val c = ZhaoxiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = c.ink,
                fontFamily = if (mono) FontFamily.Monospace else null)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = c.ink3, lineHeight = 16.sp)
        }
        trailing()
    }
}
