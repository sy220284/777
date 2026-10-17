package com.labteto.dshmobile.ui.host

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.security.MessageDigest
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
import com.labteto.dshmobile.uicontract.SessionUiPort
import com.labteto.dshmobile.uicontract.SessionUiCommand
import com.labteto.dshmobile.uicontract.UiCommandReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiHostSceneNavigation
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uihost.RenderHostedScene
import com.labteto.dshmobile.uihost.UiSceneRenderAccess
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.screens.local.LocalHarnessViewModel
import com.labteto.dshmobile.uipack.v6.V6ApplicationScenePack
import com.labteto.dshmobile.uipack.v6.V6ConversationPorts
import com.labteto.dshmobile.uipack.v6.V6SecondaryPorts
import kotlinx.coroutines.flow.map

/** The original shipped screen is an explicit compatibility mode, never a fake complete UiPack. */
internal const val LEGACY_V6_APP_UI = "legacy-v6-shell"

/** Never route a notification to Home until the Session Owner confirms the exact target. */
internal fun localNotificationHomeReady(target: String?, snapshot: com.labteto.dshmobile.uicontract.UiSessionsSnapshot?): Boolean =
    target != null && snapshot?.loading == false && snapshot.identity?.sessionId == target

/**
 * Persisted UI navigation is scoped to the actual locally-owned conversation.
 * A digest avoids placing raw private session identifiers in preference key names.
 * The absent-session scope is distinct from a real session named "none".
 */
internal fun localNavigationSnapshotKey(packId: String, sessionId: String?): String {
    val scope = if (sessionId == null) "no-session" else "session:${sessionId.length}:$sessionId"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    return "navigation_snapshot.$packId.$digest"
}


/**
 * Installed packages are candidates, not admitted packages. The production Host
 * checks the COMPLETE scene catalog and real renderers before executing a package.
 * Only the V6 migration candidate is installed; legacy UI remains stable until verified.
 */
/** V6 observes Session Owner directly; the legacy Shell has no execution authority. */
internal class LocalV6SessionBinding(private val sessions: SessionUiPort) {
    fun isWork(): Boolean = sessions.snapshot().usageMode == LocalUsageMode.WORK
    fun sessionId(): String = sessions.snapshot().identity?.sessionId.orEmpty()
    val workModeUpdates: kotlinx.coroutines.flow.Flow<Boolean> =
        sessions.snapshots.map { it.usageMode == LocalUsageMode.WORK }
}

internal object LocalApplicationPackInstallations {
    fun fromOwners(owner: LocalHarnessViewModel,
        updateStatus: kotlinx.coroutines.flow.StateFlow<String?>? = null,
        checkForUpdate: (() -> Unit)? = null,
    ): List<UiApplicationScenePack> {
        val session = LocalV6SessionBinding(owner.sessionUiPort)
        return listOf(
            // Migrating V6 has only actual settings and conversation renderers; Host denies it
            // until every required Scene exists, so current users stay on legacy stable V6.
            V6ApplicationScenePack(
                owner.hostPreferencesUiPort,
                owner.settingsUiPort,
                V6ConversationPorts(
                    chat = owner.chatUiPort, work = owner.workConversationUiPort,
                    drafts = owner.conversationDraftUiPort,
                    reading = owner.conversationReadingUiPort,
                    attachments = owner.attachmentUiPort, history = owner.messageHistoryUiPort,
                    documents = owner.uiDocumentUiPort, forms = owner.uiDocumentFormPort,
                    media = owner.mediaUiPort, evidence = owner.workEvidenceUiPort,
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
                    workMode = session::isWork,
                    workModeUpdates = session.workModeUpdates,
                ),
                usageOwner = owner.usageUiPort,
                updateStatus = updateStatus,
                checkForUpdate = checkForUpdate,
                secondary = V6SecondaryPorts(
                    tools = owner.toolsUiPort, files = owner.filesUiPort,
                    media = owner.mediaUiPort, settings = owner.settingsUiPort,
                    sessionId = session::sessionId,
                    automation = owner.automationUiPort, sessions = owner.sessionUiPort,
                    transcript = owner.messageHistoryUiPort,
                    memory = owner.memoryUiPort, diary = owner.diaryUiPort,
                    authoring = owner.personaAuthoringUiPort,
                    currentPersona = owner.currentPersonaUiPort,
                    replySuggestions = owner.replySuggestionsUiPort,
                    gallery = owner.personaGalleryUiPort,
                    selection = owner.personaSelectionUiPort,
                    portraits = owner.personaPortraitUiPort,
                    modelPerformance = LocalModelPerformanceUiOwner,
                    pricing = owner.memoryUiPort as? com.labteto.dshmobile.uicontract.PricingUiPort,
                ),
            ),
        )
    }
}

