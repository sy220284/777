package com.labteto.dshmobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.ui.host.*
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiSceneTargets
import com.labteto.dshmobile.uihost.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real local Owners, no model/network calls. Rendering evidence is distinct from business E2E. */
@OptIn(ExperimentalTestApi::class)
class ZhaoxiAcceptanceAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val owner get() = ViewModelProvider(compose.activity)[LocalApplicationFeaturePortsOwner::class.java]
    private val directory get() = File(compose.activity.getExternalFilesDir(null), "zhaoxi-acceptance").apply { mkdirs() }

    private fun ready(): LocalApplicationFeaturePortsOwner = owner.also { ports ->
        compose.waitUntil(30_000) { ports.sessions.snapshot().identity != null && !ports.sessions.snapshot().loading }
    }

    private fun appearance(ports: LocalApplicationFeaturePortsOwner, theme: String, scale: Float) = runBlocking {
        assertTrue(ports.hostPreferencesUiPort.submit(UiHostPreferencesCommand.Appearance(
            theme, "stone", scale, 0, .5f, .5f, .5f, 1f, true)))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun all75RenderersAndThreeAppearanceMatrixUseProductionOwners() {
        val ports = ready()
        val saved = runBlocking { ports.personaAuthoringUiPort.create(UiPersonaDraft(name = "本地验收人物", coreIdentity = "验收夹具")) }
        assertTrue(saved is UiPersonaAuthoringReceipt.Saved)
        val targets = ZhaoxiSceneTargets().apply { galleryId = (saved as UiPersonaAuthoringReceipt.Saved).galleryId }
        val pack = createZhaoxiApplicationPack(ports, ports.hostPreferencesUiPort, targets, MutableStateFlow(null)) { error("Renderer must not check updates implicitly") }
        assertEquals(75, pack.manifest.scenes.size)
        val requirements = LocalUiSceneCatalog.requirements.filter { it.id in pack.manifest.scenes }
        var current by mutableStateOf(requirements.first())
        val root = UiSceneId("route.home")
        val navigation = UiHostSceneNavigation(LocalUiSceneCatalog.requirements, pack.manifest, root)
        val systemOperations = UiHostSystemOperationOwner()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalUiPrivateTextCipher provides ports.privateTextCipher,
                LocalUiHostSystemOperationOwner provides systemOperations) {
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    // SLOT renderers are embedded here; never proposed as navigation targets.
                    key(current.id) {
                        pack.SceneFrame(UiSceneNavigationState(pack.manifest.id, listOf(root, current.id))) {
                            pack.renderers.getValue(current.id).Render(UiSceneRenderScope(current,
                                { navigation.navigate(navigation.initial, it) }, { UiSceneNavigationResult.AtRoot }))
                        }
                    }
                }
            }
        } }
        val representative = setOf("route.home", "conversation.chat", "conversation.work", "route.persona_gallery",
            "chat.gallery_profile", "chat.persona_editor", "settings.root", "settings.models", "settings.appearance",
            "settings.account", "settings.memory", "settings.usage", "media.creation", "route.tools", "route.workspace")
        val index = File(directory, "rendering.tsv")
        index.writeText("appearance\tscene\tkind\tcontext\n")
        try {
            for ((theme, scale) in listOf("light" to 1f, "dark" to 1f, "light" to 1.3f)) {
                appearance(ports, theme, scale)
                val label = if (scale > 1f) "large" else theme
                for (requirement in requirements.filter { label == "light" || it.id.value in representative }) {
                    compose.runOnIdle { current = requirement }
                    compose.waitForIdle()
                    compose.onAllNodesWithText("该场景暂无 Zx* 实装映射", substring = true).assertCountEquals(0)
                    capture("$label-${requirement.id.value}")
                    index.appendText("$label\t${requirement.id.value}\t${requirement.kind}\tlocal-session-and-persona; no-external-model; empty-message-and-media-states\n")
                }
            }
        } finally { appearance(ports, "system", 1f) }
        assertEquals(106, index.readLines().size) // 75 light + 15 dark + 15 at 130%.
    }

    @Test fun appearancePersistsExactStandardScaleAndExposesSelection() {
        val ports = ready()
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("外观", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("大").performScrollTo().performClick()
        compose.onNodeWithText("大").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("大").assertIsSelected()
        compose.onNodeWithText("标准").performClick()
        compose.onNodeWithText("标准").assertIsSelected()
        val snapshot = runBlocking { ports.hostPreferencesUiPort.snapshots.first() }
        assertEquals(1f, snapshot.textScale, .001f)
        compose.onNodeWithText("暮橙").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("暮橙").assertIsSelected()
        capture("interaction-appearance")
    }

    @Test fun localModelFormSavesWithoutNetworkAndKeepsFieldLabels() {
        val ports = ready()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("模型", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("模型名称").performTextInput("acceptance-local-model")
        compose.onNodeWithContentDescription("模型名称").assertExists()
        compose.onNodeWithContentDescription("接口地址").performTextReplacement("http://127.0.0.1:9")
        compose.onNodeWithContentDescription("API Key（编辑时留空保留原凭据）").performTextInput("local-acceptance-placeholder")
        compose.onNodeWithText("保存模型").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("模型已保存").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { withTimeout(15_000) { ports.settingsUiPort.state.first { state -> state.modelProfiles.any { it.model == "acceptance-local-model" } } } }
        capture("interaction-model-saved")
    }

    @Test fun switchSemanticsExposeRealStateAndToggleOwner() {
        val ports = ready()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("行为与采样").performClick()
        val old = runBlocking { ports.settingsUiPort.state.first { !it.loading }.autoMemory }
        val control = compose.onNodeWithContentDescription("自动记忆")
        control.performScrollTo()
        if (old) control.assertIsOn() else control.assertIsOff()
        control.performClick()
        runBlocking { withTimeout(15_000) { ports.settingsUiPort.state.first { it.autoMemory != old } } }
        if (old) control.assertIsOff() else control.assertIsOn()
        capture("interaction-memory-toggle")
        control.performClick()
    }
}
