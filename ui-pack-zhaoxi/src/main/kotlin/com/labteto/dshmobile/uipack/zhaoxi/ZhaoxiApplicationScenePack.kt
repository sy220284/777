package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.uicontract.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiHue
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.uicontract.ChatUiCommand
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiExecutionPhase
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.UsageUiPort
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.scenes.*
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiBackground
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiDuration
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiMotion
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneNavigationState
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import com.labteto.dshmobile.uihost.UiSceneRenderer
import kotlinx.coroutines.flow.StateFlow

/**
 * 朝夕应用级 UI 包（E8.14 实装，按"替代 V6"标准）。
 *
 * 对齐主线合并版（PR #705 merged @ 283230d5）的实际准入形状
 * `V6ApplicationScenePack`（ui-pack-v6/V6ApplicationScenePack.kt）：
 *  - class + 构造注入 Feature Ports（App 组合根绑定），场景与能力按注入端口**条件声明**，
 *    不再存在静态全量 manifest；
 *  - 合并版 ui-host 已删除会话级 `ConversationUiPack` 契约——本类是唯一接入形态；
 *  - 契约新增 `SceneFrame(state, content)`：Pack 自有的视觉连续性层（朝夕场景转场），
 *    Host 仍是唯一导航提交方；
 *  - 应用级准入双门禁：`checkCompatibility` + renderer 覆盖四查（missing / undeclared /
 *    hostService / unknown 全空才 Admitted）；开发期可用
 *    `UiApplicationPackRegistry.inspectArchitectureSlice` 探针自查；
 *  - hostProvided 三场景（conversation.attachments / shell.notifications /
 *    system.permissions）不声明、不渲染，否则视为 hostServiceRenderers 违规。
 *
 * 条件场景集合与能力声明逐项镜像 V6（同一目录、同一端口组合）：
 * 全部端口注入时场景恰好 75 个、与 LocalUiSceneCatalog 非 hostProvided 全集一致。
 *
 * E8.14 渲染策略：75 已声明场景全部分发到 scenes/ 下的 Zx* 实装（见 [RenderScene]），
 * 占位渲染已移除；上下文提取失败的场景呈现显式空态，绝不静默回退 V6。
 */
