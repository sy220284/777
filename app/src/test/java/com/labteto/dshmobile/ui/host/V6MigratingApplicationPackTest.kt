package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.HostPreferencesUiPort
import com.labteto.dshmobile.uicontract.UiHostPreferences
import com.labteto.dshmobile.uicontract.UiHostPreferencesCommand
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiSceneRequirement
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import com.labteto.dshmobile.uipack.v6.V6ApplicationScenePack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real V6 renderers, never a fabricated 75-scene production pack. */
class V6MigratingApplicationPackTest {
    private val port = object : HostPreferencesUiPort {
        override val snapshots: Flow<UiHostPreferences> = flowOf(UiHostPreferences())
        override suspend fun submit(command: UiHostPreferencesCommand) = true
        override fun openSystemPermissions() = true
        override fun openSystemNotifications() = true
    }
    private fun pack() = V6ApplicationScenePack(port)
    // The registry must not execute a business Owner merely to enumerate renderers.
    private val settings = java.lang.reflect.Proxy.newProxyInstance(
        SettingsUiPort::class.java.classLoader,
        arrayOf(SettingsUiPort::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "unrendered-settings-owner"
            "hashCode" -> 42
            "equals" -> false
            else -> error("Scene registration must not call Settings Owner: ${method.name}")
        }
    } as SettingsUiPort
    private fun productionCandidate() = V6ApplicationScenePack(port, settings)
    private fun requirements(scenes: List<UiSceneRequirement>) = UiPackAdmissionRequirements(
        UI_CONTRACT_VERSION, scenes, emptySet(), UiWindowClass.COMPACT,
        UiAccessibility.entries.toSet(), false,
    )

    @Test fun v6SettingsHaveExactlyThreeExecutableIndependentScenes() {
        val expected = setOf("settings.appearance", "settings.notifications",
            "settings.permissions").map(::UiSceneId).toSet()
        assertEquals(expected, pack().manifest.scenes)
        assertEquals(expected, pack().renderers.keys)
        assertTrue(UiSceneId("route.home") !in pack().renderers)
    }

    @Test fun productionCompositionRegistersFiveRealSettingsRenderers() {
        val candidate = productionCandidate()
        val ids = setOf("settings.appearance", "settings.notifications",
            "settings.permissions", "settings.chat", "settings.session").map(::UiSceneId).toSet()
        assertEquals(ids, candidate.manifest.scenes)
        assertEquals(ids, candidate.renderers.keys)
        val selected = LocalUiSceneCatalog.requirements.filter { it.id in ids }
        assertTrue(UiApplicationPackRegistry(listOf(candidate))
            .select(candidate.manifest.id, requirements(selected)) is UiApplicationPackAdmission.Admitted)
    }

    @Test fun productionHostRefusesIncompleteV6Migration() {
        val v6 = productionCandidate()
        val result = decideApplicationUi(v6.manifest.id, listOf(v6),
            requirements(LocalUiSceneCatalog.requirements))
        assertTrue(result is LocalApplicationUiDecision.Unavailable)
        val rejection = (result as LocalApplicationUiDecision.Unavailable).rejection
        assertTrue(rejection is UiApplicationPackAdmission.Incompatible)
        val missing = (rejection as UiApplicationPackAdmission.Incompatible)
            .compatibility.missingScenes
        assertTrue(UiSceneId("route.home") in missing)
        assertTrue(UiSceneId("conversation.composer") in missing)
    }

    @Test fun isolatedThreeSceneContractIsActuallyAdmitted() {
        val v6 = pack()
        val sceneRequirements = LocalUiSceneCatalog.requirements.filter { it.id in v6.manifest.scenes }
        val result = UiApplicationPackRegistry(listOf(v6))
            .select(v6.manifest.id, requirements(sceneRequirements))
        assertTrue(result is UiApplicationPackAdmission.Admitted)
    }
}