internal sealed interface LocalApplicationUiDecision {
    data object Legacy : LocalApplicationUiDecision
    data class Hosted(val admission: UiApplicationPackAdmission.Admitted) : LocalApplicationUiDecision
    data class Unavailable(val selectedId: String, val rejection: UiApplicationPackAdmission) : LocalApplicationUiDecision
}

/**
 * Exact Host admission, with no null-renderer, manifest-only or silent legacy fallback.
 * The selected ID comes from the app preference, never from a model or UI pack.
 */
internal fun decideApplicationUi(
    selectedId: String?,
    installed: Collection<UiApplicationScenePack>,
    requirements: UiPackAdmissionRequirements,
): LocalApplicationUiDecision {
    if (selectedId == null || selectedId == LEGACY_V6_APP_UI)
        return LocalApplicationUiDecision.Legacy
    val admission = UiApplicationPackRegistry(installed).select(selectedId, requirements)
    return when (admission) {
        is UiApplicationPackAdmission.Admitted -> LocalApplicationUiDecision.Hosted(admission)
        else -> LocalApplicationUiDecision.Unavailable(selectedId, admission)
    }
}

/**
 * Production entry point for eventual whole-app replacement.
 * This does not advertise a pack selector until all its real renderers exist.
 * An unavailable saved pack is shown explicitly, and requires a user action to reset it.
 */