class ZhaoxiApplicationScenePack(
    private val preferences: HostPreferencesUiPort,
    private val targets: ZhaoxiSceneTargets = ZhaoxiSceneTargets(),
    private val settings: SettingsUiPort? = null,
    private val conversations: ZhaoxiConversationPorts? = null,
    private val usageOwner: UsageUiPort? = null,
    private val updateStatus: StateFlow<String?>? = null,
    private val checkForUpdate: (() -> Unit)? = null,
    private val secondary: ZhaoxiSecondaryPorts? = null,
) : UiApplicationScenePack {

    private fun currentDocumentContext(): ZhaoxiDocumentContext? {
        val ports = conversations ?: return null
        val workMode = ports.workMode()
        val active = if (workMode) {
            val snapshot = ports.work.snapshot()
            snapshot.identity to snapshot.messages.lastOrNull()?.id
        } else {
            val snapshot = ports.chat.snapshot()
            snapshot.identity to snapshot.messages.lastOrNull()?.id
        }
        val identity = active.first ?: return null
        val messageId = active.second ?: return null
        return ZhaoxiDocumentContext(identity, workMode, messageId)
    }

    private var messagePreview by mutableStateOf<Pair<UiSessionIdentity, UiMessageSnapshot>?>(null)

    // Ephemeral selection only: the FilesUiPort remains the data and authorization owner.


    // ---- 条件场景集合（镜像 V6ApplicationScenePack）----

    private val pages = setOf("settings.appearance", "settings.notifications",
        "settings.permissions").map(::UiSceneId).toSet()

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

    private val messageActionScenes = if (conversations != null) setOf(
        "conversation.message_actions", "conversation.edit",
        "conversation.reader", "chat.branches").map(::UiSceneId).toSet() else emptySet()

    private val suggestionsScenes = if (conversations?.replySuggestions != null)
        setOf(UiSceneId("chat.reply_suggestions")) else emptySet()

    private val steeringScene = if (conversations?.workPolicy != null)
        setOf(UiSceneId("work.steering_choice")) else emptySet()

    private val secondaryTools = if (secondary?.tools != null)
        setOf("route.tools", "tools.mcp", "tools.github", "tools.plugins", "work.capabilities")
            .map(::UiSceneId).toSet() else emptySet()

    private val secondaryWorkspace = if (secondary?.files != null)
        setOf(UiSceneId("route.workspace"), UiSceneId("workspace.preview")) else emptySet()

    private val secondaryMedia = if (secondary?.media != null && secondary.settings != null)
        setOf(UiSceneId("media.creation"), UiSceneId("media.result")) else emptySet()

    private val secondaryAutomations = if (secondary?.automation != null)
        setOf("route.tasks", "automation.tasks", "automation.editor",
            "automation.persona_interaction").map(::UiSceneId).toSet() else emptySet()

    private val secondaryShell = if (secondary?.sessions != null)
        setOf("shell.new_session", "shell.onboarding", "shell.empty").map(::UiSceneId).toSet()
        else emptySet()

    private val secondarySearch = if (secondary?.sessions != null && secondary.transcript != null)
        setOf(UiSceneId("shell.search")) else emptySet()

    private val shellErrorScene = if (conversations != null)
        setOf(UiSceneId("shell.error")) else emptySet()

    private val extraPersonaScenes = if (secondary?.authoring != null &&
        secondary.gallery != null && secondary.selection != null)
        setOf(UiSceneId("chat.persona_editor"), UiSceneId("chat.world_book")) else emptySet()

    private val extraTuningScene = if (secondary?.currentPersona != null)
        setOf(UiSceneId("chat.character_tuning")) else emptySet()

    private val extraPortraitScene = if (secondary?.portraits != null && secondary.gallery != null)
        setOf(UiSceneId("chat.gallery_portrait")) else emptySet()

    private val extraDiaryScenes = if (secondary?.diary != null && secondary.gallery != null)
        setOf(UiSceneId("chat.diary"), UiSceneId("route.diary")) else emptySet()

    private val extraMemoryScene = if (secondary?.memory != null && secondary.settings != null)
        setOf(UiSceneId("settings.memory_management")) else emptySet()

    /** 全部端口注入时恰好 75 场景；未注入的域整体不声明。 */
    private val declaredScenes: Set<UiSceneId> =
        pages + sessionSettings + conversationScenes + documentScenes +
            evidenceScene + personaScene + groupScenes + archiveScenes +
            planScene + teamScene + settingsSuite + updateScene +
            messageActionScenes + suggestionsScenes + steeringScene +
            secondaryTools + secondaryWorkspace + secondaryMedia +
            secondaryAutomations + secondaryShell + secondarySearch + shellErrorScene +
            extraPersonaScenes + extraTuningScene + extraPortraitScene +
            extraDiaryScenes + extraMemoryScene

    override val manifest = UiPackManifest(
        id = "zhaoxi",
        version = 1,
        minContractVersion = UI_CONTRACT_VERSION,
        maxContractVersion = UI_CONTRACT_VERSION,
        scenes = declaredScenes,
        requiredCapabilities = buildSet {
            // 准入对照的是宿主真实连接的能力投影（LocalUiConnectedCapabilities），
            // 只声明与已声明场景对应的能力；release 验收归 CI。
            if (conversations != null) {
                addAll(setOf("chat.send_text", "work.send_text", "chat.edit", "chat.regenerate",
                    "chat.select_variant", "chat.stop", "work.edit", "work.regenerate",
                    "work.stop", "work.answer_question", "work.answer_approval"))
                if (conversations.attachments != null) add("attachment.import")
                if (conversations.history != null) add("session.transcript_read")
                if (conversations.documents != null && conversations.forms != null)
                    addAll(setOf("ui_document.generate", "ui_document.generate_local",
                        "ui_document.prepare_input"))
                if (conversations.evidence != null) add("work.evidence_reference")
                if (conversations.gallery != null) {
                    add("chat.gallery_archive")
                    if (conversations.selection != null) add("chat.persona_selection")
                }
                if (conversations.groups != null) add("chat.group_members")
                if (conversations.workReads != null && conversations.workPolicy != null)
                    add("work.plan_review")
                if (conversations.workTeam != null) {
                    add("work.team_control")
                    add("work.team")
                }
            }
            secondary?.let { owners ->
                if (owners.sessions != null) add("shell.session_preferences")
                if (owners.files != null) add("files.read")
                if (owners.memory != null && owners.settings != null) add("memory.manage")
                if (owners.automation != null) add("automation.manage")
                if (owners.media != null) add("media.create")
                if (owners.authoring != null) add("chat.persona_authoring")
                if (owners.currentPersona != null) add("chat.current_persona")
                if (owners.diary != null) add("chat.diary_manage")
                if (owners.portraits != null) add("chat.gallery_portrait")
            }
            if (usageOwner != null) add("usage.read")
        }.map(::UiCapabilityId).toSet(),
        windowClasses = UiWindowClass.entries.toSet(),
        accessibility = UiAccessibility.entries.toSet(),
        supportsDark = true,
    )

    override val renderers: Map<UiSceneId, UiSceneRenderer> =
        declaredScenes.associateWith { scene -> UiSceneRenderer { scope -> RenderScene(scene, scope) } }

    /**
     * E8.16 修正后的分发：75 场景全部落位 Zx* 场景函数。
     * `UiSceneRequirement` 只有 id/kind/owner/capabilities/hostProvided，**不含会话身份**——
     * 身份只能从端口快照取（`chat.snapshot().identity`），取不到即未激活，场景显式降级，
     * 绝不伪造身份或业务数据，绝不回退 V6。人物目标 id 由宿主导航提案携带，
     * 包内无合法来源时图集子页呈现显式空态。
     */
    @Composable
    private fun RenderScene(scene: UiSceneId, scope: UiSceneRenderScope) {
        val conv = conversations
        val sec = secondary
        val identity: UiSessionIdentity? =
            conv?.chat?.snapshot()?.identity ?: conv?.work?.snapshot()?.identity
        val sessionId = identity?.sessionId ?: sec?.sessionId?.invoke().orEmpty()
        when (scene.value) {
            // ---- 壳层 ----
            "route.home", "shell.new_session" -> ZhaoxiHomeScene(sec?.sessions, scope)
            "shell.search" -> ZhaoxiSearchScene(sec?.sessions, scope)
            "shell.onboarding" -> ZhaoxiOnboardingScene(scope)
            "shell.empty" -> ZhaoxiEmptyDemoScene(scope)
            "shell.error" -> ZhaoxiErrorDemoScene(scope)

            // ---- 会话域 ----
            "conversation.chat" -> conv?.let {
                ZhaoxiChatScene(it.chat, it.drafts, it.reading, it.history, authorName = "助手", scope,
                    onMessage = { identity, message -> targets.message = identity to message.id; messagePreview = identity to message; scope.onNavigate(UiSceneId("conversation.message_actions")) },
                    menu = { ZhaoxiConversationMenu(conversations, secondary, scope, renderSlot = { RenderScene(it, scope) }) },
                    documents = conv.documents, knownScenes = manifest.scenes, media = conv.media)
            }
            "conversation.work" -> conv?.let { ZhaoxiWorkScene(it.work, it.drafts, it.reading, it.history, scope,
                onMessage = { identity, message -> targets.message = identity to message.id; messagePreview = identity to message; scope.onNavigate(UiSceneId("conversation.message_actions")) },
                menu = { ZhaoxiConversationMenu(conversations, secondary, scope, renderSlot = { RenderScene(it, scope) }) },
                    documents = conv.documents, knownScenes = manifest.scenes, media = conv.media) }
            "conversation.header" -> conv?.let {
                ZhaoxiConversationMenu(conversations, secondary, scope, renderSlot = { RenderScene(it, scope) })
            }
            "conversation.timeline" -> conv?.let { ZhaoxiTimelineScene(it.chat, authorName = "助手") }
            "conversation.composer" -> conv?.let {
                val s = it.chat.snapshot()
                val stored by it.drafts.drafts.collectAsState()
                ZhaoxiComposerSlotScene(s.send, s.phase == UiExecutionPhase.RUNNING, "说点什么…",
                    onSendText = { t ->
                        val id = s.identity ?: return@ZhaoxiComposerSlotScene false
                        when (it.chat.submit(ChatUiCommand.SendText(id, t))) {
                            UiCommandReceipt.Accepted, UiCommandReceipt.Queued -> true
                            else -> false  // 拒收/失败：保留草稿，可用性原因由 send.availability 呈现
                        }
                    },
                    onStop = { s.identity?.let { id -> it.chat.submit(ChatUiCommand.Stop(id)) } },
                    draft = stored[s.identity?.sessionId].orEmpty(),
                    onDraftChange = { text -> s.identity?.let { id -> it.drafts.updateDraft(id.sessionId, text) } })
            }
            "conversation.reply_status" -> conv?.let {
                val s = it.chat.snapshot()
                ZhaoxiReplyStatusScene(s.phase, s.queuedInputCount)
            }
            "conversation.message_actions", "conversation.edit", "chat.branches",
            "conversation.reader" -> {
                val workMode = conv?.workMode() == true
                val activeIdentity = if (workMode) conv?.work?.snapshot()?.identity else conv?.chat?.snapshot()?.identity
                val messages = if (workMode) conv?.work?.snapshot()?.messages else conv?.chat?.snapshot()?.messages
                val selectedId = targets.message?.takeIf { it.first == activeIdentity }?.second
                val msg = selectedId?.let { id ->
                    messages?.firstOrNull { it.id == id } ?: messagePreview?.takeIf {
                        it.first == activeIdentity && it.second.id == id
                    }?.second
                }
                if (msg == null) ZxEmpty("没有消息", "会话为空") else when (scene.value) {
                    "conversation.edit" -> ZhaoxiEditScene(msg, conv!!.chat, scope, if (workMode) conv.work else null)
                    "chat.branches" -> ZhaoxiBranchesScene(msg, conv!!.chat, scope)
                    "conversation.reader" -> ZhaoxiReaderScene("阅读", msg.text, scope)
                    else -> ZhaoxiMessageActionsScene(msg, scope,
                        onEdit = { scope.onNavigate(UiSceneId("conversation.edit")) },
                        onRegenerate = {
                            if (workMode) activeIdentity?.let { conv!!.work.submit(WorkConversationUiCommand.RegenerateReply(it, msg.id)) }
                            else activeIdentity?.let { conv!!.chat.submit(ChatUiCommand.RegenerateReply(it, msg.id)) }
                            scope.onBack()
                        })
                }
            }
            "conversation.model_picker" -> settings?.let { modelOwner ->
                val snapshot by modelOwner.state.collectAsState(initial = modelOwner.initialState)
                ZhaoxiModelPickerScene(snapshot.modelProfiles.map { profile ->
                    Triple(profile.id, profile.displayName ?: profile.model, snapshot.modelSelection.isActive(profile))
                }, { modelOwner.submit(SettingsUiCommand.SelectModel(it)); scope.onBack() },
                    { scope.onNavigate(UiSceneId("settings.models")) }, scope)
            }
            "conversation.ui_document" -> conv?.documents?.let { ZhaoxiUiDocumentScene(it, scope, ::currentDocumentContext, manifest.scenes, { conversations?.work?.snapshot()?.approval?.pendingId }, conv?.media) }
            "conversation.ui_document_generate" -> conv?.documents?.let { ZhaoxiUiDocumentGenerateScene(it, scope, ::currentDocumentContext) }
            "work.approval" -> conv?.let { ZhaoxiApprovalScene(it.work, scope) }
            "work.question" -> conv?.let { ZhaoxiQuestionScene(it.work, scope) }
            "work.plan_review" -> conv?.workReads?.let { ZhaoxiPlanReviewScene(it, conv.work, sessionId, scope) }
            "work.requirement_evidence" -> if (conv?.evidence != null && identity != null)
                ZhaoxiRequirementEvidenceScene(conv.evidence, identity, scope)
            else ZxEmpty("证据不可用", "缺少会话身份")
            "work.team" -> conv?.workTeam?.let { ZhaoxiWorkTeamScene(it, scope) }
            "work.capabilities" -> sec?.tools?.let { ZhaoxiToolsScene(it, scope) }
            "work.steering_choice" -> ZhaoxiSteeringScene(scope)
            "chat.reply_suggestions" -> (conv?.replySuggestions ?: sec?.replySuggestions)?.let {
                ZxReplySuggestionsScene(it, onPick = { text ->
                    val current = conv?.chat?.snapshot()?.identity
                    if (current != null) { conv?.drafts?.updateDraft(current.sessionId, text); scope.onBack() }
                }, scope = scope)
            }

            // ---- 人物域 ----
            "chat.persona_picker", "chat.gallery", "route.persona_gallery" ->
                (conv?.gallery ?: sec?.gallery)?.let {
                    ZhaoxiPersonaGalleryScene(it, scope,
                        onOpenPersona = { galleryId ->
                            targets.galleryId = galleryId
                            scope.onNavigate(UiSceneId(if (galleryId == "new") "chat.persona_editor" else "chat.gallery_profile"))
                        }, authoring = sec?.authoring, transfer = sec?.transfer)
                }
            // 人物子页的图集 id 合法来源 = CurrentPersonaUiPort.read() 的 target.personaId；
            // 端口未注入或无当前人物时显式空态，不伪造目标。
            "chat.gallery_profile", "chat.persona_editor", "chat.world_book",
            "chat.gallery_portrait", "chat.gallery_delete", "chat.diary", "route.diary" -> {
                val personaTarget = (sec?.currentPersona?.read() as?
                    com.labteto.dshmobile.uicontract.UiCurrentPersonaReceipt.Loaded)
                    ?.snapshot?.target
                val gid = targets.galleryId ?: personaTarget?.personaId
                if (gid == null) ZxEmpty("未指定人物", "请从人物图集进入 · 目标由宿主导航提案携带")
                else when (scene.value) {
                    "chat.gallery_profile" -> ZhaoxiPersonaProfileScene(gid, sec?.gallery ?: conv?.gallery,
                        conv?.selection ?: sec?.selection, conv?.stories, sec?.transfer, scope)
                    "chat.persona_editor", "chat.world_book" ->
                        if (sec?.authoring != null) ZhaoxiPersonaEditorScene(sec.authoring, gid, scope)
                        else ZxEmpty("端口未注入", "chat.persona_authoring 能力未连接")
                    "chat.gallery_portrait" ->
                        if (sec?.portraits != null) ZhaoxiPortraitEditor(sec.portraits, gid, scope)
                        else ZxEmpty("端口未注入", "chat.gallery_portrait 能力未连接")
                    "chat.gallery_delete" ->
                        if (conv?.galleryDeletion != null)
                            ZxGalleryDeleteScene(conv.galleryDeletion, gid, "所选人物", identity, scope, sec?.gallery ?: conv.gallery)
                        else ZxEmpty("端口未注入", "图集删除能力未连接")
                    else ->
                        if (sec?.diary != null) ZhaoxiDiaryScene(sec.diary, gid, scope)
                        else ZxEmpty("端口未注入", "chat.diary_manage 能力未连接")
                }
            }
            "chat.character_tuning" -> sec?.currentPersona?.let { ZhaoxiCurrentPersonaTuning(it, scope) }
            "chat.group_members" -> conv?.groups?.let { ZxGroupMembersScene(it, scope) }
            "chat.group_announcement" -> conv?.groups?.let { ZxGroupAnnouncementScene(it, scope) }

            // ---- 自动化 / 工具 / 媒体 ----
            "route.tasks", "automation.tasks" -> sec?.automation?.let { ZhaoxiAutomationTasksScene(it, scope) }
            "automation.editor" -> sec?.automation?.let { ZhaoxiAutomationEditorScene(it, scope) }
            "automation.persona_interaction" -> sec?.automation?.let { ZhaoxiPersonaInteractionScene(it, scope) }
            "route.tools", "tools.mcp", "tools.github", "tools.plugins" ->
                sec?.tools?.let { ZhaoxiToolsScene(it, scope) }
            "media.creation", "media.result" -> (sec?.media ?: conv?.media)?.let { media ->
                settings?.let { ZhaoxiConnectedMediaScene(media, it, sessionId, scope, sec?.gallery ?: conv?.gallery, sec?.portraits) }
            }

            // ---- 工作区 ----
            "route.workspace" -> ZhaoxiWorkspaceScene(sec?.files ?: conv?.files, scope) { filePath ->
                targets.workspacePath = filePath
                scope.onNavigate(UiSceneId("workspace.preview"))
            }
            "workspace.preview" -> ZhaoxiWorkspacePreviewScene(
                sec?.files ?: conv?.files, targets.workspacePath, scope)

            // ---- 设置 ----
            "settings.root", "route.settings" -> ZhaoxiSettingsHomeScene(scope)
            "settings.appearance" -> ZhaoxiAppearanceScene(preferences, scope)
            "settings.notifications", "settings.permissions" -> ZhaoxiNotificationsScene(preferences, scope)
            "settings.models" -> settings?.let { ZhaoxiModelsScene(it, scope) }
            "settings.session", "settings.chat", "settings.advanced", "settings.memory_settings" ->
                settings?.let { ZhaoxiRuntimeSettingsScene(it, scope) }
            "settings.account" -> settings?.let { ZhaoxiAccountScene(it, scope) }
            "settings.minimax_quota" -> settings?.let { ZhaoxiQuotaScene(it, scope) }
            "settings.usage", "settings.model_usage", "settings.usage_log", "settings.usage_detail" ->
                usageOwner?.let { ZhaoxiUsageScene(it, scope) }
            "settings.model_performance" -> sec?.modelPerformance?.let { ZhaoxiPerformanceScene(it, scope) }
            "settings.pricing" -> sec?.pricing?.let { ZhaoxiPricingScene(it, scope) }
            "settings.memory", "settings.memory_management" -> sec?.memory?.let { ZhaoxiMemoryManagementScene(it, scope) }
            "settings.session_storage" -> settings?.let { ZhaoxiStorageScene(it, scope) }
            "settings.diagnostics", "settings.update" ->
                ZhaoxiDiagnosticsScene(updateStatus, checkForUpdate, scope)

            else -> ZxEmpty("朝夕 · ${scene.value}", "该场景暂无 Zx* 实装映射")
        }
    }

    /**
     * 朝夕场景转场层（E8.16 实装）：ZhaoxiTheme + ZhaoxiBackground 实绘
     * （底色纵向渐变 + 环境光双径向）+ 单一内容树入场转场
     * （淡入 + 16dp 上移，经 ZhaoxiMotion.spec 走 reduced-motion 降级）。
     * Host 仍是唯一导航提交方，本层只做呈现，不保留旧场景。
     */
    @Composable
    override fun SceneFrame(state: UiSceneNavigationState, content: @Composable () -> Unit) {
        val appearance by preferences.snapshots.collectAsState(initial = UiHostPreferences())
        val systemDark = isSystemInDarkTheme()
        val dark = when (appearance.theme) {
            "dark", "matte_black" -> true
            "light" -> false
            else -> systemDark
        }
        val hue = when (appearance.accent) {
            "sage", "celadon" -> ZhaoxiHue.SAGE
            "steel" -> ZhaoxiHue.STEEL
            "mauve" -> ZhaoxiHue.MAUVE
            "dusk" -> ZhaoxiHue.DUSK
            else -> ZhaoxiHue.STONE
        }
        // Android's animator duration scale 0 is an explicit reduced-motion preference.
        val context = LocalContext.current
        val reduced = android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
        val windowDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(windowDensity.density, windowDensity.fontScale * appearance.textScale),
        ) {
        ZhaoxiTheme(hue = hue, dark = dark, reducedMotion = reduced || appearance.motionScale == 0f) {
            Box(Modifier.fillMaxSize()) {
                ZhaoxiBackground(Modifier.fillMaxSize())
                // Only the incoming content is live; do not retain an outgoing command tree.
                val progress = remember { Animatable(0f) }
                val motion = ZhaoxiMotion.spec<Float>(ZhaoxiDuration.NORMAL)
                LaunchedEffect(state.stack.lastOrNull()) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, motion)
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * 16f * density
                }) { content() }
            }
        }
        }
    }
}
