package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import com.labteto.dshmobile.uihost.UiSceneNavigationResult

/** Feature Owners are injected by the App, never recreated inside the UI bundle. */
data class NextApplicationFeaturePorts(
    val chat: ChatUiPort,
    val work: WorkConversationUiPort,
    val drafts: ConversationDraftUiPort,
    val reading: ConversationReadingUiPort,
    val gallery: PersonaGalleryUiPort,
    val selection: PersonaSelectionUiPort,
    val workMode: () -> Boolean,
    val workModeUpdates: Flow<Boolean>? = null,
    val documents: UiDocumentUiPort? = null,
    val forms: UiDocumentFormUiPort? = null,
    val attachments: AttachmentUiPort? = null,
    val sessions: SessionUiPort? = null,
    val transcript: UiMessageHistoryPort? = null,
    val policy: WorkPolicyUiPort? = null,
    val workReads: WorkReadUiPort? = null,
    val files: FilesUiPort? = null,
    val evidence: WorkEvidenceUiPort? = null,
    val shell: ConversationShellUiPort? = null,
    val team: WorkTeamUiPort? = null,
    val groups: ChatGroupUiPort? = null,
    val personaTransfer: PersonaTransferUiPort? = null,
    val stories: PersonaStoryUiPort? = null,
    val galleryDeletion: PersonaGalleryDeletionUiPort? = null,
    val gallerySave: PersonaGallerySaveUiPort? = null,
    val portraits: PersonaPortraitUiPort? = null,
    val tools: ToolsUiPort? = null,
    val invocations: ComposerInvocationUiPort? = null,
    val authoring: PersonaAuthoringUiPort? = null,
    val currentPersona: CurrentPersonaUiPort? = null,
    val memory: MemoryUiPort? = null,
    val settings: SettingsUiPort? = null,
    val automation: AutomationUiPort? = null,
    val media: MediaUiPort? = null,
    val usage: UsageUiPort? = null,
    val activeSessionId: () -> String? = { null },
    val diary: DiaryUiPort? = null,
    val hostPreferences: HostPreferencesUiPort? = null,
    val replySuggestions: ReplySuggestionsUiPort? = null,
    val updateStatus: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    val checkForUpdate: (() -> Unit)? = null,
)

/**
 * Architecture verification pack, not a second production UI.
 * The V6 migration is responsible for full product parity; this pack only proves
 * independently-compiled chat, Work, gallery, tools and files can consume Owner Ports.
 * Do not advertise placeholder sub-scenes as a production migration.
 */
