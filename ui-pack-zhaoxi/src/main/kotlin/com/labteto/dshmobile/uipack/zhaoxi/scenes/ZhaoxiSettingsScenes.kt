package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
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

/** route.settings — 按 E8.18 四大信息域组织设置入口。 */
@Composable
fun ZhaoxiSettingsHomeScene(scope: UiSceneRenderScope) {
    ZxPage("设置", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("对话与体验")
        ZxNavRow("外观", sub = "明暗主题 · 强调色 · 环境光") { scope.onNavigate(UiSceneId("settings.appearance")) }
        ZxNavRow("聊天偏好", sub = "对话风格 · 个人规则") { scope.onNavigate(UiSceneId("settings.chat")) }
        ZxNavRow("记忆", sub = "记忆概览 · 管理") { scope.onNavigate(UiSceneId("settings.memory")) }
        ZxNavRow("通知", sub = "提醒种类 · 免打扰") { scope.onNavigate(UiSceneId("settings.notifications")) }

        ZxSection("模型与账户")
        ZxNavRow("模型", sub = "当前模型 · API Key · 协议") { scope.onNavigate(UiSceneId("settings.models")) }
        ZxNavRow("模型性能", sub = "推理强度 · 温度") { scope.onNavigate(UiSceneId("settings.model_performance")) }
        ZxNavRow("ChatGPT 账户", sub = "授权与登录状态") { scope.onNavigate(UiSceneId("settings.account")) }
        ZxNavRow("套餐额度", sub = "MiniMax 模型额度") { scope.onNavigate(UiSceneId("settings.minimax_quota")) }

        ZxSection("Agent 与安全")
        ZxNavRow("权限与设备", sub = "通知与媒体权限状态") { scope.onNavigate(UiSceneId("settings.permissions")) }

        ZxSection("数据与系统")
        ZxNavRow("用量统计", sub = "模型消耗 · 请求记录") { scope.onNavigate(UiSceneId("settings.usage")) }
        ZxNavRow("会话存储", sub = "占用空间 · 压缩 · 导出") { scope.onNavigate(UiSceneId("settings.session_storage")) }
        ZxNavRow("高级与运行", sub = "运行限制 · 规则") { scope.onNavigate(UiSceneId("settings.advanced")) }
        ZxNavRow("诊断与更新", sub = "版本 · 检查更新") { scope.onNavigate(UiSceneId("settings.diagnostics")) }
    }
}

/** settings.appearance — 外观（HostPreferencesUiPort）。 */
@Composable
fun ZhaoxiAppearanceScene(preferences: HostPreferencesUiPort, scope: UiSceneRenderScope) {
    val snap by preferences.snapshots.collectAsState(initial = UiHostPreferences("system", "stone", 1.0f))
    val jobs = rememberCoroutineScope()
    fun writeAppearance(
        theme: String = snap.theme,
        accent: String = snap.accent,
        textScale: Float = snap.textScale,
        ambientGlow: Float = snap.ambientGlow,
        transparency: Float = snap.transparency,
        motionScale: Float = snap.motionScale,
    ) {
        jobs.launch { preferences.submit(UiHostPreferencesCommand.Appearance(theme, accent, textScale, snap.textWeight,
            transparency, ambientGlow, snap.componentEmphasis, motionScale, snap.adaptiveContrast)) }
    }
    var glow by remember(snap.ambientGlow) { mutableFloatStateOf(snap.ambientGlow) }
    var glass by remember(snap.transparency) { mutableFloatStateOf(snap.transparency) }
    ZxPage("外观", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("主题")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, l) ->
                ZxChip(l, snap.theme == k) {
                    writeAppearance(theme = k)
                }
            }
        }
        ZxSection("强调色")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("stone" to "石灰", "sage" to "鼠尾草", "steel" to "钢蓝", "mauve" to "藕紫", "dusk" to "暮橙")
                .forEach { (k, l) ->
                    ZxChip(l, snap.accent == k) {
                        writeAppearance(accent = k)
                    }
                }
        }
        ZxSection("字号")
        ZxDetentSlider(
            value = listOf(0.9f, 1f, 1.3f).indices.minBy { kotlin.math.abs(listOf(0.9f, 1f, 1.3f)[it] - snap.textScale) },
            steps = 3,
            labels = listOf("小", "标准", "大"),
            onChange = { v -> writeAppearance(textScale = listOf(0.9f, 1f, 1.3f)[v]) },
        )
        ZxSection("背景与材质")
        Text("环境光强度 · ${(glow * 100).toInt()}%", fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        Slider(
            value = glow, onValueChange = { glow = it },
            onValueChangeFinished = { writeAppearance(ambientGlow = glow) },
            colors = SliderDefaults.colors(
                thumbColor = ZhaoxiTheme.colors.accent,
                activeTrackColor = ZhaoxiTheme.colors.accent,
            ),
        )
        Text("玻璃透明度 · ${(glass * 100).toInt()}%", fontSize = 12.sp, color = ZhaoxiTheme.colors.ink2)
        Slider(
            value = glass, onValueChange = { glass = it },
            onValueChangeFinished = { writeAppearance(transparency = glass) },
            colors = SliderDefaults.colors(
                thumbColor = ZhaoxiTheme.colors.accent,
                activeTrackColor = ZhaoxiTheme.colors.accent,
            ),
        )
        ZxSection("动效")
        ZxSwitchRow("界面动效", "关闭后停用场景与组件过渡动画",
            checked = snap.motionScale > 0f,
            onChange = { writeAppearance(motionScale = if (it) 1f else 0f) })
        
    }
}

