package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
)

/**
 * Ten independently rendered scenes when Tools, Files and Memory Owners are connected; still incomplete.
 * The production Host must reject this incomplete manifest for 78-scene admission.
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
    private val settingsScenes = setOf(
        "route.settings", "settings.root", "settings.models", "settings.chat",
        "settings.memory", "settings.memory_settings", "settings.advanced",
        "settings.session", "settings.usage", "settings.model_usage",
        "settings.usage_log", "settings.usage_detail",
        "settings.session_storage", "settings.diagnostics", "settings.account",
    ).map(::UiSceneId).toSet()
    private val implementedScenes = buildSet {
        addAll(setOf(home, galleryRoute, chat, work, galleryPage))
        if (ports.tools != null) addAll(setOf(toolsRoute, toolsMcp, toolsPlugins, toolsGithub))
        if (ports.files != null) add(workspace)
        if (ports.memory != null && ports.settings != null) add(memories)
        if (ports.automation != null) addAll(setOf(tasksRoute, tasksPage, taskEditor, chatTasks))
        if (ports.diary != null) addAll(setOf(diaryRoute, diaryPage))
        if (ports.sessions != null && ports.transcript != null) add(globalSearch)
        if (ports.media != null && ports.settings != null) addAll(setOf(mediaCreation, mediaResults))
        if (ports.settings != null) addAll(settingsScenes)
    }

    override val manifest = UiPackManifest(
        id = "next-application-incremental", version = 1,
        minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
        scenes = implementedScenes,
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
        if (settingsOwner != null) {
            settingsScenes.forEach { id ->
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
    }

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
            if (ports.memory != null && ports.settings != null) TextButton(onClick = { scope.onNavigate(memories) }) {
                Text(stringResource(R.string.next_memory_title))
            }
            if (ports.sessions != null && ports.transcript != null) TextButton(onClick = { scope.onNavigate(globalSearch) }) {
                Text("跨会话搜索")
            }
            if (ports.diary != null) TextButton(onClick = { scope.onNavigate(diaryRoute) }) {
                Text("人物日记")
            }
            if (ports.automation != null) TextButton(onClick = { scope.onNavigate(tasksRoute) }) {
                Text("任务")
            }
            if (ports.media != null && ports.settings != null) TextButton(onClick = { scope.onNavigate(mediaCreation) }) {
                Text("媒体创作")
            }
            if (ports.settings != null) TextButton(onClick = { scope.onNavigate(UiSceneId("route.settings")) }) {
                Text("设置")
            }
            Box(Modifier.weight(1f)) {
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
