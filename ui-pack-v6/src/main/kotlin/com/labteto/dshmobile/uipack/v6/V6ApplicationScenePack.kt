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
    private val usageOwner: com.labteto.dshmobile.uicontract.UsageUiPort? = null,
    private val updateStatus: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    private val checkForUpdate: (() -> Unit)? = null,
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
    private val documentScenes = if (conversations?.documents != null &&
        conversations.forms != null) setOf(UiSceneId("conversation.ui_document"),
        UiSceneId("conversation.ui_document_generate")) else emptySet()
    private val evidenceScene = if (conversations?.evidence != null)
        setOf(UiSceneId("work.requirement_evidence")) else emptySet()
    private val personaScene = if (conversations?.gallery != null &&
        conversations.selection != null) setOf(UiSceneId("chat.persona_picker")) else emptySet()
    private val groupScenes = if (conversations?.groups != null)
        setOf(UiSceneId("chat.group_members"), UiSceneId("chat.group_announcement"))
        else emptySet()
    private val archiveScenes = if (conversations?.gallery != null &&
        conversations.selection != null)
        setOf("chat.gallery", "route.persona_gallery", "chat.gallery_profile",
            "chat.gallery_delete").map(::UiSceneId).toSet() else emptySet()
    private val planScene = if (conversations?.workReads != null && conversations.workPolicy != null)
        setOf(UiSceneId("work.plan_review")) else emptySet()
    private val teamScene = if (conversations?.workTeam != null)
        setOf(UiSceneId("work.team")) else emptySet()
    private val settingsSuite = if (settings == null) emptySet() else setOf(
        "settings.root", "route.settings",
        "settings.models", "conversation.model_picker",
        "settings.account", "settings.minimax_quota",
        "settings.model_performance", "settings.pricing",
        "settings.usage", "settings.model_usage", "settings.usage_log",
        "settings.usage_detail", "settings.memory", "settings.memory_settings",
        "settings.advanced", "settings.session_storage", "settings.diagnostics",
    ).map(::UiSceneId).toSet()
    private val updateScene = if (settings != null && checkForUpdate != null &&
        updateStatus != null) setOf(UiSceneId("settings.update")) else emptySet()


    override val manifest = UiPackManifest(
        id = "v6-application-migrating", version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = pages.keys + sessionSettings + conversationScenes + documentScenes +
            evidenceScene + personaScene + groupScenes + archiveScenes +
            planScene + teamScene + settingsSuite + updateScene,
        requiredCapabilities = buildSet {
            if (conversations != null) {
                addAll(setOf("chat.send_text", "work.send_text", "chat.edit", "chat.regenerate",
                    "chat.select_variant", "chat.stop", "work.edit", "work.regenerate",
                    "work.stop", "work.answer_question", "work.answer_approval"))
                if (conversations.documents != null && conversations.forms != null)
                    addAll(setOf("ui_document.generate", "ui_document.generate_local",
                        "ui_document.prepare_input"))
                if (conversations.evidence != null) add("work.evidence_reference")
                if (conversations.gallery != null && conversations.selection != null)
                    add("chat.persona_selection")
                if (conversations.groups != null) add("chat.group_members")
                if (conversations.workReads != null && conversations.workPolicy != null)
                    add("work.plan_review")
                if (conversations.workTeam != null) add("work.team")
            }
        }.map(::UiCapabilityId).toSet(),
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
            val galleryOwner = owners.gallery
            val selectOwner = owners.selection
            if (galleryOwner != null && selectOwner != null)
                put(UiSceneId("chat.persona_picker"), UiSceneRenderer { scope ->
                    V6PersonaPickerScene(galleryOwner, selectOwner,
                        onBack = { scope.onBack(); Unit })
                })
            if (galleryOwner != null && selectOwner != null) {
                listOf(
                    "chat.gallery" to V6PersonaArchivePart.GALLERY,
                    "route.persona_gallery" to V6PersonaArchivePart.GALLERY,
                    "chat.gallery_profile" to V6PersonaArchivePart.PROFILE,
                    "chat.gallery_delete" to V6PersonaArchivePart.DELETE,
                ).forEach { (scene, part) ->
                    put(UiSceneId(scene), UiSceneRenderer { scope ->
                        V6PersonaArchiveScene(galleryOwner, selectOwner,
                            stories = owners.stories, deletion = owners.galleryDeletion,
                            part = part, onBack = { scope.onBack(); Unit })
                    })
                }
            }
            owners.groups?.let { groupOwner ->
                put(UiSceneId("chat.group_members"), UiSceneRenderer { scope ->
                    V6ChatGroupScene(groupOwner, announcementOnly = false,
                        onBack = { scope.onBack(); Unit })
                })
                put(UiSceneId("chat.group_announcement"), UiSceneRenderer { scope ->
                    V6ChatGroupScene(groupOwner, announcementOnly = true,
                        onBack = { scope.onBack(); Unit })
                })
            }
            put(UiSceneId("route.home"), UiSceneRenderer { scope ->
                val workMode = owners.workModeUpdates
                    ?.collectAsStateWithLifecycle(initialValue = owners.workMode())?.value
                    ?: owners.workMode()
                V6ConversationScene(owners, workMode, onBack = { scope.onBack(); Unit },
                    onNavigate = scope.onNavigate)
            })
            put(UiSceneId("conversation.chat"), UiSceneRenderer { scope ->
                V6ConversationScene(owners, workMode = false, onBack = { scope.onBack(); Unit },
                    onNavigate = scope.onNavigate)
            })
            put(UiSceneId("conversation.work"), UiSceneRenderer { scope ->
                V6ConversationScene(owners, workMode = true, onBack = { scope.onBack(); Unit },
                    onNavigate = scope.onNavigate)
            })
            val planOwner = owners.workReads
            val policyOwner = owners.workPolicy
            if (planOwner != null && policyOwner != null)
                put(UiSceneId("work.plan_review"), UiSceneRenderer { scope ->
                    val active by owners.work.snapshots.collectAsStateWithLifecycle(
                        initialValue = owners.work.snapshot())
                    V6WorkPlanScene(planOwner, policyOwner, active.identity,
                        onBack = { scope.onBack(); Unit })
                })
            owners.workTeam?.let { teamOwner ->
                put(UiSceneId("work.team"), UiSceneRenderer { scope ->
                    V6WorkTeamScene(teamOwner, onBack = { scope.onBack(); Unit })
                })
            }
            owners.evidence?.let { evidenceOwner ->
                put(UiSceneId("work.requirement_evidence"), UiSceneRenderer { scope ->
                    val work by owners.work.snapshots.collectAsStateWithLifecycle(
                        initialValue = owners.work.snapshot())
                    val identity = work.identity
                    if (identity == null) {
                        androidx.compose.material3.Text("正在切换 Work 会话，证据暂不可查看")
                    } else {
                        androidx.compose.foundation.layout.Column {
                            SyButton("返回", { scope.onBack(); Unit }, style = SyButtonStyle.GHOST)
                            V6WorkEvidencePanel(evidenceOwner, identity, openOnEntry = true)
                        }
                    }
                })
            }
            val docOwner = owners.documents
            val formOwner = owners.forms
            if (docOwner != null && formOwner != null) {
                put(UiSceneId("conversation.ui_document"), UiSceneRenderer { scope ->
                    val mode = owners.workModeUpdates
                        ?.collectAsStateWithLifecycle(initialValue = owners.workMode())?.value
                        ?: owners.workMode()
                    V6UiDocumentScene(owners, docOwner, formOwner, owners.media,
                        workMode = mode, onBack = { scope.onBack(); Unit })
                })
                put(UiSceneId("conversation.ui_document_generate"), UiSceneRenderer { scope ->
                    val mode = owners.workModeUpdates
                        ?.collectAsStateWithLifecycle(initialValue = owners.workMode())?.value
                        ?: owners.workMode()
                    V6UiDocumentScene(owners, docOwner, formOwner, owners.media,
                        workMode = mode, onBack = { scope.onBack(); Unit }, generationOnly = true)
                })
            }
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
                    V6ConversationScene(owners, mode, onBack = { scope.onBack(); Unit }, part = part,
                        onNavigate = scope.onNavigate)
                })
            }
        }
        settings?.let { owner ->
            (settingsSuite + updateScene).forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    V6SettingsSuiteScene(id, owner, usageOwner,
                        onNavigate = scope.onNavigate,
                        onBack = { scope.onBack(); Unit },
                        updateStatus = updateStatus, checkForUpdate = checkForUpdate)
                })
            }
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
