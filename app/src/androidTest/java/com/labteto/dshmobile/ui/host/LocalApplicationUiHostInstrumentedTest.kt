package com.labteto.dshmobile.ui.host

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiAccessibility
import com.labteto.dshmobile.uicontract.UiPackManifest
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiWindowClass
import com.labteto.dshmobile.uihost.UiApplicationScenePack
import com.labteto.dshmobile.uihost.UiSceneRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real AppRoot Host rendering and SharedPreferences switching.
 * Packs are synthetic UI fixtures, not an assertion that production V6/Next have 78 renderers.
 */
class LocalApplicationUiHostInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preference get() = context.getSharedPreferences("ui_application_pack", Context.MODE_PRIVATE)

    @Before fun clearSelection() {
        clearHostPreferences()
    }

    @After fun resetSelection() {
        clearHostPreferences()
    }

    private fun clearHostPreferences() {
        preference.edit().remove("selected_pack")
            .remove("navigation_snapshot.fixture-next")
            .remove("navigation_snapshot.fixture-v6").commit()
    }

    private fun fullFixture(id: String, title: String): UiApplicationScenePack {
        val scenes = LocalUiSceneCatalog.requirements.filterNot { it.hostProvided }
            .map { it.id }.toSet()
        return object : UiApplicationScenePack {
            override val manifest = UiPackManifest(
                id = id, version = 1,
                minContractVersion = UI_CONTRACT_VERSION, maxContractVersion = UI_CONTRACT_VERSION,
                scenes = scenes, requiredCapabilities = emptySet(),
                windowClasses = UiWindowClass.entries.toSet(),
                accessibility = UiAccessibility.entries.toSet(), supportsDark = true,
            )
            override val renderers = scenes.associateWith { scene ->
                UiSceneRenderer { _ ->
                    Text(if (scene == UiSceneId("route.home")) title else scene.value)
                }
            }
        }
    }

    @Test fun externalSceneRequestNavigatesThroughFullPackAndReportsActualResult() {
        val next = fullFixture("fixture-next", "Next full-pack home")
        preference.edit().putString("selected_pack", next.manifest.id).commit()
        val acknowledgements = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LocalApplicationUiHost(
                    installedPacks = listOf(next),
                    availableCapabilities = emptySet(),
                    requestedSceneKey = "settings.models",
                    onSceneRequestProcessed = { key, result ->
                        if (result is com.labteto.dshmobile.uihost.UiSceneNavigationResult.Changed)
                            acknowledgements += key
                    },
                ) { Text("Explicit legacy screen") }
            }
        }
        compose.onNodeWithText("settings.models").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("settings.models"), acknowledgements)
            val saved = preference.getString("navigation_snapshot.fixture-next", null)
            assertEquals(UiSceneId("settings.models"),
                com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec
                    .decodeOrNull(saved)?.stack?.last())
        }
    }

    @Test fun coldStartRestoresOnlyValidatedPackOwnedNavigationState() {
        val next = fullFixture("fixture-next", "Next full-pack home")
        val snapshot = com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec.encode(
            com.labteto.dshmobile.uihost.UiSceneNavigationState(
                next.manifest.id, listOf(UiSceneId("route.home"), UiSceneId("settings.models"))))
        preference.edit().putString("selected_pack", next.manifest.id)
            .putString("navigation_snapshot.fixture-next", snapshot).commit()
        compose.setContent {
            MaterialTheme {
                LocalApplicationUiHost(
                    installedPacks = listOf(next), availableCapabilities = emptySet(),
                ) { Text("Explicit legacy screen") }
            }
        }
        compose.onNodeWithText("settings.models").assertExists()
        compose.onNodeWithText("Next full-pack home").assertDoesNotExist()
    }

    @Test fun corruptSavedPackHistoryRequiresExplicitRecovery() {
        val next = fullFixture("fixture-next", "Next full-pack home")
        val foreign = com.labteto.dshmobile.uihost.UiSceneNavigationSnapshotCodec.encode(
            com.labteto.dshmobile.uihost.UiSceneNavigationState(
                "old-pack", listOf(UiSceneId("route.home"))))
        preference.edit().putString("selected_pack", next.manifest.id)
            .putString("navigation_snapshot.fixture-next", foreign).commit()
        compose.setContent {
            MaterialTheme {
                LocalApplicationUiHost(
                    installedPacks = listOf(next), availableCapabilities = emptySet(),
                ) { Text("Explicit legacy screen") }
            }
        }
        compose.onNodeWithText(context.getString(R.string.ui_pack_incompatible)).assertExists()
        compose.onNodeWithText("Next full-pack home").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_pack_back_to_stable)).performClick()
        compose.onNodeWithText("Explicit legacy screen").assertExists()
        compose.runOnIdle {
            assertEquals(null, preference.getString("navigation_snapshot.fixture-next", null))
        }
    }

    @Test fun changingDurableSelectionActuallySwapsTwoWholeApplicationRenderers() {
        val first = fullFixture("fixture-v6", "V6 full-pack home")
        val second = fullFixture("fixture-next", "Next full-pack home")
        compose.setContent {
            MaterialTheme {
                LocalApplicationUiHost(
                    installedPacks = listOf(first, second),
                    availableCapabilities = emptySet(),
                ) { Text("Explicit legacy screen") }
            }
        }
        compose.onNodeWithText("Explicit legacy screen").assertExists()
        compose.runOnIdle { preference.edit().putString("selected_pack", "fixture-v6").commit() }
        compose.onNodeWithText("V6 full-pack home").assertExists()
        compose.runOnIdle { preference.edit().putString("selected_pack", "fixture-next").commit() }
        compose.onNodeWithText("Next full-pack home").assertExists()
        compose.onNodeWithText("V6 full-pack home").assertDoesNotExist()

        // An invalid saved ID is reported, not silently routed into V6.
        compose.runOnIdle { preference.edit().putString("selected_pack", "missing-pack").commit() }
        compose.onNodeWithText(context.getString(R.string.ui_pack_incompatible)).assertExists()
        compose.onNodeWithText("Explicit legacy screen").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ui_pack_back_to_stable)).performClick()
        compose.onNodeWithText("Explicit legacy screen").assertExists()
    }
}
