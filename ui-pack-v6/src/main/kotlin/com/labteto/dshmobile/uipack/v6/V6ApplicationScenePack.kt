package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.uicontract.UiHostPreferencesCommand
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyGlassSelect
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.components.SySwitch
import com.shenyan.designsystem.components.SyValueSlider
import com.shenyan.designsystem.foundation.SyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** First independent V6 application scenes; never claims that all 75 have migrated. */
class V6ApplicationScenePack(
    private val preferences: HostPreferencesUiPort,
    private val settings: SettingsUiPort? = null,
    private val conversations: V6ConversationPorts? = null,
) : UiApplicationScenePack {
    private val pages = mapOf(
        UiSceneId("settings.appearance") to V6PreferencesPage.APPEARANCE,
        UiSceneId("settings.notifications") to V6PreferencesPage.NOTIFICATIONS,
        UiSceneId("settings.permissions") to V6PreferencesPage.PERMISSIONS,
    )
    private val sessionSettings = if (settings == null) emptySet() else
        setOf(UiSceneId("settings.session"), UiSceneId("settings.chat"))

    private val conversationScenes = if (conversations == null) emptySet() else
        setOf("route.home", "conversation.chat", "conversation.work",
            "conversation.header", "conversation.timeline", "conversation.composer",
            "conversation.reply_status", "work.approval", "work.question").map(::UiSceneId).toSet()

    override val manifest = UiPackManifest(
        id = "v6-application-migrating", version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = pages.keys + sessionSettings + conversationScenes,
        requiredCapabilities = if (conversations == null) emptySet() else
            setOf("chat.send_text", "work.send_text", "chat.edit", "chat.regenerate",
                "chat.select_variant", "chat.stop", "work.edit", "work.regenerate",
                "work.stop", "work.answer_question", "work.answer_approval")
                .map(::UiCapabilityId).toSet(),
        windowClasses = UiWindowClass.entries.toSet(),
        accessibility = UiAccessibility.entries.toSet(), supportsDark = true,
    )
    override val renderers: Map<UiSceneId, UiSceneRenderer> = buildMap {
        pages.forEach { (id, page) ->
            put(id, UiSceneRenderer { scope ->
                V6PreferencesScene(page, preferences, onBack = { scope.onBack(); Unit })
            })
        }
        conversations?.let { owners ->
            put(UiSceneId("route.home"), UiSceneRenderer { scope ->
                val workMode = owners.workModeUpdates
                    ?.collectAsStateWithLifecycle(initialValue = owners.workMode())?.value
                    ?: owners.workMode()
                V6ConversationScene(owners, workMode, onBack = { scope.onBack(); Unit })
            })
            put(UiSceneId("conversation.chat"), UiSceneRenderer { scope ->
                V6ConversationScene(owners, workMode = false, onBack = { scope.onBack(); Unit })
            })
            put(UiSceneId("conversation.work"), UiSceneRenderer { scope ->
                V6ConversationScene(owners, workMode = true, onBack = { scope.onBack(); Unit })
            })
            listOf(
                "conversation.header" to V6ConversationPart.HEADER,
                "conversation.timeline" to V6ConversationPart.TIMELINE,
                "conversation.composer" to V6ConversationPart.COMPOSER,
                "conversation.reply_status" to V6ConversationPart.REPLY_STATUS,
                "work.approval" to V6ConversationPart.WORK_APPROVAL,
                "work.question" to V6ConversationPart.WORK_QUESTION,
            ).forEach { (key, part) ->
                put(UiSceneId(key), UiSceneRenderer { scope ->
                    val mode = owners.workModeUpdates
                        ?.collectAsStateWithLifecycle(initialValue = owners.workMode())?.value
                        ?: owners.workMode()
                    V6ConversationScene(owners, mode, onBack = { scope.onBack(); Unit }, part = part)
                })
            }
        }
        settings?.let { owner ->
            put(UiSceneId("settings.chat"), UiSceneRenderer { scope ->
                V6ChatStyleSettingsScene(owner, onBack = { scope.onBack(); Unit })
            })
            put(UiSceneId("settings.session"), UiSceneRenderer { scope ->
                V6SessionSettingsScene(owner,
                    onNavigate = scope.onNavigate,
                    onBack = { scope.onBack(); Unit })
            })
        }
    }
}

enum class V6PreferencesPage { APPEARANCE, NOTIFICATIONS, PERMISSIONS }

/** State and writes come from the Host owner; this V6 page owns only visual controls. */
@Composable
fun V6PreferencesScene(page: V6PreferencesPage, owner: HostPreferencesUiPort, onBack: () -> Unit) {
    var settings by remember(owner) { mutableStateOf<UiHostPreferences?>(null) }
    var loadError by remember(owner) { mutableStateOf<String?>(null) }
    var feedback by remember(owner, page) { mutableStateOf<String?>(null) }
    var saving by remember(owner, page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        try {
            owner.snapshots.collect { settings = it; loadError = null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = failure.message ?: "设置读取失败" }
    }
    fun save(command: UiHostPreferencesCommand) {
        if (saving || settings == null) return
        saving = true
        scope.launch {
            try {
                feedback = if (owner.submit(command)) "设置已保存" else "设置未保存，请重试"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "保存失败" }
            finally { saving = false }
        }
    }
    fun launchSystem(notifications: Boolean) {
        try {
            val accepted = if (notifications) owner.openSystemNotifications() else owner.openSystemPermissions()
            if (!accepted) feedback = "无法打开 Android 系统设置"
        } catch (failure: Exception) {
            feedback = failure.message ?: "无法打开 Android 系统设置"
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (page) {
                V6PreferencesPage.APPEARANCE -> "界面与外观"
                V6PreferencesPage.NOTIFICATIONS -> "通知偏好"
                V6PreferencesPage.PERMISSIONS -> "系统权限"
            }, style = MaterialTheme.typography.titleLarge)
            SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        }
        feedback?.let { SyNote(it) }
        loadError?.let { SyNote(it) }
        val current = settings
        if (current == null) SyNote("尚未读取到真实设置，操作不可用")
        else when (page) {
            V6PreferencesPage.APPEARANCE -> V6AppearanceEditor(current, saving, ::save)
            V6PreferencesPage.NOTIFICATIONS -> V6NotificationEditor(current, saving, ::save,
                onOpenSettings = { launchSystem(true) })
            V6PreferencesPage.PERMISSIONS -> SyGlassCard(Modifier.fillMaxWidth()) {
                Text("权限由 Android 系统决定，本界面不会自动授予。")
                SyButton("应用权限设置", { launchSystem(false) })
                SyButton("通知权限设置", { launchSystem(true) }, style = SyButtonStyle.SECONDARY)
            }
        }
    }
}

