package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.v6.V6ApplicationScenePack
import com.labteto.dshmobile.uipack.v6.V6ConversationPorts
import com.labteto.dshmobile.uipack.v6.V6SecondaryPorts
import com.labteto.dshmobile.uihost.UiApplicationPackAdmission
import com.labteto.dshmobile.uihost.UiApplicationPackRegistry
import com.labteto.dshmobile.uihost.UiPackAdmissionRequirements
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All 75 required scenes must correspond to actual renderers, never no-op placeholders. */
class V6FullSceneCoverageTest {
    private inline fun <reified T : Any> owner(): T =
        java.lang.reflect.Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java)
        ) { _, method, _ ->
            error("Owner must not be invoked while registering Scene: ${method.name}")
        } as T

    private fun fullCandidate(): V6ApplicationScenePack {
        val settings = owner<SettingsUiPort>()
        val chat = V6ConversationPorts(
            chat = owner(), work = owner(), drafts = owner(), reading = owner(),
            attachments = owner(), history = owner(), documents = owner(), forms = owner(),
            media = owner(), evidence = owner(), gallery = owner(), selection = owner(),
            groups = owner(), stories = owner(), galleryDeletion = owner(),
            workReads = owner(), workPolicy = owner(), workTeam = owner(),
            replySuggestions = owner(), workMode = { false },
        )
        val extra = V6SecondaryPorts(
            tools = owner(), files = owner(), media = owner(), settings = settings,
            sessionId = { "session-test" }, automation = owner(),
            sessions = owner(), transcript = owner(), memory = owner(), diary = owner(),
            authoring = owner(), currentPersona = owner(), replySuggestions = owner(),
            gallery = owner(), selection = owner(), portraits = owner(),
        )
        return V6ApplicationScenePack(
            owner<HostPreferencesUiPort>(), settings, chat,
            usageOwner = owner(), updateStatus = MutableStateFlow(null),
            checkForUpdate = {}, secondary = extra,
        )
    }

    @Test fun allSeventyFiveRequiredScenesHaveDistinctNonPlaceholderRendererRegistrations() {
        val candidate = fullCandidate()
        val required = LocalUiSceneCatalog.requirements
            .filterNot { it.hostProvided }.map { it.id }.toSet()
        assertEquals(75, required.size)
        assertEquals(required, candidate.manifest.scenes)
        assertEquals(required, candidate.renderers.keys)
        assertTrue(candidate.renderers.values.distinct().size == 75)
    }

    @Test fun fullCoveragePackIsStillBlockedUntilTheProductionAcceptanceCapabilityExists() {
        val candidate = fullCandidate()
        val requirements = UiPackAdmissionRequirements(
            UI_CONTRACT_VERSION, LocalUiSceneCatalog.requirements,
            candidate.manifest.requiredCapabilities -
                UiCapabilityId("v6.migration.accepted"),
            UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), false,
        )
        val selection = UiApplicationPackRegistry(listOf(candidate))
            .select(candidate.manifest.id, requirements)
        assertTrue(selection is UiApplicationPackAdmission.Incompatible)
    }

    @Test fun actualSceneManifestPassesItsOwnStrictScopedContract() {
        val candidate = fullCandidate()
        val requirements = UiPackAdmissionRequirements(
            UI_CONTRACT_VERSION, LocalUiSceneCatalog.requirements,
            candidate.manifest.requiredCapabilities,
            UiWindowClass.COMPACT, UiAccessibility.entries.toSet(), false,
        )
        assertTrue(UiApplicationPackRegistry(listOf(candidate))
            .select(candidate.manifest.id, requirements) is UiApplicationPackAdmission.Admitted)
    }
}
