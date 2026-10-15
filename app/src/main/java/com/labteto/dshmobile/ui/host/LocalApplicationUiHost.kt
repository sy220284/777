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
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiCapabilityId
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
import com.labteto.dshmobile.uipack.next.NextApplicationFeaturePorts
import com.labteto.dshmobile.uipack.next.NextApplicationScenePack
import kotlinx.coroutines.flow.map

/** The original shipped screen is an explicit compatibility mode, never a fake complete UiPack. */
internal const val LEGACY_V6_APP_UI = "legacy-v6-shell"

/**
 * Installed packages are candidates, not admitted packages. The production Host
 * checks the COMPLETE scene catalog and real renderers before executing a package.
 * The incremental Next pack is intentionally incomplete and cannot replace V6 yet.
 */
internal object LocalApplicationPackInstallations {
    fun fromOwners(owner: LocalHarnessViewModel): List<UiApplicationScenePack> =
        listOf(NextApplicationScenePack(NextApplicationFeaturePorts(
            chat = owner.chatUiPort, work = owner.workConversationUiPort,
            drafts = owner.conversationDraftUiPort, reading = owner.conversationReadingUiPort,
            gallery = owner.personaGalleryUiPort, selection = owner.personaSelectionUiPort,
            workMode = { owner.shellState.value.usageMode == LocalUsageMode.WORK },
            workModeUpdates = owner.shellState.map { it.usageMode == LocalUsageMode.WORK },
            documents = owner.nextUiDocumentUiPort, forms = owner.uiDocumentFormPort,
            attachments = owner.attachmentUiPort, sessions = owner.sessionUiPort,
            transcript = owner.messageHistoryUiPort, policy = owner.workPolicyUiPort,
            workReads = owner.workReadUiPort, files = owner.filesUiPort,
            evidence = owner.workEvidenceUiPort, shell = owner.conversationShellUiPort,
            team = owner.workTeamUiPort, groups = owner.chatGroupUiPort,
            tools = owner.toolsUiPort, invocations = owner.composerInvocationUiPort,
            authoring = owner.personaAuthoringUiPort,
            memory = owner.memoryUiPort, settings = owner.settingsUiPort,
            personaTransfer = owner.personaTransferUiPort,
            stories = owner.personaStoryUiPort, galleryDeletion = owner.personaGalleryDeletionUiPort,
            gallerySave = owner.personaGallerySaveUiPort, portraits = owner.personaPortraitUiPort,
        )))
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
            val nav = remember(decision.admission.pack.manifest.id) {
                UiHostSceneNavigation(LocalUiSceneCatalog.requirements,
                    decision.admission.pack.manifest, UiSceneId("route.home"),
                    deepLinks = LocalApplicationSceneDeepLinks.targets)
            }
            val result = decision.admission.RenderHostedScene(
                nav, onAtRootBack = { activity?.finish() },
                requestedDeepLinkKey = requestedSceneKey,
                onDeepLinkProcessed = onSceneRequestProcessed,
            )
            // An invalid restored history must not turn the application into an empty screen.
            if (result is UiSceneRenderAccess.Rejected) {
                UnavailablePackNotice {
                    preference.edit().putString("selected_pack", LEGACY_V6_APP_UI).commit()
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
