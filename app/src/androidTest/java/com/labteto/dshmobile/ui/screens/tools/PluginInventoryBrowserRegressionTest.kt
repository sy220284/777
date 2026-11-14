package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot as McpServerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PluginInventoryBrowserRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installedPluginDetailsOpenAndCanReturnToConversation() {
        var returned = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"),

                    onBack = {},
                    onManageConnections = {},
                    onReturnToChat = { returned++ },
                )
            }
        }
        compose.onNodeWithText("终端与运行环境").assertIsDisplayed().performClick()
        compose.onNodeWithText("返回对话").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, returned) }
    }
    @Test
    fun serviceConnectionsCategoryUsesLocalMcpInventory() {
        var openedConnections = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    connectedServices = listOf(McpServerSnapshot(
                        id = "local-mcp", transport = "http", target = "http://127.0.0.1:4312",
                        tools = listOf("search"),
                    )),
                    onBack = {},
                    onManageConnections = { openedConnections++ },
                    onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-category-list").performScrollToIndex(1)
        compose.onNodeWithText("已连接").performClick()
        compose.onNodeWithText("MCP 本地服务").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, openedConnections) }
    }

    @Test
    fun useHandsTheSelectedCapabilityToTheComposer() {
        var prompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = { prompt = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-local:android-runtime").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(requireNotNull(prompt).contains("android-runtime"))
        }
    }

    @Test
    fun categoryFiltersTheRealInventory() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime", "local-vision"),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-category-list").performScrollToIndex(4)
        compose.onNodeWithText("创意设计").performClick()
        compose.onNodeWithText("视觉理解").assertIsDisplayed()
        compose.onNodeWithText("终端与运行环境").assertDoesNotExist()
    }

    @Test
    fun skillBrowserShowsInstalledSkillMetadataInsteadOfTheBuiltinPlugin() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-local-builtins"),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry("report", "Build a report", true)),
                    skillsOnly = true, onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithText("报告生成").assertIsDisplayed()
        compose.onNodeWithText("整理资料并制作报告").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-skill:report").assertIsDisplayed()
    }

    @Test
    fun missingPresetCanBeInstalledFromSkillsPage() {
        var selected: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    presets = listOf(com.labteto.dshmobile.local.presentation.LocalPresetSkillUiEntry(
                        "research-check", "资料研究与核实", "查证材料", false,
                    )),
                    skillsOnly = true, onBack = {},
                    onManageConnections = {}, onReturnToChat = {},
                    onInstallPreset = { selected = it },
                )
            }
        }
        compose.onNodeWithText("发现").assertIsDisplayed()
        compose.onNodeWithText("资料研究与核实").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle { assertEquals("research-check", selected) }
    }

    @Test
    fun installedSkillRequiresConfirmationBeforeRemoval() {
        var removed: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "my-skill", "My instructions", true,
                    )),
                    skillsOnly = true, onBack = {},
                    onManageConnections = {}, onReturnToChat = {},
                    onRemoveSkill = { removed = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:my-skill").performClick()
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals(null, removed) }
        compose.onNodeWithText("移除技能").assertIsDisplayed()
        compose.onNodeWithText("移除").performClick()
        compose.runOnIdle { assertEquals("my-skill", removed) }
    }

    @Test
    fun userOnlySkillCanStillBeInvokedExplicitly() {
        var prompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), skillsOnly = true,
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "manual-review", "Only on user request", false,
                    )),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = { prompt = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:manual-review").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("@skill:manual-review\n", prompt)
        }
    }

    @Test
    fun skillsPageExposesFileImportAndConversationalCreation() {
        var imported = 0
        var creationPrompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),

                    skillsOnly = true,
                    onBack = {},
                    onManageConnections = {},
                    onReturnToChat = {},
                    onImportSkill = { imported++ },
                    onUseCapability = { creationPrompt = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-more").performClick()
        compose.onNodeWithTag("skill-import-file").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, imported) }
        compose.onNodeWithTag("plugin-inventory-more").performClick()
        compose.onNodeWithTag("skill-create-in-chat").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val prompt = requireNotNull(creationPrompt)
            org.junit.Assert.assertTrue(prompt.contains("SKILL.md"))
            org.junit.Assert.assertTrue(prompt.contains("确认"))
            org.junit.Assert.assertTrue(prompt.contains(".dsh/skills/"))
        }
    }


    @Test
    fun pluginCardsKeepCompactIconsAndReadableText() {
        compose.setContent {
            DshTheme {
                // CI emulators use a 412dp viewport: exercise a real narrow
                // 320dp page rather than only the roomy default viewport.
                Box(Modifier.width(320.dp)) {
                    PluginInventoryBrowser(
                        localIds = listOf("android-automation"),
                        onBack = {}, onManageConnections = {}, onReturnToChat = {},
                        onUseCapability = {},
                    )
                }
            }
        }
        val icon = compose.onNodeWithTag("plugin-inventory-icon-local:android-automation", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("plugin-inventory-title-local:android-automation", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("plugin-inventory-card-local:android-automation")
            .getUnclippedBoundsInRoot()
        assertTrue("Brand icon must fit inside its own tile", (icon.right - icon.left) <= 56.dp)
        assertTrue("Plugin title must have readable horizontal space", (title.right - title.left) >= 100.dp)
        assertTrue("Plugin card should not become a tall empty slab", (card.bottom - card.top) < 220.dp)
    }

    @Test
    fun skillCardsKeepDescriptionAndActionInOneCompactRow() {
        compose.setContent {
            DshTheme {
                Box(Modifier.width(320.dp)) {
                    PluginInventoryBrowser(
                        localIds = emptyList(),
                        skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                            "research-check", "核查改动正确性并追踪异常、复盘及验证修复结果", true,
                        )),
                        skillsOnly = true,
                        onBack = {}, onManageConnections = {}, onReturnToChat = {},
                        onUseCapability = {},
                    )
                }
            }
        }
        val title = compose.onNodeWithTag("plugin-inventory-title-skill:research-check", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val details = compose.onNodeWithTag("plugin-inventory-detail-skill:research-check", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("plugin-inventory-card-skill:research-check")
            .getUnclippedBoundsInRoot()
        assertTrue("Title must remain readable on narrow screens", (title.right - title.left) >= 100.dp)
        assertTrue("Description stays below title", details.top >= title.bottom)
        assertTrue("Description must stay inside the row", details.right < card.right)
        assertTrue("Compact list rows must not waste vertical space", (card.bottom - card.top) < 130.dp)
    }

    @Test
    fun consecutiveSkillRowsStayDense() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(
                        com.labteto.dshmobile.local.presentation.LocalSkillUiEntry("first", "排查问题并验证修复", true),
                        com.labteto.dshmobile.local.presentation.LocalSkillUiEntry("second", "对代码变更执行审计", true),
                    ),
                    skillsOnly = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = {},
                )
            }
        }
        val first = compose.onNodeWithTag("plugin-inventory-card-skill:first")
            .getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("plugin-inventory-card-skill:second")
            .getUnclippedBoundsInRoot()
        assertTrue("Skill cards must not have giant gaps", (second.top - first.bottom) <= 12.dp)
    }

    @Test
    fun skillSearchPrioritizesResultsOverCreationToolsOnNarrowScreens() {
        compose.setContent {
            DshTheme {
                Box(Modifier.width(320.dp)) {
                    PluginInventoryBrowser(
                        localIds = emptyList(),
                        skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                            "research-check", "研究资料并核实来源", true,
                        )),
                        skillsOnly = true,
                        onBack = {}, onManageConnections = {}, onReturnToChat = {},
                        onImportSkill = {}, onUseCapability = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("skill-import-file").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-search-trigger").performClick()
        compose.onNodeWithTag("plugin-inventory-search").performTextInput("research")
        compose.onNodeWithTag("skill-import-file").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").assertIsDisplayed()
    }

    @Test
    fun searchingDescriptionsHasAUsefulEmptyState() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-automation"),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-search-trigger").performClick()
        compose.onNodeWithTag("plugin-inventory-search").performTextInput("自动执行")
        compose.onNodeWithTag("plugin-inventory-card-local:android-automation").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-search").performTextInput("不存在的插件名称")
        compose.onNodeWithText("没有找到匹配的技能或插件").assertIsDisplayed()
    }

    @Test
    fun existingCardsRemainVisibleDuringSkillUpdates() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "research-check", "核查改动正确性", true,
                    )),
                    skillsOnly = true, loading = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onUseCapability = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").assertIsNotEnabled()
    }

    @Test
    fun embeddedInventoryOmitsNestedPageHeader() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"), embedded = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-search").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-search-trigger").assertIsDisplayed()
        compose.onNodeWithText("插件").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-card-local:android-runtime").assertIsDisplayed()
    }

    @Test
    fun pluginTabDoesNotDuplicateSkillsRuntime() {
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-local-builtins", "android-runtime"),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithText("技能").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-card-local:android-runtime").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-local:android-local-builtins").assertDoesNotExist()
    }

    @Test
    fun failedRefreshKeepsLoadedPluginRowsAndOffersRetry() {
        var retried = 0
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = listOf("android-runtime"),
                    error = "加载失败，请重试",
                    onRetry = { retried++ },
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                )
            }
        }
        compose.onNodeWithText("加载失败，请重试").assertIsDisplayed()
        compose.onNodeWithTag("plugin-inventory-card-local:android-runtime").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun capabilityTabsSwitchSectionsWithoutNavigating() {
        compose.setContent {
            DshTheme {
                val current = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.foundation.layout.Column {
                    CapabilityCenterTabs(current.intValue) { current.intValue = it }
                    androidx.compose.material3.Text("当前标签:${current.intValue}")
                }
            }
        }
        compose.onNodeWithText("当前标签:0").assertIsDisplayed()
        compose.onNodeWithText("技能").performClick()
        compose.onNodeWithText("当前标签:1").assertIsDisplayed()
        compose.onNodeWithText("连接器").performClick()
        compose.onNodeWithText("当前标签:2").assertIsDisplayed()
        compose.onNodeWithText("插件").performClick()
        compose.onNodeWithText("当前标签:0").assertIsDisplayed()
    }


    @Test
    fun githubSkillUpdateIsCheckedAndConfirmedFromExistingDetails() {
        val revision = "abcdef0123456789abcdef0123456789abcdef01"
        var checked: String? = null
        var upgraded: Pair<String, String>? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "upgrade-demo", "示例技能", true,
                        sourceUrl = "https://github.com/openai/skills/tree/$revision/skills/.curated/upgrade-demo",
                    )),
                    pendingSkillUpdates = mapOf("upgrade-demo" to revision),
                    skillsOnly = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onCheckSkillUpdate = { checked = it },
                    onUpgradeSkill = { id, sha -> upgraded = id to sha },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:upgrade-demo").performClick()
        compose.onNodeWithTag("skill-check-update").assertExists().performScrollTo().performClick()
        compose.runOnIdle { assertEquals("upgrade-demo", checked) }
        compose.onNodeWithTag("skill-upgrade").assertExists().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, upgraded) }
        compose.onNodeWithTag("skill-upgrade-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("upgrade-demo" to revision, upgraded) }
    }

    @Test
    fun rollbackIsAvailableOnlyAfterAnInstalledRevisionExists() {
        var restored: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "rollback-demo", "具备已保存版本", true,
                        rollbackAvailable = true,
                    )),
                    skillsOnly = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onRollbackSkill = { restored = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:rollback-demo").performClick()
        compose.onNodeWithTag("skill-rollback").assertExists().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, restored) }
        compose.onNodeWithTag("skill-rollback-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("rollback-demo", restored) }
    }
    @Test
    fun officialCodexPluginExposesRealSkillsAndOAuthAction() {
        var selectedSkill: Pair<String, String>? = null
        var connectedPlugin: String? = null
        val pluginId = "figma@openai-curated"
        val plugin = com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot(
            pluginId, "figma", "Creativity", "plugins/figma",
            "AVAILABLE", "ON_INSTALL", false,
        )
        val detail = com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot(
            pluginId, "Figma", "设计协作", "Figma", "2.0",
            listOf("figma-use"), true, true, false,
            "figma", "https://mcp.figma.com/mcp",
        )
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), codexPlugins = listOf(plugin),
                    codexDetails = mapOf(pluginId to detail),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onInstallCodexPluginSkill = { id, skill ->
                        selectedSkill = id to skill
                    },
                    onConnectCodexPlugin = { connectedPlugin = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-codex-plugin:$pluginId")
            .performScrollTo().performClick()
        compose.onNodeWithText("包含技能 · 1 项").assertIsDisplayed()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle {
            assertEquals(pluginId to "figma-use", selectedSkill)
        }
        // Installation keeps the detail destination alive for a connected next step.
        compose.onNodeWithText("授权并连接 figma").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(pluginId, connectedPlugin)
        }
    }

    @Test
    fun installedPluginSkillRecognizesPinnedOriginWhenFolderAndSkillNameDiffer() {
        var installRequests = 0
        val pluginId = "zoom@openai-curated"
        val plugin = com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot(
            pluginId, "zoom", "Productivity", "plugins/zoom",
            "AVAILABLE", "ON_INSTALL", false,
        )
        val detail = com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot(
            pluginId, "Zoom", "会议", "Zoom", "1.0",
            listOf("general"), false, false, false,
            skillDirectories = mapOf("general" to "skills/general"),
        )
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), codexPlugins = listOf(plugin),
                    codexDetails = mapOf(pluginId to detail),
                    skills = listOf(com.labteto.dshmobile.local.presentation.LocalSkillUiEntry(
                        "zoom-general", "Use Zoom", true,
                        sourceUrl = "https://github.com/openai/plugins/tree/0123456789012345678901234567890123456789/plugins/zoom/skills/general",
                    )),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onInstallCodexPluginSkill = { _, _ -> installRequests++ },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-codex-plugin:$pluginId")
            .performScrollTo().performClick()
        compose.onNodeWithTag("codex-plugin-skills-summary").performClick()
        compose.onNodeWithTag("codex-skill-install-$pluginId-general")
            .performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, installRequests) }
    }

    @Test
    fun skillDependenciesStayCollapsedUntilDetailAndOAuthUsesPredefinedEndpoint() {
        var authorized: Pair<String, String>? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    skills = listOf(com.labteto.dshmobile.uicontract.UiSkillSnapshot(
                        name = "figma-use", description = "编辑 Figma 设计", modelInvocable = true,
                        requirements = listOf(com.labteto.dshmobile.uicontract.UiSkillRequirement(
                            type = "mcp", value = "figma", description = "Figma 设计服务",
                            registered = false, transport = "http",
                            url = "https://mcp.figma.com/mcp",
                        )),
                    )),
                    skillsOnly = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onConnectSkillMcp = { name, url -> authorized = name to url },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:figma-use").assertIsDisplayed()
        compose.onNodeWithText("需要授权 · Figma 设计服务").assertDoesNotExist()
        compose.onNodeWithTag("plugin-inventory-card-skill:figma-use").performClick()
        compose.onNodeWithTag("skill-dependency-summary").assertIsDisplayed()
        compose.onNodeWithText("需要授权 · Figma 设计服务").assertDoesNotExist()
        compose.onNodeWithTag("skill-dependency-summary").performClick()
        compose.onNodeWithText("需要授权 · Figma 设计服务").assertIsDisplayed()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.runOnIdle {
            assertEquals("figma" to "https://mcp.figma.com/mcp", authorized)
        }
    }

    @Test
    fun codexPluginWithMultipleSkillsInstallsAsOneBundleFromDetail() {
        var installedPlugin: String? = null
        val pluginId = "sample@openai-curated"
        val plugin = com.labteto.dshmobile.uicontract.UiCodexPluginSnapshot(
            pluginId, "sample", "Productivity", "plugins/sample",
            "AVAILABLE", "ON_INSTALL", false,
        )
        val detail = com.labteto.dshmobile.uicontract.UiCodexPluginDetailSnapshot(
            pluginId, "Sample", "多技能示例插件", "Official", "1.0",
            listOf("research", "summarize"), false, false, false,
            skillDirectories = mapOf(
                "research" to "skills/research",
                "summarize" to "skills/summarize",
            ),
        )
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(), codexPlugins = listOf(plugin),
                    codexDetails = mapOf(pluginId to detail),
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onInstallCodexPlugin = { installedPlugin = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-codex-plugin:$pluginId")
            .performScrollTo().performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").assertIsDisplayed()
        compose.onNodeWithText("安装包含的 2 项技能").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(pluginId, installedPlugin) }
    }

    @Test
    fun installingAPresetKeepsDetailOpenAndReprojectsLiveInstalledState() {
        val installed = androidx.compose.runtime.mutableStateOf(false)
        var usePrompt: String? = null
        compose.setContent {
            DshTheme {
                PluginInventoryBrowser(
                    localIds = emptyList(),
                    presets = listOf(com.labteto.dshmobile.uicontract.UiPresetSkillSnapshot(
                        "research-check", "资料研究与核实", "核查来源", installed.value,
                    )),
                    skills = if (installed.value) listOf(
                        com.labteto.dshmobile.uicontract.UiSkillSnapshot(
                            "research-check", "核查来源", true,
                        )) else emptyList(),
                    skillsOnly = true,
                    onBack = {}, onManageConnections = {}, onReturnToChat = {},
                    onInstallPreset = { installed.value = true },
                    onUseCapability = { usePrompt = it },
                )
            }
        }
        compose.onNodeWithTag("plugin-inventory-card-skill:research-check").performClick()
        compose.onNodeWithTag("plugin-detail-primary-action").performClick()
        compose.onNodeWithText("已安装").assertIsDisplayed()
        compose.onNodeWithTag("plugin-detail-primary-action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("@skill:research-check\\n", usePrompt) }
    }

}