@Composable
private fun V6AppearanceEditor(p: UiHostPreferences, busy: Boolean, save: (UiHostPreferencesCommand) -> Unit) {
    var theme by rememberSaveable(p) { mutableStateOf(p.theme) }
    var accent by rememberSaveable(p) { mutableStateOf(p.accent) }
    var scale by rememberSaveable(p) { mutableFloatStateOf(p.textScale) }
    var weight by rememberSaveable(p) { mutableIntStateOf(p.textWeight) }
    var alpha by rememberSaveable(p) { mutableFloatStateOf(p.transparency) }
    var glow by rememberSaveable(p) { mutableFloatStateOf(p.ambientGlow) }
    var emphasis by rememberSaveable(p) { mutableFloatStateOf(p.componentEmphasis) }
    var motion by rememberSaveable(p) { mutableFloatStateOf(p.motionScale) }
    var contrast by rememberSaveable(p) { mutableStateOf(p.adaptiveContrast) }
    val valid = Regex("[a-z0-9_-]{2,50}").matches(accent)
    SyGlassCard(Modifier.fillMaxWidth()) {
        Text("主题与材质", style = MaterialTheme.typography.titleMedium)
        SyGlassSelect(listOf(
            SyOption("system", "跟随系统"), SyOption("light", "暖纸浅色"),
            SyOption("dark", "烟熏玻璃"), SyOption("matte_black", "哑光黑")
        ), theme, onSelect = { theme = it })
        SyInput(accent, { accent = it.filter { c -> c in 'a'..'z' || c in '0'..'9' ||
            c == '-' || c == '_' }.take(50) }, label = "配色标识",
            error = if (valid) null else "请输入 2 至 50 个有效字符")
        SyValueSlider("阅读字号（%）", scale * 100, { scale = it / 100 }, range = 90f..150f)
        SyValueSlider("字重", weight.toFloat(), { weight = it.toInt() }, range = 0f..2f)
        SyValueSlider("材质透明度（%）", alpha * 100, { alpha = it / 100 })
        SyValueSlider("环境辉光（%）", glow * 100, { glow = it / 100 })
        SyValueSlider("组件强调（%）", emphasis * 100, { emphasis = it / 100 })
        SyValueSlider("动效速度（%）", motion * 100, { motion = it / 100 }, range = 70f..130f)
        SySwitch("背景自适应对比", contrast, { contrast = it })
        SyButton("保存外观", { save(UiHostPreferencesCommand.Appearance(theme, accent,
            scale, weight, alpha, glow, emphasis, motion, contrast)) }, enabled = !busy && valid)
    }
}

@Composable
private fun V6NotificationEditor(p: UiHostPreferences, busy: Boolean,
    save: (UiHostPreferencesCommand) -> Unit, onOpenSettings: () -> Unit) {
    var jobs by rememberSaveable(p) { mutableStateOf(p.notifyJobs) }
    var results by rememberSaveable(p) { mutableStateOf(p.notifyResults) }
    var failures by rememberSaveable(p) { mutableStateOf(p.notifyFailures) }
    var action by rememberSaveable(p) { mutableStateOf(p.notifyNeedsAction) }
    var chat by rememberSaveable(p) { mutableStateOf(p.notifyChat) }
    var quiet by rememberSaveable(p) { mutableStateOf(p.quietEnabled) }
    var start by rememberSaveable(p) { mutableIntStateOf(p.quietStartMinute) }
    var end by rememberSaveable(p) { mutableIntStateOf(p.quietEndMinute) }
    SyGlassCard(Modifier.fillMaxWidth()) {
        Text("通知类型", style = MaterialTheme.typography.titleMedium)
        SySwitch("任务通知", jobs, { jobs = it })
        SySwitch("结果通知", results, { results = it })
        SySwitch("失败通知", failures, { failures = it })
        SySwitch("需要操作提醒", action, { action = it })
        SySwitch("聊天消息", chat, { chat = it })
        SySwitch("免打扰", quiet, { quiet = it })
        if (quiet) {
            SyValueSlider("免打扰开始（分钟）", start.toFloat(), { start = it.toInt() }, range = 0f..1439f)
            SyValueSlider("免打扰结束（分钟）", end.toFloat(), { end = it.toInt() }, range = 0f..1439f)
            Text("设备本地时间：${"%02d:%02d".format(start / 60, start % 60)} 至 " +
                "%02d:%02d".format(end / 60, end % 60))
        }
        SyButton("保存通知偏好", { save(UiHostPreferencesCommand.Notifications(
            jobs, results, failures, action, chat, quiet, start, end)) }, enabled = !busy)
        SyButton("Android 通知权限", onOpenSettings, style = SyButtonStyle.SECONDARY)
    }
}
