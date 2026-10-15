package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.ui.screens.settings.SettingsDestination
import com.labteto.dshmobile.uicontract.UiSceneId

/**
 * Fixed, Host-owned deep-link vocabulary. Keys are existing semantic scene IDs,
 * not arbitrary URLs or model/tool output. No host SYSTEM/SLOT capability is exposed.
 */
internal object LocalApplicationSceneDeepLinks {
    val targets: Map<String, UiSceneId> = buildMap {
        LocalFeatureCatalog.routes.forEach { route ->
            val key = "route.${route.name.lowercase()}"
            check(put(key, UiSceneId(key)) == null)
        }
        SettingsDestination.entries.forEach { destination ->
            val key = "settings.${destination.name.lowercase()}"
            check(put(key, UiSceneId(key)) == null)
        }
    }

    /** Only an exact, Host-owned URI can request a visible scene. No arbitrary URLs. */
    private val externalSceneUri = Regex("""dsh777://scene/([a-z0-9_.-]{1,80})""")

    fun fromExternalUri(raw: String?): String? {
        val key = raw?.let { externalSceneUri.matchEntire(it)?.groupValues?.get(1) }
            ?: return null
        return key.takeIf(targets::containsKey)
    }

    init {
        val requirements = LocalUiSceneCatalog.requirements.associateBy { it.id }
        require(targets.values.all { requirements[it]?.let { scene ->
            !scene.hostProvided &&
                (scene.kind == com.labteto.dshmobile.uicontract.UiSceneKind.PAGE ||
                    scene.kind == com.labteto.dshmobile.uicontract.UiSceneKind.OVERLAY)
        } == true }) { "Invalid host scene deep-link mapping" }
    }
}
