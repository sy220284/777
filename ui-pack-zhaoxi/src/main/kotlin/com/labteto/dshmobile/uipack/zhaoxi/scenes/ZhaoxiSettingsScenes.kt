package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UiHostPreferencesCommand
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDetentSlider
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxDivider
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxField
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uicontract.UiSceneId
import kotlinx.coroutines.launch
import com.labteto.dshmobile.uihost.UiSceneRenderScope

/**
 * 设置域场景（E8.14 实装）。
 * route.settings 为分组导航；settings.* 目的地共享同一套行组件。
 * 外观/通知走 HostPreferencesUiPort.submit；模型/供应商配置走 SettingsUiPort。
 * 只读诊断（usage/performance/pricing/memory）只呈现快照。
 */

/** route.settings — 设置主页。 */
@Composable
fun ZhaoxiSettingsHomeScene(scope: UiSceneRenderScope) {
    ZxPage("设置", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("模型与服务")
        ZxNavRow("模型", sub = "当前模型 · 供应商 · 路由") { scope.onNavigate(UiSceneId("settings.models")) }
        ZxNavRow("行为与采样", sub = "温度 · 上下文 · 全局默认") { scope.onNavigate(UiSceneId("settings.advanced")) }
        ZxSection("外观与通知")
        ZxNavRow("外观", sub = "主题 · 字级 · 强调色") { scope.onNavigate(UiSceneId("settings.appearance")) }
        ZxNavRow("通知与权限", sub = "系统级开关") { scope.onNavigate(UiSceneId("settings.notifications")) }
        ZxSection("数据")
        ZxNavRow("用量", sub = "token 与费用") { scope.onNavigate(UiSceneId("settings.usage")) }
        ZxNavRow("记忆与日记", sub = "长期记忆 · 回顾") { scope.onNavigate(UiSceneId("settings.memory")) }
        ZxNavRow("备份与迁移", sub = "导出 · 导入") { scope.onNavigate(UiSceneId("settings.session_storage")) }
        ZxSection("关于")
        ZxNavRow("诊断与更新", sub = "版本 · 状态演示") { scope.onNavigate(UiSceneId("settings.diagnostics")) }
    }
}

/** settings.appearance — 外观（HostPreferencesUiPort）。 */
@Composable
fun ZhaoxiAppearanceScene(preferences: HostPreferencesUiPort, scope: UiSceneRenderScope) {
    val snap by preferences.snapshots.collectAsState(initial = UiHostPreferences("system", "stone", 1.0f))
    val jobs = rememberCoroutineScope()
    fun writeAppearance(theme: String = snap.theme, accent: String = snap.accent, textScale: Float = snap.textScale) {
        jobs.launch { preferences.submit(UiHostPreferencesCommand.Appearance(theme, accent, textScale, snap.textWeight,
            snap.transparency, snap.ambientGlow, snap.componentEmphasis, snap.motionScale, snap.adaptiveContrast)) }
    }
    ZxPage("外观", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("主题")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, l) ->
                ZxChip(l, snap.theme == k) {
                    writeAppearance(theme = k)
                }
            }
        }
        ZxSection("强调色")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("stone" to "石灰", "sage" to "鼠尾草", "steel" to "钢蓝", "mauve" to "藕紫", "dusk" to "暮橙")
                .forEach { (k, l) ->
                    ZxChip(l, snap.accent == k) {
                        writeAppearance(accent = k)
                    }
                }
        }
        ZxSection("字号")
        ZxDetentSlider(
            value = ((snap.textScale - 0.9f) / 0.3f).toInt().coerceIn(0, 2),
            steps = 3,
            labels = listOf("小", "标准", "大"),
            onChange = { v -> writeAppearance(textScale = 0.9f + v * 0.3f) },
        )
        ZxFooterNote("外观偏好即时生效 · 写入宿主偏好存储")
    }
}

/** settings.notifications — 通知与系统权限入口。 */
@Composable
fun ZhaoxiNotificationsScene(preferences: HostPreferencesUiPort, scope: UiSceneRenderScope) {
    ZxPage("通知与权限", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxNavRow("系统通知设置", sub = "跳转系统页") { preferences.openSystemNotifications() }
        ZxNavRow("系统权限管理", sub = "通知 · 媒体 · 文件") { preferences.openSystemPermissions() }
        ZxFooterNote("shell.notifications 为宿主直供场景 · 不在本包渲染")
    }
}