class NextApplicationScenePack(private val ports: NextApplicationFeaturePorts) : UiApplicationScenePack {
    private val home = UiSceneId("route.home")
    private val galleryRoute = UiSceneId("route.persona_gallery")
    private val chat = UiSceneId("conversation.chat")
    private val work = UiSceneId("conversation.work")
    private val galleryPage = UiSceneId("chat.gallery")
    private val toolsRoute = UiSceneId("route.tools")
    private val toolsMcp = UiSceneId("tools.mcp")
    private val toolsPlugins = UiSceneId("tools.plugins")
    private val toolsGithub = UiSceneId("tools.github")
    private val workspace = UiSceneId("route.workspace")
    private val memories = UiSceneId("settings.memory_management")
    private val globalSearch = UiSceneId("shell.search")
    private val diaryRoute = UiSceneId("route.diary")
    private val diaryPage = UiSceneId("chat.diary")
    private val tasksRoute = UiSceneId("route.tasks")
    private val tasksPage = UiSceneId("automation.tasks")
    private val taskEditor = UiSceneId("automation.editor")
    private val chatTasks = UiSceneId("automation.persona_interaction")
    private val mediaCreation = UiSceneId("media.creation")
    private val mediaResults = UiSceneId("media.result")
    private val conversationSlots = setOf(
        "conversation.header", "conversation.timeline", "conversation.composer",
        "conversation.reply_status", "conversation.message_actions",
        "conversation.edit", "conversation.reader", "chat.branches",
    ).map(::UiSceneId).toSet()
    private val documentSlots = setOf(
        "conversation.ui_document", "conversation.ui_document_generate",
    ).map(::UiSceneId).toSet()
    private val groupSlots = setOf("chat.group_members", "chat.group_announcement")
        .map(::UiSceneId).toSet()
    private val workPrompts = setOf("work.approval", "work.question")
        .map(::UiSceneId).toSet()
    private val galleryMaintenance = setOf(
        "chat.gallery_profile", "chat.gallery_delete", "chat.gallery_portrait",
    ).map(::UiSceneId).toSet()
    private val sessionWelcome = setOf("shell.empty", "shell.onboarding")
        .map(::UiSceneId).toSet()
    private val settingsScenes = setOf(
        "route.settings", "settings.root", "settings.models", "settings.chat",
        "settings.memory", "settings.memory_settings", "settings.advanced",
        "settings.session", "settings.usage", "settings.model_usage",
        "settings.usage_log", "settings.usage_detail",
        "settings.session_storage", "settings.diagnostics", "settings.account",
        "settings.minimax_quota",
    ).map(::UiSceneId).toSet()
    // Only independently meaningful vertical slices belong to the admission manifest.
    // Remaining candidate renderers below are development references, NOT feature parity.
    private val architectureProbeScenes: Set<UiSceneId> = buildSet {
        addAll(setOf(home, galleryRoute, chat, work, galleryPage))
        if (ports.tools != null) addAll(setOf(toolsRoute, toolsMcp, toolsPlugins, toolsGithub))
        if (ports.files != null) add(workspace)
    }
    private val implementedScenes = buildSet {
        addAll(setOf(home, galleryRoute, chat, work, galleryPage))
        addAll(conversationSlots)
        if (ports.documents != null && ports.forms != null) addAll(documentSlots)
        if (ports.settings != null) add(UiSceneId("conversation.model_picker"))
        add(UiSceneId("chat.persona_picker"))
        if (ports.galleryDeletion != null || ports.portraits != null || ports.stories != null)
            addAll(galleryMaintenance)
        if (ports.authoring != null) addAll(setOf("chat.persona_editor", "chat.world_book").map(::UiSceneId))
        if (ports.replySuggestions != null) add(UiSceneId("chat.reply_suggestions"))
        if (ports.currentPersona != null) add(UiSceneId("chat.character_tuning"))
        if (ports.groups != null) addAll(groupSlots)
        addAll(workPrompts)
        if (ports.team != null) add(UiSceneId("work.team"))
        if (ports.evidence != null) add(UiSceneId("work.requirement_evidence"))
        if (ports.policy != null) add(UiSceneId("work.steering_choice"))
        if (ports.workReads != null) add(UiSceneId("work.plan_review"))
        if (ports.tools != null) add(UiSceneId("work.capabilities"))
        if (ports.sessions != null) addAll(sessionWelcome + UiSceneId("shell.new_session"))
        add(UiSceneId("shell.error"))
        if (ports.files != null) add(UiSceneId("workspace.preview"))
        if (ports.tools != null) addAll(setOf(toolsRoute, toolsMcp, toolsPlugins, toolsGithub))
        if (ports.files != null) add(workspace)
        if (ports.memory != null && ports.settings != null) add(memories)
        if (ports.automation != null) addAll(setOf(tasksRoute, tasksPage, taskEditor, chatTasks))
        if (ports.diary != null) addAll(setOf(diaryRoute, diaryPage))
        if (ports.sessions != null && ports.transcript != null) add(globalSearch)
        if (ports.media != null && ports.settings != null) addAll(setOf(mediaCreation, mediaResults))
        if (ports.settings != null) addAll(settingsScenes + UiSceneId("settings.model_performance") +
            UiSceneId("settings.pricing"))
        if (ports.hostPreferences != null) addAll(setOf("settings.appearance",
            "settings.notifications", "settings.permissions").map(::UiSceneId))
        if (ports.checkForUpdate != null && ports.updateStatus != null)
            add(UiSceneId("settings.update"))
    }

