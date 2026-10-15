package com.labteto.dshmobile.ui.host

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/** The original shipped screen is an explicit compatibility mode, never a fake complete UiPack. */
internal const val LEGACY_V6_APP_UI = "legacy-v6-shell"

/**
 * The only production list of fully implemented application packs.
 * Do not register NextConversationUiPack or component-only V6 design libraries here:
 * neither currently owns the 78 real, executable application scene renderers.
 */
internal object LocalApplicationPackInstallations {
    val all: List<UiApplicationScenePack> = emptyList()
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
    if (selectedId.isNullOrBlank() || selectedId == LEGACY_V6_APP_UI)
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
    legacyContent: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preference = remember(context) {
        context.getSharedPreferences("ui_application_pack", Context.MODE_PRIVATE)
    }
    var selectionEpoch by rememberSaveable { mutableIntStateOf(0) }
    val selectedId = remember(preference, selectionEpoch) {
        preference.getString("selected_pack", LEGACY_V6_APP_UI)
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
        availableCapabilities = emptySet<UiCapabilityId>(),
        windowClass = window,
        requiredAccessibility = UiAccessibility.entries.toSet(),
        dark = isSystemInDarkTheme(),
    )
    val decision = remember(installedPacks, selectedId, window, requirements.dark) {
        decideApplicationUi(selectedId, installedPacks, requirements)
    }
    when (decision) {
        LocalApplicationUiDecision.Legacy -> legacyContent()
        is LocalApplicationUiDecision.Hosted -> {
            val nav = remember(decision.admission.pack.manifest.id) {
                UiHostSceneNavigation(LocalUiSceneCatalog.requirements,
                    decision.admission.pack.manifest, UiSceneId("route.home"))
            }
            decision.admission.RenderHostedScene(nav, onAtRootBack = {})
        }
        is LocalApplicationUiDecision.Unavailable -> {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.ui_pack_incompatible),
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        if (preference.edit().putString("selected_pack", LEGACY_V6_APP_UI).commit())
                            selectionEpoch++
                    }) { Text(stringResource(R.string.ui_pack_back_to_stable)) }
                }
            }
        }
    }
}
