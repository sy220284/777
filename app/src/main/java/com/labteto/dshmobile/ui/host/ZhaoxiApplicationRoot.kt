package com.labteto.dshmobile.ui.host

import androidx.compose.foundation.layout.systemBarsPadding
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.ui.AppViewModel
import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneKind
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiApplicationScenePack
import com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiConversationPorts
import com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiSecondaryPorts
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackPreference
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiApplicationPackSelection
import com.labteto.dshmobile.uihost.UiApplicationPackSelectionResult
import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.RenderHostedScene

/** Only stores a pack identifier. Domain state, messages, sessions and drafts never pass here. */
private class ZhaoxiPackPreference(context: Context) : UiApplicationPackPreference {
    private val store = context.getSharedPreferences("ui_pack_selection", Context.MODE_PRIVATE)
    override fun readSelectedId(): String? = store.getString("active_pack_id", null)
    override fun persistSelectedId(id: String): Boolean =
        store.edit().putString("active_pack_id", id).commit()
}

/**
 * App-only composition root: Host validates the ENTIRE scene catalog before rendering.
 * Zhaoxi consumes typed ports only. No V6 renderer or parallel business store is installed.
 */
@Composable
internal fun ZhaoxiApplicationRoot(
    owner: LocalApplicationFeaturePortsOwner,
    app: AppViewModel,
    preferences: HostPreferencesUiPort,
    onAtRootBack: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = isSystemInDarkTheme()
    val window = when {
        configuration.screenWidthDp >= 840 -> UiWindowClass.EXPANDED
        configuration.screenWidthDp >= 600 -> UiWindowClass.MEDIUM
        else -> UiWindowClass.COMPACT
    }
    val targets = androidx.compose.runtime.saveable.rememberSaveable(saver = com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiSceneTargets.Saver) { com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiSceneTargets() }
    val pack = remember(owner, app, preferences, targets) {
        createZhaoxiApplicationPack(owner, preferences, targets, app.updateInstallStatus) {
            app.checkForUpdateAndInstall(BuildConfig.VERSION_NAME)
        }
    }
    val registry = remember(pack) { UiApplicationPackRegistry(listOf(pack)) }
    val requirements = remember(owner.connectedCapabilities, window, dark) {
        UiPackAdmissionRequirements(
            contractVersion = UI_CONTRACT_VERSION,
            requiredScenes = LocalUiSceneCatalog.requirements,
            availableCapabilities = owner.connectedCapabilities,
            windowClass = window,
            requiredAccessibility = setOf(UiAccessibility.SCREEN_READER),
            dark = dark,
        )
    }
    val chooser = remember(registry, context) {
        UiApplicationPackSelection(registry, ZhaoxiPackPreference(context), "zhaoxi")
    }
    var selected by remember(chooser, requirements) {
        mutableStateOf(chooser.restore(requirements))
    }
    val systemOperations = remember { com.labteto.dshmobile.uihost.UiHostSystemOperationOwner() }
    androidx.compose.runtime.CompositionLocalProvider(
        com.labteto.dshmobile.uihost.LocalUiHostSystemOperationOwner provides systemOperations,
        com.labteto.dshmobile.uihost.LocalUiPrivateTextCipher provides owner.privateTextCipher,
    ) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().systemBarsPadding()) {
    when (val result = selected) {
        is UiApplicationPackSelectionResult.Selected -> {
            val admission = result.admission
            val navigation = remember(admission.pack.manifest) {
                val links = LocalUiSceneCatalog.requirements.asSequence()
                    .filter { !it.hostProvided && it.id in admission.pack.manifest.scenes &&
                        it.kind in setOf(UiSceneKind.PAGE, UiSceneKind.OVERLAY) }
                    .associate { it.id.value to it.id }
                UiHostSceneNavigation(LocalUiSceneCatalog.requirements,
                    admission.pack.manifest, UiSceneId("route.home"), links)
            }
            val snapshot by owner.sessions.snapshots.collectAsState(initial = owner.sessions.snapshot())
            // Activity recreation may briefly expose an unbound/loading Session projection.
            // Keep the last *real* session identity so the restored Host back stack is not
            // discarded before the Owner has finished restoring the same conversation.
            // Mode alone never invalidates a settings/file navigation stack.
            var stableSessionScope by rememberSaveable { mutableStateOf("") }
            val restoredScope = snapshot.identity?.sessionId?.takeIf { it.isNotBlank() }
            if (restoredScope != null && stableSessionScope != restoredScope) {
                stableSessionScope = restoredScope
            }
            val conversationRequest by owner.conversationNavigation.collectAsState()
            val conversationScene = activatedConversationScene(conversationRequest, snapshot)
            val requested by owner.sceneRequest.collectAsState()
            val request = requested?.takeIf { id -> id in LocalUiSceneCatalog.requirements.map { it.id.value } }
            val access = admission.RenderHostedScene(
                navigation = navigation,
                onAtRootBack = onAtRootBack,
                requestedDeepLinkKey = conversationScene ?: request,
                onDeepLinkProcessed = { key, _ ->
                    if (key == conversationScene && owner.conversationNavigation.value == conversationRequest)
                        owner.conversationNavigation.value = null
                    else owner.consumeSceneRequest(key)
                },
                navigationStateScope = stableSessionScope,
            )
            if (access !is com.labteto.dshmobile.uihost.UiSceneRenderAccess.Ready)
                IntegrationBlocked("朝夕场景导航状态失效。请重启应用以重新建立安全导航栈。", null)
        }
        is UiApplicationPackSelectionResult.Rejected ->
            IntegrationBlocked("朝夕 UI 尚未通过完整场景和能力准入，请检查集成版本。") {
                selected = chooser.choose("zhaoxi", requirements)
            }
        is UiApplicationPackSelectionResult.PersistenceFailed ->
            IntegrationBlocked("无法持久保存朝夕 UI 选择，请检查本地存储权限。") {
                selected = chooser.choose("zhaoxi", requirements)
            }
    }
    }
    }
}

