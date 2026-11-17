package com.labteto.dshmobile.ui.audit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.automation.AutomationTask
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageModeAnalytics
import com.labteto.dshmobile.ui.screens.settings.UsageEfficiency
import com.labteto.dshmobile.local.chat.ChatDiaryEntry
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.local.CharacterDiaryScreen
import com.labteto.dshmobile.ui.screens.local.PersonaGalleryAddPanel
import com.labteto.dshmobile.ui.screens.local.PersonaGalleryScreen
import com.labteto.dshmobile.ui.screens.tasks.TaskCard
import com.labteto.dshmobile.ui.screens.tools.ExtensionCatalogCard
import com.labteto.dshmobile.ui.screens.tools.ExtensionMarketState
import com.labteto.dshmobile.ui.screens.tools.ExtensionMarketStatus
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.shenyan.designsystem.foundation.LocalSyColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Production content layouts with explicit sample data; no accounts, jobs or model calls. */
@RunWith(Parameterized::class)
class ContentPagesAuditTest(
    private val page: String,
    private val theme: ThemePreference,
    private val fontScale: Float,
    private val width: Int,
) {
    @get:Rule val compose = createComposeRule()
    private val persona = PersonaProfile(id = "ui-audit", name = "林间的旅人",
        coreIdentity = "一位记录沿途见闻的旅行者，喜欢安静地听人讲述自己的故事。",
        franchise = "原生界面排版样例", timelinePosition = "重逢后的第二个清晨")
    private val entry = PersonaGalleryEntry("ui-audit", persona)
    private val now = 1794816000000L

    @Test fun productionContentKeepsItsNativeReadingLayout() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DshTheme(preference = theme) {
                    Box(Modifier.width(width.dp).fillMaxHeight().testTag("content-audit-frame")) {
                        when (page) {
                            "persona-detail" -> PersonaGalleryScreen(
                                entries = listOf(entry), initialEntryId = entry.id,
                                presets = emptyList(), currentPersona = persona,
                                currentGalleryId = entry.id, currentGalleryStoryId = null,
                                currentHasUnsavedChanges = false, currentSessionId = "ui-audit", canSave = false,
                                onSaveCurrent = { _, _, _, _ -> error("视觉样例") },
                                onHistory = { _, _, _ -> com.labteto.dshmobile.uicontract.UiPersonaGalleryHistoryReceipt.Missing },
                                onEditStoryDetails = { _, _, _, _ -> error("视觉样例") },
                                onRenameStory = { _, _, _ -> error("视觉样例") },
                                onInspect = { _, _ -> error("视觉样例") },
                                onApplySuggestions = { _, _ -> error("视觉样例") },
                                onDelete = { error("视觉样例") }, onDeleteStory = { _, _ -> error("视觉样例") },
                                onDeleteHistoryMessage = { _, _, _ -> error("视觉样例") },
                                onExport = { _, _ -> error("视觉样例") }, onImport = { _, _, _ -> error("视觉样例") },
                                onInstallPreset = { error("视觉样例") }, onSetPortrait = { _, _ -> error("视觉样例") },
                                onRemovePortrait = { error("视觉样例") }, onStart = { _, _, _ -> },
                                onCreate = {}, onDismiss = {},
                            )
                            "persona-presets" -> PersonaGalleryAddPanel(
                                presets = PersonaPresetCatalog.presets, installedPresetIds = emptySet(), busy = false,
                                onBack = {}, onCreate = {}, onImport = {}, onInstallPreset = {}, onRequestHidePreset = {},
                            )
                            "diary-content" -> CharacterDiaryScreen(
                                gallery = listOf(entry), currentPersona = persona, currentGalleryId = entry.id,
                                loadEntries = { key -> listOf(ChatDiaryEntry(
                                    id = "ui-audit", subjectKey = key, personaName = persona.name,
                                    event = "我们一起走过雨后的长街，聊起各自仍然惦记的地方。",
                                    feeling = "轻松，也有一些不舍。", innerThought = "希望下次见面时，还有新的故事可以分享。",
                                    relationshipMeaning = "开始愿意认真倾听对方的心事。", createdAt = now, updatedAt = now,
                                )) },
                                onCorrectEntry = { _, _, _, _ -> false }, onDeactivateEntry = { _, _, _ -> false },
                                onOpenSourceSession = { _, _ -> false }, onDismiss = {},
                            )
                            else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
                                if (page == "task-content") TaskCard(
                                    task = AutomationTask(id = "ui-audit", prompt = "整理本周的阅读记录，并把需要继续研究的主题汇总成一份清晰的清单。",
                                        createdAt = now, nextRunAt = now + 3600000),
                                    onCancel = {}, onPause = {}, onResume = {}, onRunNow = {}, onEdit = {}, onOpenSession = {},
                                ) else if (page == "usage-content") UsageEfficiency(
                                    LocalUsageMode.WORK, TokenUsageModeAnalytics(
                                        mainTokens = 987654321, subagentTokens = 876543210,
                                        averageInputPerTurn = 12345678,
                                        aggregate = TokenUsageAggregate(cacheHitTokens = 10000),
                                    ),
                                ) else ExtensionCatalogCard(
                                    id = "ui-audit", title = "资料整理与内容检索助手",
                                    description = "查询资料、组织线索，并保留可以继续追溯的原始来源。完整说明可从详情页阅读。",
                                    icon = FeatherIcons.Search, accent = LocalSyColors.current.primary,
                                    marketState = ExtensionMarketState(ExtensionMarketStatus.INSTALLED, "已安装"), onOpen = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        captureScrollableUiAudit(compose, "sample-$page-${theme.name}-$fontScale-$width", "content-audit-frame")
        if (page == "usage-content") {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("100.0%", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Expected the production cache-rate layout", layouts.isNotEmpty())
            assertFalse("The complete numeric value must remain readable", layouts.single().hasVisualOverflow)
        }
        if (page == "extension-content") {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("plugin-inventory-title-ui-audit", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Expected the production title layout", layouts.isNotEmpty())
            assertFalse("An installed status must not truncate the identity of the extension", layouts.single().hasVisualOverflow)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-{2}-{3}dp")
        fun cases(): List<Array<Any>> = listOf("persona-detail", "persona-presets", "diary-content", "task-content", "extension-content", "usage-content")
            .flatMap { page -> listOf(arrayOf<Any>(page, ThemePreference.LIGHT, 1f, 390)) +
                listOf(ThemePreference.LIGHT, ThemePreference.DARK, ThemePreference.MATTE_BLACK)
                    .map { arrayOf<Any>(page, it, 2f, 320) } }
    }
}