/** settings.model / settings.behavior — 模型与行为（SettingsUiPort）。 */
@Composable
fun ZhaoxiModelSettingsScene(settings: SettingsUiPort, scope: UiSceneRenderScope) {
    val snap by settings.state.collectAsState(initial = null)
    ZxPage("模型", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("当前")
        ZxValueRow("模型", value = snap?.toString()?.substringAfter("model=")?.take(32) ?: "—")
        ZxNavRow("供应商与密钥", sub = "配置经 SettingsUiPort.configure*") {}
        ZxSection("采样默认")
        ZxValueRow("温度", value = "全局默认", sub = "人物级覆盖在人物编辑器 · 行为调节")
        ZxFooterNote("保存为 suspend 写路径 · 失败以回执呈现")
    }
}

/** settings.usage — 用量（UsageUiPort 只读）。 */
@Composable
fun ZhaoxiUsageScene(usage: com.labteto.dshmobile.uicontract.UsageUiPort, scope: UiSceneRenderScope) {
    val revision by usage.revision.collectAsState()
    val snapshot = remember(revision) { usage.snapshot() }
    var model by remember { mutableStateOf<String?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    val current = model?.let { snapshot.byModel[it] } ?: snapshot
    ZxPage("模型用量", onBack = { scope.onBack() }) {
        ZxChip("全部模型", model == null) { model = null }
        snapshot.byModel.keys.forEach { id -> ZxChip(id, model == id) { model = id } }
        ZxValueRow("Token 总量", value = "${current.tracked.totalTokens}", sub = "${current.tracked.requestCount} 次请求")
        ZxValueRow("输入 / 输出", value = "${current.tracked.inputTokens} / ${current.tracked.outputTokens}")
        ZxValueRow("估算费用（元）", value = "${current.tracked.estimatedCostCny}")
        current.sessions.forEach { session -> ZxRow(session.title, "${session.aggregate.totalTokens} Token", onClick = { selectedGroup = session.key }) }
        selectedGroup?.let { id ->
            usage.groupDetail(com.labteto.dshmobile.local.TokenUsageGroupKind.SESSION, id, model)?.let { detail ->
                ZxSection(detail.title)
                detail.records.forEach { record -> Text(record.toString(), color = ZhaoxiTheme.colors.ink2) }
            }
        }
    }
}

/** settings.memory / route.diary — 记忆与日记（只读为主）。 */
@Composable
fun ZhaoxiMemoryScene(
    memory: com.labteto.dshmobile.uicontract.MemoryUiPort?,
    scope: UiSceneRenderScope,
) {
    ZxPage("记忆", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (memory == null) {
            com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("记忆未启用", "在模型配置后可用")
        } else {
            ZxValueRow("长期记忆", value = "", sub = "快照由 MemoryUiPort 提供")
            ZxNavRow("公开日记回顾", sub = "route.diary") { scope.onNavigate(UiSceneId("route.diary")) }
        }
    }
}

/** settings.diagnostics — 诊断与更新。 */
@Composable
fun ZhaoxiDiagnosticsScene(
    updateStatus: kotlinx.coroutines.flow.StateFlow<String?>?,
    checkForUpdate: (() -> Unit)?,
    scope: UiSceneRenderScope,
) {
    var demo by remember { mutableStateOf<String?>(null) }
    val status by (updateStatus ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)).collectAsState()
    ZxPage("诊断与更新", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxValueRow("版本", value = "朝夕 E8.17", sub = "独立 UI Pack / Host 契约版本 1")
        ZxValueRow("更新状态", value = status ?: "未检查")
        Spacer(Modifier.height(8.dp))
        ZxButton("检查更新", { checkForUpdate?.invoke() }, Modifier.fillMaxWidth())
        if (demo == "empty") com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty("这里什么都没有", "可以返回继续使用")
        if (demo == "error") com.labteto.dshmobile.uipack.zhaoxi.components.ZxBanner("错误状态示例", com.labteto.dshmobile.uipack.zhaoxi.components.ZxTone.Err)
        ZxSection("界面状态演示")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("空态", false) { demo = "empty" }
            ZxChip("错误态", false) { demo = "error" }
            ZxChip("引导", false) { scope.onNavigate(UiSceneId("shell.onboarding")) }
        }
    }
}

/** settings.backup — 备份与迁移入口页。 */
@Composable
fun ZhaoxiBackupScene(scope: UiSceneRenderScope) {
    ZxPage("备份与迁移", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxNavRow("导出人物", sub = "经人物图集 · 迁移入口在图集内") { scope.onNavigate(UiSceneId("route.persona_gallery")) }
        ZxNavRow("工作区文件", sub = "route.workspace") { scope.onNavigate(UiSceneId("route.workspace")) }
        ZxFooterNote("破坏性迁移只经 ZxDialog 确认")
    }
}