/** settings.notifications — 五类通知和免打扰均使用现有真实 HostPreferences。 */
@Composable
fun ZhaoxiNotificationsScene(preferences: HostPreferencesUiPort, scope: UiSceneRenderScope) {
    val snap by preferences.snapshots.collectAsState(initial = UiHostPreferences())
    val jobs = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var startTime by remember(snap.quietStartMinute) {
        mutableStateOf("%02d:%02d".format(snap.quietStartMinute / 60, snap.quietStartMinute % 60))
    }
    var endTime by remember(snap.quietEndMinute) {
        mutableStateOf("%02d:%02d".format(snap.quietEndMinute / 60, snap.quietEndMinute % 60))
    }
    fun update(
        jobsEnabled: Boolean = snap.notifyJobs,
        resultsEnabled: Boolean = snap.notifyResults,
        failuresEnabled: Boolean = snap.notifyFailures,
        actionEnabled: Boolean = snap.notifyNeedsAction,
        chatEnabled: Boolean = snap.notifyChat,
        quietEnabled: Boolean = snap.quietEnabled,
        start: Int = snap.quietStartMinute,
        end: Int = snap.quietEndMinute,
    ) {
        jobs.launch {
            status = if (preferences.submit(UiHostPreferencesCommand.Notifications(
                jobsEnabled, resultsEnabled, failuresEnabled, actionEnabled,
                chatEnabled, quietEnabled, start, end,
            ))) "已保存" else "未能保存通知设置"
        }
    }
    fun minutes(text: String): Int? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
        val h = match.groupValues[1].toInt()
        val m = match.groupValues[2].toInt()
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
    ZxPage("通知", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("提醒类型")
        ZxSwitchRow("任务执行", "定时任务和自动化事件", snap.notifyJobs) { update(jobsEnabled = it) }
        ZxSwitchRow("执行结果", "任务完成后的结果提示", snap.notifyResults) { update(resultsEnabled = it) }
        ZxSwitchRow("失败提醒", "任务或工具运行失败", snap.notifyFailures) { update(failuresEnabled = it) }
        ZxSwitchRow("需要操作", "待审批与待确认项目", snap.notifyNeedsAction) { update(actionEnabled = it) }
        ZxSwitchRow("聊天消息", "新的聊天消息提醒", snap.notifyChat) { update(chatEnabled = it) }
        ZxSection("免打扰")
        ZxSwitchRow("启用免打扰", "在设定时段减少提醒", snap.quietEnabled) { update(quietEnabled = it) }
        if (snap.quietEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    startTime, { startTime = it }, Modifier.weight(1f), placeholder = "开始 HH:mm")
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    endTime, { endTime = it }, Modifier.weight(1f), placeholder = "结束 HH:mm")
            }
            ZxButton("保存免打扰时间", {
                val start = minutes(startTime)
                val end = minutes(endTime)
                if (start == null || end == null) status = "请输入正确时间（00:00–23:59）"
                else update(start = start, end = end)
            })
        }
        status?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        ZxNavRow("系统通知权限") { preferences.openSystemNotifications() }
    }
}

/** settings.permissions — 呈现真实系统权限事实，跳转系统授权页而不伪造开关。 */
@Composable
fun ZhaoxiPermissionsScene(preferences: HostPreferencesUiPort, scope: UiSceneRenderScope, settings: SettingsUiPort? = null) {
    val notification = preferences.notificationPermissionGranted()
    val media = preferences.mediaPermissionSnapshot()
    ZxPage("权限与设备", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        settings?.let { ZhaoxiApprovalModeSelector(it) }
        ZxSection("系统通知")
        ZxValueRow("通知权限", value = when (notification) {
            true -> "已授权"
            false -> "未授权"
            null -> "系统未提供状态"
        })
        ZxNavRow("管理通知权限") { preferences.openSystemNotifications() }
        ZxSection("媒体权限")
        if (media != null) {
            ZxValueRow("图片", value = if (media.images) "已授权" else "未授权")
            ZxValueRow("视频", value = if (media.videos) "已授权" else "未授权")
            ZxValueRow("音频", value = if (media.audio) "已授权" else "未授权")
            if (media.selectedVisualOnly) ZxFooterNote("目前仅授予所选图片/视频的访问范围")
        } else ZxFooterNote("设备权限状态由 Android 系统提供")
        ZxNavRow("管理设备权限") { preferences.openSystemPermissions() }
        
    }
}

/** 运行更新入口；只显示 Host 真实提供的状态和可用操作。 */
@Composable
fun ZhaoxiDiagnosticsScene(
    updateStatus: kotlinx.coroutines.flow.StateFlow<String?>?,
    checkForUpdate: (() -> Unit)?,
    scope: UiSceneRenderScope,
) {
    val status by (updateStatus ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null))
        .collectAsState()
    ZxPage("更新", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("应用更新")
        ZxValueRow("检查状态", value = status ?: "尚未检查")
        if (checkForUpdate != null) {
            ZxButton("检查更新", checkForUpdate, Modifier.fillMaxWidth())
        } else {
            ZxEmpty("暂不可检查更新", "请稍后重试")
        }
    }
}