@Composable
internal fun LocalApplicationUiHost(
    installedPacks: Collection<UiApplicationScenePack>,
    availableCapabilities: Set<UiCapabilityId>,
    requestedSceneKey: String? = null,
    notificationSessionId: String? = null,
    notificationSessions: SessionUiPort? = null,
    onNotificationSessionProcessed: () -> Unit = {},
    onSceneRequestProcessed: (String, com.labteto.dshmobile.uihost.UiSceneNavigationResult) -> Unit = { _, _ -> },
    legacyContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val preference = remember(context) {
        context.getSharedPreferences("ui_application_pack", Context.MODE_PRIVATE)
    }
    var selectedId by remember(preference) {
        mutableStateOf(preference.getString("selected_pack", LEGACY_V6_APP_UI))
    }
    // An admitted pack selection persisted by Settings must swap the live Host too.
    // This listener watches a local preference only: no cloud/network UI-state coupling.
    DisposableEffect(preference) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "selected_pack")
                selectedId = prefs.getString(key, LEGACY_V6_APP_UI)
        }
        preference.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preference.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val width = LocalConfiguration.current.screenWidthDp
    val window = when {
        width >= 840 -> UiWindowClass.EXPANDED
        width >= 600 -> UiWindowClass.MEDIUM
        else -> UiWindowClass.COMPACT
    }
    val requirements = UiPackAdmissionRequirements(
        contractVersion = UI_CONTRACT_VERSION,
        requiredScenes = LocalUiSceneCatalog.requirements,
        // A capability is admitted only after a production Owner composition declares it.
        // Never copy advertised capabilities from a pack's own manifest.
        availableCapabilities = availableCapabilities,
        windowClass = window,
        requiredAccessibility = UiAccessibility.entries.toSet(),
        dark = isSystemInDarkTheme(),
    )
    val decision = remember(installedPacks, selectedId, window, requirements.dark, availableCapabilities) {
        decideApplicationUi(selectedId, installedPacks, requirements)
    }
    when (decision) {
        LocalApplicationUiDecision.Legacy -> legacyContent()
        is LocalApplicationUiDecision.Hosted -> {
            val packId = decision.admission.pack.manifest.id
            // An old overlay or pending screen is not part of a different session.
            // Never migrate a legacy global pack snapshot without a session identity.
            val sessionState = notificationSessions?.let { sessions ->
                val snapshot by sessions.snapshots.collectAsStateWithLifecycle(
                    initialValue = remember(sessions) { sessions.snapshot() })
                snapshot
            }
            val snapshotKey = remember(packId, sessionState?.identity?.sessionId) {
                localNavigationSnapshotKey(packId, sessionState?.identity?.sessionId)
            }
            val persistedSnapshot = remember(preference, snapshotKey) {
                preference.getString(snapshotKey, null)
            }
            val nav = remember(packId, snapshotKey) {
                UiHostSceneNavigation(LocalUiSceneCatalog.requirements,
                    decision.admission.pack.manifest, UiSceneId("route.home"),
                    deepLinks = LocalApplicationSceneDeepLinks.targets)
            }
            // Notification session IDs are only local selectors, never arbitrary scene URLs.
            // Validate the target against the Session Owner catalog before opening it.
            var notificationHomeTarget by remember(packId) {
                mutableStateOf<String?>(null)
            }
            var notificationError by remember(packId) {
                mutableStateOf<String?>(null)
            }
            val notificationUnconfirmed = stringResource(R.string.notification_session_open_unconfirmed)
            LaunchedEffect(packId, notificationSessions, notificationSessionId) {
                val target = notificationSessionId
                val sessions = notificationSessions
                if (target == null || sessions == null) return@LaunchedEffect
                notificationHomeTarget = null
                notificationError = null
                try {
                    require(target.length <= 256 && target.isNotBlank() &&
                        target.none(Char::isISOControl)) { "无效的会话通知目标" }
                    val catalog = sessions.snapshots.first { !it.loading }
                    require(catalog.sessions.any { it.id == target }) {
                        "通知指向的本地会话不存在或已经删除"
                    }
                    val result = sessions.submit(SessionUiCommand.Open(target))
                    if (result == UiCommandReceipt.Accepted ||
                        result == UiCommandReceipt.Queued) {
                        // Accepted/Queued is not proof the target became the active session.
                        // The Host must not navigate the old conversation while switching.
                        val opened = withTimeoutOrNull(15_000L) {
                            sessions.snapshots.first { !it.loading && it.identity?.sessionId == target }
                        }
                        if (opened != null) notificationHomeTarget = target
                        else notificationError = notificationUnconfirmed
                    } else {
                        notificationError = "当前无法打开通知指定的本地会话"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    notificationError = failure.message ?: "通知会话恢复失败"
                } finally {
                    onNotificationSessionProcessed()
                }
            }
            val notificationHomeReady = localNotificationHomeReady(notificationHomeTarget, sessionState)
            val result = decision.admission.RenderHostedScene(
                nav, onAtRootBack = { activity?.finish() },
                requestedDeepLinkKey = if (notificationHomeReady) "route.home"
                    else requestedSceneKey,
                onDeepLinkProcessed = { key, receipt ->
                    if (key == "route.home" && notificationHomeReady)
                        notificationHomeTarget = null
                    else onSceneRequestProcessed(key, receipt)
                },
                persistedNavigationSnapshot = persistedSnapshot,
                onNavigationSnapshotUpdated = { encoded ->
                    preference.edit().putString(snapshotKey, encoded).commit()
                },
                navigationStateScope = snapshotKey,
            )
            notificationError?.let { error ->
                // Explicit failure is visible without treating the notification as an action grant.
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            // An invalid restored history must not turn the application into an empty screen.
            if (result is UiSceneRenderAccess.Rejected) {
                UnavailablePackNotice {
                    // Explicit recovery must clear the rejected pack's saved history.
                    preference.edit().remove(snapshotKey)
                        .putString("selected_pack", LEGACY_V6_APP_UI).commit()
                }
            }
        }
        is LocalApplicationUiDecision.Unavailable -> {
            UnavailablePackNotice {
                preference.edit().putString("selected_pack", LEGACY_V6_APP_UI).commit()
            }
        }
    }
}

@Composable
private fun UnavailablePackNotice(onSelectStable: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.ui_pack_incompatible),
                style = MaterialTheme.typography.titleMedium)
            Button(onClick = onSelectStable) {
                Text(stringResource(R.string.ui_pack_back_to_stable))
            }
        }
    }
}

/** Hardware Back at the root delegates to the Activity; never silently enters an older pack. */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
