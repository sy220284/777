package com.labteto.dshmobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxMessageRow
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxRichReply
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxTypingIndicator
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiHue
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

    private fun capture(name: String, sceneId: String? = null) {
        compose.waitForIdle()
        // Capture the rendered scene subtree, not whichever Activity window is currently foreground.
        // This prevents mislabeled setting/tool screenshots from passing for conversation scenes.
        val screenshot = if (sceneId == null) {
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } else {
            val scene = compose.onNodeWithTag("zx-acceptance-$sceneId").assertIsDisplayed()
            if (sceneId == "chat.gallery_delete") {
                compose.onNodeWithText("确认删除").assertIsDisplayed()
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            } else scene.captureToImage().asAndroidBitmap()
        }
        File(directory, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Screenshot failed: $name" }
        }
        screenshot.recycle()
    }

    @Test fun portraitSelectionDoesNotCommitAndCropKeepsOriginalPixels() {
        val ports = ready()
        val context = compose.activity
        val source = File(context.cacheDir, "camera/portrait-crop-test.png").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        android.graphics.Canvas(bitmap).drawRect(100f, 0f, 200f, 100f,
            android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val saved = runBlocking { ports.personaAuthoringUiPort.create(UiPersonaDraft(name = "画像裁切测试", coreIdentity = "测试夹具")) }
        val galleryId = (saved as UiPersonaAuthoringReceipt.Saved).galleryId
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", source).toString()
        try {
            val preview = runBlocking { ports.personaPortraitUiPort.previewImport(uri) }
            assertTrue(preview is UiPersonaPortraitPreviewReceipt.Ready)
            assertEquals(UiPersonaPortraitPreviewReceipt.Missing,
                runBlocking { ports.personaPortraitUiPort.preview(galleryId) })
            val result = runBlocking { ports.personaPortraitUiPort.submit(
                PersonaPortraitUiCommand.Import(galleryId, uri, UiPortraitCrop(.5f, 0f, 1f, 1f))) }
            assertTrue(result is UiPersonaPortraitReceipt.Updated)
            val current = runBlocking { ports.personaPortraitUiPort.preview(galleryId) } as UiPersonaPortraitPreviewReceipt.Ready
            val decoded = android.graphics.BitmapFactory.decodeByteArray(current.bytes, 0, current.bytes.size)
            try {
                assertEquals(100, decoded.width)
                assertEquals(100, decoded.height)
                assertEquals(android.graphics.Color.BLUE, decoded.getPixel(50, 50))
            } finally { decoded.recycle() }
        } finally {
            source.delete()
            runBlocking { ports.personaGalleryDeletionUiPort.delete(DeletePersonaGalleryCommand(ports.sessions.snapshot().identity, galleryId)) }
        }
    }

    @Test fun documentFieldsUseHostDraftsAndReleaseMemoryOnlyValuesWhenClosed() {
        ready()
        var saved: List<String> = emptyList()
        val forms = LocalUiDocumentFormState(emptyList()) { saved = it }
        val document = UiDocument(1, "form-lifecycle",
            UiDocumentSource("form-session", "form-message", 1L, null, null), 1L,
            "表单保存与关闭", listOf(
                UiDocumentNode.Field("notes", "普通备注", "", false, true),
                UiDocumentNode.Field("transient_notes", "本次输入", "", false, true, UiFieldRetention.MEMORY_ONLY),
            ), emptyList(), "表单")
        val key = UiDocumentFormKey("form-session", "form-lifecycle", 1L)
        var visible by mutableStateOf(true)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            ZhaoxiTheme {
                if (visible) com.labteto.dshmobile.uipack.zhaoxi.scenes.ZxDocumentCard(
                    document, knownScenes = emptySet(), pendingApprovalId = null,
                    onAction = { _, _, _ -> UiDocumentReceipt.Rejected(UiCommandRejection.UNSUPPORTED) },
                    forms = forms,
                )
            }
        } }
        compose.onNodeWithContentDescription("普通备注").performTextInput("保留到重开")
        compose.onNodeWithContentDescription("本次输入").performTextInput("仅本次可见")
        compose.runOnIdle {
            assertEquals("保留到重开", forms.values.value[key]?.get("notes"))
            assertEquals("仅本次可见", forms.values.value[key]?.get("transient_notes"))
            assertTrue(saved.contains("保留到重开"))
            assertFalse(saved.contains("仅本次可见"))
            visible = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("保留到重开", forms.values.value[key]?.get("notes"))
            assertFalse(forms.values.value[key].orEmpty().containsKey("transient_notes"))
            visible = true
        }
        compose.onNodeWithContentDescription("普通备注").assertTextEquals("保留到重开")
        compose.onNodeWithContentDescription("本次输入").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("")))
        capture("document-form-retention")
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
                Box(Modifier.fillMaxSize().testTag("zx-acceptance-${current.id.value}")) {
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
                    compose.onAllNodesWithText("页面暂不可用").assertCountEquals(0)
                    capture("$label-${requirement.id.value}", requirement.id.value)
                    index.appendText("$label\t${requirement.id.value}\t${requirement.kind}\tlocal-session-and-persona; no-external-model; empty-message-and-media-states\n")
                }
            }
        } finally { appearance(ports, "system", 1f) }
        assertEquals(106, index.readLines().size) // 75 light + 15 dark + 15 at 130% + header.
    }


    @Test fun commonButtonsExposeRealClickLoadingAndDisabledSemantics() {
        ready()
        var clicks = 0
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                ZhaoxiTheme(hue = ZhaoxiHue.STONE, dark = false, reducedMotion = true) {
                    Column(Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ZxButton("主要操作", { clicks++ }, Modifier.fillMaxWidth())
                        ZxButton("处理中", { clicks++ }, loading = true)
                        ZxButton("不可使用", { clicks++ }, enabled = false)
                        ZxButton("描边操作", { clicks++ }, variant = ZxButtonVariant.Outline)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("主要操作").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithContentDescription("处理中").assertIsNotEnabled()
        compose.onNodeWithContentDescription("不可使用").assertIsNotEnabled()
        compose.onNodeWithContentDescription("描边操作").performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
        capture("fixture-controls")
    }

    /**
     * Offline presentation fixture. Unlike owner-backed 75-scene smoke screenshots, this
     * deliberately includes rich answer content so a reviewer can actually inspect typography.
     * Never label these screenshots as evidence of network streaming or Work tool execution.
     */
    @Test fun offlineReplyFixturesCoverContentAndLargeText() {
        ready()
        val examples = listOf(
            Triple("light", false, 1f),
            Triple("dark", true, 1f),
            Triple("large", false, 1.3f),
            Triple("accessibility-200", false, 2f),
        )
        examples.forEach { (label, dark, fontScale) ->
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    val deviceDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(deviceDensity.density, deviceDensity.fontScale * fontScale)
                    ) {
                        ZhaoxiTheme(hue = ZhaoxiHue.STONE, dark = dark, reducedMotion = true) {
                            Column(
                                Modifier.fillMaxSize()
                                    .background(com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme.colors.bg)
                                    .testTag("zx-acceptance-reply-fixture")
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ZxMessageRow(
                                    UiMessageSnapshot("fixture-user", UiMessageRole.USER,
                                        "帮我整理一份 UI 改造进度，有表格和代码。", 0L),
                                    "我", onLongPress = {}, onBranchSelect = {},
                                )
                                ZxMessageRow(
                                    UiMessageSnapshot("fixture-assistant", UiMessageRole.ASSISTANT,
                                        "# 界面验收示例\n正文排版应保持清晰，**重点内容**醒目但克制。\n\n" +
                                        "## 本轮内容\n- 顶栏层级\n- 回复布局\n- 空白状态\n\n" +
                                        "| 项目 | 状态 | 备注 |\n| --- | --- | --- |\n" +
                                        "| 视觉层级 | 已调整 | 中性色为主 |\n" +
                                        "| 动效 | 已调整 | 尊重减弱动效 |\n\n" +
                                        "```kotlin\nval reply = \"清晰、稳定的回复\"\n```", 0L),
                                    "助手", onLongPress = {}, onBranchSelect = {},
                                )
                                Spacer(Modifier.height(4.dp))
                                ZxTypingIndicator()
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("界面验收示例", substring = true).assertExists()
            capture("fixture-reply-$label", "reply-fixture")
        }
    }

    @Test fun appearancePersistsExactStandardScaleAndExposesSelection() {
        val ports = ready()
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("外观", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("大").performScrollTo().performClick()
        compose.onNodeWithText("大").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("大").assertIsSelected()
        // 字号与字重都有“标准”：以 RadioButton 语义锁定字号档位。
        val standardTextScale = hasText("标准") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.RadioButton,
        )
        compose.onNode(standardTextScale).performScrollTo().performClick()
        // 设置写入和观察者更新异步进行；等待 Owner 确认后仍检验 UI 选中态。
        compose.waitUntil(10_000) {
            runBlocking { ports.hostPreferencesUiPort.snapshots.first().textScale == 1f }
        }
        compose.onNode(standardTextScale).assertIsSelected()
        val snapshot = runBlocking { ports.hostPreferencesUiPort.snapshots.first() }
        assertEquals(1f, snapshot.textScale, .001f)
        compose.onNodeWithText("暮橙").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("暮橙").assertIsSelected()
        capture("interaction-appearance")
    }

    @Test fun localModelFormSavesWithoutNetworkAndKeepsFieldLabels() {
        val ports = ready()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模型", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("＋ 添加 API 模型").performScrollTo().performClick()
        // The create action must not remain active while a draft is open.
        compose.onAllNodesWithText("＋ 添加 API 模型").assertCountEquals(0)
        compose.onNodeWithText("查看全部预设", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("搜索预设模型").performTextInput("deepseek-flash")
        compose.onNodeWithTag("zx-model-preset-deepseek-flash").performScrollTo().performClick()
        compose.onNodeWithText("获取可选模型").performScrollTo().assertExists()
        // 预设不会锁死自定义模型：改名后仍可编辑端点并保存。
        compose.onNodeWithContentDescription("模型名称").performTextReplacement("acceptance-local-model")
        compose.onNodeWithContentDescription("模型名称").assertExists()
        compose.onNodeWithContentDescription("接口地址").performTextReplacement("http://127.0.0.1:9")
        compose.onNodeWithContentDescription("API Key（编辑时留空保留原凭据）").performTextInput("local-acceptance-placeholder")
        compose.onNodeWithText("保存模型").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("模型已保存").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { withTimeout(15_000) { ports.settingsUiPort.state.first { state -> state.modelProfiles.any { it.model == "acceptance-local-model" } } } }
        capture("interaction-model-saved")
    }

    @Test fun unsavedModelDraftRequiresConfirmationBeforeCancel() {
        ready()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模型", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("＋ 添加 API 模型").performScrollTo().performClick()
        compose.onNodeWithContentDescription("模型名称").performTextInput("keep-unsaved-model")
        compose.onNodeWithText("取消编辑").performScrollTo().performClick()
        compose.onNodeWithText("放弃未保存的模型配置？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("模型名称").assertTextContains("keep-unsaved-model")
        compose.onNodeWithText("取消编辑").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("＋ 添加 API 模型").assertExists()
    }

    @Test fun switchSemanticsExposeRealStateAndToggleOwner() {
        val ports = ready()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("记忆").performClick()
        compose.onNodeWithText("记忆设置").performClick()
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
    @Test fun bundledWallpapersAndReducedMotionUseDurableHostPreferences() {
        val ports = ready()
        val entries = mutableSetOf<String>()
        compose.activity.assets.open("e818_wallpapers.zip").use { source ->
            java.util.zip.ZipInputStream(source).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    entries += item.name
                }
            }
        }
        assertEquals((0..9).map { "w$it.webp" }.toSet(), entries)
        val previous = runBlocking { ports.hostPreferencesUiPort.snapshots.first() }
        try {
            runBlocking {
                assertTrue(ports.hostPreferencesUiPort.submit(UiHostPreferencesCommand.Appearance(
                    "light", "stone", 1f, 0, .5f, .5f, .5f, 0f, true, true,
                )))
                withTimeout(15_000) {
                    ports.hostPreferencesUiPort.snapshots.first {
                        it.presetWallpaperEnabled && it.motionScale == 0f
                    }
                }
                assertTrue(ports.hostPreferencesUiPort.submit(
                    UiHostPreferencesCommand.SelectWallpaperPreset("w7.webp")))
                withTimeout(15_000) {
                    ports.hostPreferencesUiPort.snapshots.first {
                        it.presetWallpaperEnabled && it.selectedWallpaperPreset == "w7.webp"
                    }
                }
            }
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    ZhaoxiTheme(hue = ZhaoxiHue.STONE, dark = false, reducedMotion = true) {
                        Box(Modifier.fillMaxSize().testTag("zx-acceptance-wallpaper-fixture")) {
                            com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiBackground(
                                Modifier.fillMaxSize(),
                                wallpaperPreset = "w2.webp",
                                adaptiveContrast = true,
                            )
                        }
                    }
                }
            }
            capture("fixture-wallpaper-stone-light", "wallpaper-fixture")
        } finally {
            runBlocking {
                assertTrue(ports.hostPreferencesUiPort.submit(
                    UiHostPreferencesCommand.SelectWallpaperPreset(previous.selectedWallpaperPreset)))
                assertTrue(ports.hostPreferencesUiPort.submit(UiHostPreferencesCommand.Appearance(
                    previous.theme, previous.accent, previous.textScale, previous.textWeight,
                    previous.transparency, previous.ambientGlow, previous.componentEmphasis,
                    previous.motionScale, previous.adaptiveContrast,
                    previous.presetWallpaperEnabled,
                )))
            }
        }
    }

    /**
     * Offline typography and scroll fixture: tests long tables/200% text without pretending
     * to prove network streaming or live approval end-to-end.
     */
    @Test fun longTableAtTwoHundredPercentCanReachTailWithoutClipping() {
        ready()
        val table = buildString {
            append("# 长内容验收\n\n| 序号 | 工作结果 | 状态说明 | 路径 |\n")
            append("| --- | --- | --- | --- |\n")
            (1..35).forEach { index ->
                append("| $index | 第 ${index} 项任务 | 等待审批与执行结果均以真实状态为准 | work/artifacts/long-file-$index.md |\n")
            }
            append("\n最后一条记录，滚动需要能够到达这里。")
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 2f)) {
                    ZhaoxiTheme(hue = ZhaoxiHue.STONE, dark = false, reducedMotion = true) {
                        Column(
                            Modifier.fillMaxSize().background(ZhaoxiTheme.colors.bg).testTag("zx-acceptance-longtable")
                                .verticalScroll(rememberScrollState()).padding(16.dp),
                        ) {
                            ZxRichReply(table)
                            Spacer(Modifier.height(24.dp))
                            androidx.compose.material3.Text("滚动终点锚点", color = ZhaoxiTheme.colors.ink)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("滚动终点锚点").performScrollTo().assertIsDisplayed()
        capture("fixture-table-200-percent-scrolled", "longtable")
    }

}