    override val manifest = UiPackManifest(
        id = "next-application-incremental", version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = architectureProbeScenes,
        requiredCapabilities = buildSet {
            addAll(setOf("chat.send_text", "work.send_text", "chat.gallery_archive", "chat.persona_selection"))
            if (ports.files != null) add("files.read")
            if (ports.memory != null && ports.settings != null) add("memory.manage")
            if (ports.authoring != null) add("chat.persona_authoring")
            if (ports.currentPersona != null) add("chat.current_persona")
            if (ports.automation != null) add("automation.manage")
            if (ports.media != null) add("media.create")
            if (ports.usage != null) add("usage.read")
            if (ports.diary != null) add("chat.diary_manage")
            if (ports.sessions != null && ports.transcript != null) add("session.transcript_read")
        }.map(::UiCapabilityId).toSet(),
        windowClasses = UiWindowClass.entries.toSet(),
        accessibility = UiAccessibility.entries.toSet(),
        supportsDark = true,
    )
    override val renderers: Map<UiSceneId, UiSceneRenderer> = buildMap {
        put(home, UiSceneRenderer { scope -> HomeRoute(scope) })
        put(chat, UiSceneRenderer { scope -> ConversationRoute(scope, false) })
        put(work, UiSceneRenderer { scope -> ConversationRoute(scope, true) })
        put(galleryRoute, UiSceneRenderer { scope -> GalleryRoute(scope) })
        put(galleryPage, UiSceneRenderer { scope -> GalleryRoute(scope) })
        conversationSlots.forEach { id ->
            put(id, UiSceneRenderer { scope ->
                CompositionLocalProvider(LocalNextMediaPort provides ports.media) {
                    NextConversationSubScene(id, ports, onBack = { scope.onBack() })
                }
            })
        }
        if (ports.documents != null && ports.forms != null) {
            documentSlots.forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    CompositionLocalProvider(LocalNextMediaPort provides ports.media) {
                        NextConversationSubScene(id, ports, onBack = { scope.onBack() })
                    }
                })
            }
        }
        if (ports.settings != null) put(UiSceneId("conversation.model_picker"), UiSceneRenderer { scope ->
            NextSettingsScene("settings.models", ports.settings, ports.usage,
                onBack = { scope.onBack() }, onNavigate = { key -> scope.onNavigate(UiSceneId(key)) })
        })
        if (ports.galleryDeletion != null || ports.portraits != null || ports.stories != null) {
            galleryMaintenance.forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    val selection = ports.selection.snapshots.collectAsStateWithLifecycle(
                        initialValue = ports.selection.snapshot()).value
                    NextPersonaGalleryDialog(
                        port = ports.gallery, personas = ports.selection, selection = selection,
                        transfer = ports.personaTransfer, stories = ports.stories,
                        deletion = ports.galleryDeletion, save = ports.gallerySave,
                        portraits = ports.portraits, authoring = ports.authoring,
                        close = { scope.onBack() },
                    )
                })
            }
        }
        put(UiSceneId("chat.persona_picker"), UiSceneRenderer { scope ->
            NextPersonaPickerScene(ports.gallery, ports.selection, onBack = { scope.onBack() })
        })
        ports.replySuggestions?.let { owner ->
            put(UiSceneId("chat.reply_suggestions"), UiSceneRenderer { scope ->
                NextReplySuggestionsScene(owner, ports.drafts, onBack = { scope.onBack() })
            })
        }
        ports.authoring?.let { owner ->
            put(UiSceneId("chat.world_book"), UiSceneRenderer { scope ->
                NextWorldBookScene(ports.gallery, owner, onBack = { scope.onBack() })
            })
            put(UiSceneId("chat.persona_editor"), UiSceneRenderer { scope ->
                NextPersonaAuthoringScene(owner, ports.selection, galleryId = null,
                    onBack = { scope.onBack() }, onSaved = { scope.onBack() })
            })
        }
        ports.currentPersona?.let { owner ->
            put(UiSceneId("chat.character_tuning"), UiSceneRenderer { scope ->
                NextCurrentPersonaScene(owner, close = { scope.onBack() })
            })
        }
        ports.groups?.let { owner ->
            groupSlots.forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    NextChatGroupScene(owner, announcementOnly = id.value == "chat.group_announcement",
                        onBack = { scope.onBack() })
                })
            }
        }
        workPrompts.forEach { id ->
            put(id, UiSceneRenderer { scope ->
                NextWorkPromptScene(ports.work, approval = id.value == "work.approval",
                    onBack = { scope.onBack() })
            })
        }
        ports.team?.let { owner ->
            put(UiSceneId("work.team"), UiSceneRenderer { scope ->
                NextWorkTeamScene(owner, onBack = { scope.onBack() })
            })
        }
        ports.evidence?.let { owner ->
            put(UiSceneId("work.requirement_evidence"), UiSceneRenderer { scope ->
                NextWorkEvidenceScene(owner, ports.work, onBack = { scope.onBack() })
            })
        }
        ports.workReads?.let { owner ->
            put(UiSceneId("work.plan_review"), UiSceneRenderer { scope ->
                NextWorkPlanScene(owner, ports.activeSessionId(), onBack = { scope.onBack() })
            })
        }
        ports.policy?.let { owner ->
            put(UiSceneId("work.steering_choice"), UiSceneRenderer { scope ->
                NextWorkPolicyScene(owner, onBack = { scope.onBack() })
            })
        }
        ports.tools?.let { owner ->
            put(UiSceneId("work.capabilities"), UiSceneRenderer { scope ->
                NextToolsScene(owner, NextToolsPage.OVERVIEW, onBack = { scope.onBack() },
                    onOpenMcp = { scope.onNavigate(toolsMcp) },
                    onOpenPlugins = { scope.onNavigate(toolsPlugins) },
                    onOpenGithub = { scope.onNavigate(toolsGithub) })
            })
        }
        put(UiSceneId("shell.error"), UiSceneRenderer { scope ->
            NextErrorScene(ports.chat, ports.work,
                onBack = { scope.onBack() },
                onDiagnostics = { scope.onNavigate(UiSceneId("settings.diagnostics")) })
        })
        ports.sessions?.let { owner ->
            put(UiSceneId("shell.new_session"), UiSceneRenderer { scope ->
                NextNewSessionScene(owner, onBack = { scope.onBack() },
                    onCreated = { scope.onNavigate(home) })
            })
            sessionWelcome.forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    NextSessionLandingScene(owner, onBack = { scope.onBack() },
                        onCreate = { scope.onNavigate(UiSceneId("shell.new_session")) },
                        onOpen = { scope.onNavigate(home) })
                })
            }
        }
        ports.files?.let { owner ->
            put(UiSceneId("workspace.preview"), UiSceneRenderer { scope ->
                NextWorkspaceScene(owner, onBack = { scope.onBack() })
            })
        }
        ports.files?.let { owner ->
            put(workspace, UiSceneRenderer { scope -> NextWorkspaceScene(owner, onBack = { scope.onBack() }) })
        }
        val memoryOwner = ports.memory
        val settingsOwner = ports.settings
        if (memoryOwner != null && settingsOwner != null) put(memories, UiSceneRenderer { scope ->
            NextMemoryScene(memoryOwner, settingsOwner, onBack = { scope.onBack() })
        })
        val sessionOwner = ports.sessions
        val historyOwner = ports.transcript
        if (sessionOwner != null && historyOwner != null) {
            put(globalSearch, UiSceneRenderer { scope ->
                NextGlobalSearchScene(sessionOwner, historyOwner,
                    onOpenSession = { scope.onNavigate(home) },
                    onBack = { scope.onBack() })
            })
        }
        ports.diary?.let { owner ->
            put(diaryRoute, UiSceneRenderer { scope ->
                NextDiaryScene(ports.gallery, owner, onBack = { scope.onBack() })
            })
            put(diaryPage, UiSceneRenderer { scope ->
                NextDiaryScene(ports.gallery, owner, onBack = { scope.onBack() })
            })
        }
        ports.automation?.let { owner ->
            put(tasksRoute, UiSceneRenderer { scope ->
                NextAutomationScene(owner, onBack = { scope.onBack() })
            })
            put(tasksPage, UiSceneRenderer { scope ->
                NextAutomationScene(owner, onBack = { scope.onBack() })
            })
            put(taskEditor, UiSceneRenderer { scope ->
                NextAutomationScene(owner, onBack = { scope.onBack() }, openEditor = true)
            })
            put(chatTasks, UiSceneRenderer { scope ->
                NextAutomationScene(owner, onBack = { scope.onBack() }, onlyChat = true)
            })
        }
        val mediaOwner = ports.media
        if (mediaOwner != null && settingsOwner != null) {
            put(mediaCreation, UiSceneRenderer { scope ->
                NextMediaScene(mediaOwner, settingsOwner, ports.activeSessionId(),
                    onBack = { scope.onBack() }, creationFirst = true)
            })
            put(mediaResults, UiSceneRenderer { scope ->
                NextMediaScene(mediaOwner, settingsOwner, ports.activeSessionId(),
                    onBack = { scope.onBack() }, creationFirst = false)
            })
        }
        if (ports.checkForUpdate != null && ports.updateStatus != null) {
            put(UiSceneId("settings.update"), UiSceneRenderer { scope ->
                NextUpdateScene(ports.checkForUpdate, ports.updateStatus, onBack = { scope.onBack() })
            })
        }
        ports.hostPreferences?.let { owner ->
            listOf("settings.appearance", "settings.notifications", "settings.permissions").forEach { key ->
                put(UiSceneId(key), UiSceneRenderer { scope ->
                    NextHostPreferencesScene(key, owner, onBack = { scope.onBack() })
                })
            }
        }
        if (settingsOwner != null) {
            (settingsScenes + UiSceneId("settings.model_performance") + UiSceneId("settings.pricing")).forEach { id ->
                put(id, UiSceneRenderer { scope ->
                    if (id.value == "settings.account") {
                        NextAccountScene(settingsOwner, onBack = { scope.onBack() })
                    } else {
                        NextSettingsScene(id.value, settingsOwner, ports.usage,
                            onBack = { scope.onBack() },
                            onNavigate = { key -> scope.onNavigate(UiSceneId(key)) })
                    }
                })
            }
        }
        ports.tools?.let { owner ->
            put(toolsRoute, UiSceneRenderer { scope ->
                NextToolsScene(owner, NextToolsPage.OVERVIEW, onBack = { scope.onBack() },
                    onOpenMcp = { scope.onNavigate(toolsMcp) },
                    onOpenPlugins = { scope.onNavigate(toolsPlugins) },
                    onOpenGithub = { scope.onNavigate(toolsGithub) })
            })
            put(toolsMcp, UiSceneRenderer { scope ->
                NextToolsScene(owner, NextToolsPage.MCP, onBack = { scope.onBack() },
                    onOpenMcp = { scope.onNavigate(toolsMcp) },
                    onOpenPlugins = { scope.onNavigate(toolsPlugins) },
                    onOpenGithub = { scope.onNavigate(toolsGithub) })
            })
            put(toolsPlugins, UiSceneRenderer { scope ->
                NextToolsScene(owner, NextToolsPage.PLUGINS, onBack = { scope.onBack() },
                    onOpenMcp = { scope.onNavigate(toolsMcp) },
                    onOpenPlugins = { scope.onNavigate(toolsPlugins) },
                    onOpenGithub = { scope.onNavigate(toolsGithub) })
            })
            put(toolsGithub, UiSceneRenderer { scope ->
                NextToolsScene(owner, NextToolsPage.GITHUB, onBack = { scope.onBack() },
                    onOpenMcp = { scope.onNavigate(toolsMcp) },
                    onOpenPlugins = { scope.onNavigate(toolsPlugins) },
                    onOpenGithub = { scope.onNavigate(toolsGithub) })
            })
        }
     }.filterKeys { it in architectureProbeScenes }

    @Composable
    private fun HomeRoute(scope: UiSceneRenderScope) {
        val liveMode = ports.workModeUpdates
            ?.collectAsStateWithLifecycle(initialValue = ports.workMode())?.value ?: ports.workMode()
        ConversationRoute(scope, liveMode)
    }

    @Composable
    private fun ConversationRoute(scope: UiSceneRenderScope, isWork: Boolean) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { scope.onNavigate(galleryRoute) }) {
                Text(stringResource(R.string.next_gallery_archive))
            }
            if (ports.tools != null) {
                TextButton(onClick = { scope.onNavigate(toolsRoute) }) {
                    Text(stringResource(R.string.next_tools_title))
                }
            }
            if (ports.files != null) TextButton(onClick = { scope.onNavigate(workspace) }) {
                Text(stringResource(R.string.next_workspace_title))
            }
            // No links to incomplete candidate scenes in an architecture-only probe.
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalNextMediaPort provides ports.media) {
                NextConversationUiPack.Render(
                    documents = ports.documents, forms = ports.forms,
                    reading = ports.reading, drafts = ports.drafts,
                    chat = ports.chat, work = ports.work, workMode = isWork,
                    onClose = { scope.onBack() }, attachments = ports.attachments,
                    sessions = ports.sessions, transcript = ports.transcript,
                    policy = ports.policy, workReads = ports.workReads,
                    files = ports.files, evidence = ports.evidence,
                    shell = ports.shell, team = ports.team, groups = ports.groups,
                    gallery = ports.gallery, personas = ports.selection,
                    personaTransfer = ports.personaTransfer, stories = ports.stories,
                    galleryDeletion = ports.galleryDeletion, gallerySave = ports.gallerySave,
                    portraits = ports.portraits, invocations = ports.invocations,
                )
                }
            }
        }
    }

    @Composable
    private fun GalleryRoute(scope: UiSceneRenderScope) {
        NextPersonaGalleryScene(
            gallery = ports.gallery, selection = ports.selection,
            onBack = { scope.onBack() },
            onStoryOpened = { scope.onNavigate(home) is UiSceneNavigationResult.Changed },
            authoring = ports.authoring, currentPersona = ports.currentPersona,
            transfer = ports.personaTransfer, stories = ports.stories,
            deletion = ports.galleryDeletion, save = ports.gallerySave, portraits = ports.portraits,
        )
    }
}