@Composable
private fun IntegrationBlocked(message: String, retry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("朝夕 UI 接入校验")
        Text(message, Modifier.padding(vertical = 12.dp))
        if (retry != null) Button(onClick = retry) { Text("重试接入") }
    }
}

/** Single production assembly, also exercised by device renderer acceptance. */
internal fun createZhaoxiApplicationPack(
    owner: LocalApplicationFeaturePortsOwner,
    preferences: HostPreferencesUiPort,
    targets: com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiSceneTargets,
    updateStatus: kotlinx.coroutines.flow.StateFlow<String?>,
    checkForUpdate: () -> Unit,
): ZhaoxiApplicationScenePack = ZhaoxiApplicationScenePack(
        preferences = preferences,
        targets = targets,
        settings = owner.settingsUiPort,
        conversations = ZhaoxiConversationPorts(
            chat = owner.chat,
            work = owner.work,
            drafts = owner.drafts,
            reading = owner.reading,
            attachments = owner.attachmentUiPort,
            history = owner.messageHistoryUiPort,
            documents = owner.documents,
            forms = owner.forms,
            media = owner.mediaUiPort,
            evidence = owner.workEvidenceUiPort,
            gallery = owner.personaGalleryUiPort,
            selection = owner.personaSelectionUiPort,
            groups = owner.chatGroupUiPort,
            stories = owner.personaStoryUiPort,
            galleryDeletion = owner.personaGalleryDeletionUiPort,
            personaAuthoring = owner.personaAuthoringUiPort,
            workReads = owner.workReadUiPort,
            workPolicy = owner.workPolicyUiPort,
            workTeam = owner.workTeamUiPort,
            replySuggestions = owner.replySuggestionsUiPort,
            files = owner.filesUiPort,
            workMode = owner::isWorkMode,
            workModeUpdates = owner.workModeUpdates,
        ),
        usageOwner = owner.usageUiPort,
        updateStatus = updateStatus,
        checkForUpdate = checkForUpdate,
        secondary = ZhaoxiSecondaryPorts(
            tools = owner.tools,
            files = owner.filesUiPort,
            media = owner.mediaUiPort,
            settings = owner.settingsUiPort,
            sessionId = { owner.currentSessionId().takeIf(String::isNotBlank) },
            automation = owner.automationUiPort,
            sessions = owner.sessions,
            transcript = owner.messageHistoryUiPort,
            memory = owner.memoryUiPort,
            diary = owner.diaryUiPort,
            authoring = owner.personaAuthoringUiPort,
            currentPersona = owner.currentPersonaUiPort,
            replySuggestions = owner.replySuggestionsUiPort,
            gallery = owner.personaGalleryUiPort,
            selection = owner.personaSelectionUiPort,
            portraits = owner.personaPortraitUiPort,
            modelPerformance = owner.modelPerformance,
            pricing = owner.pricing,
            transfer = owner.personaTransferUiPort,
        ),
    )
