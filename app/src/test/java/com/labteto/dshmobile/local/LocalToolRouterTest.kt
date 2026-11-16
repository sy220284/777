package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.LocalToolRouter
import com.labteto.dshmobile.local.model.estimateModelTokens
import com.labteto.dshmobile.local.tools.LocalCapabilityDiscovery
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRouterTest {
    @Test
    fun liveCatalogueSearchReturnsOnlySelectedSchemasAndRejectsInvalidPageSize() {
        val tools = listOf(
            tool("read", "读取文件", ToolExposure.CORE, "文件"),
            tool("media_create", "生成媒体", ToolExposure.OPTIONAL, "媒体", setOf("生成媒体")),
            tool("web_search", "网页搜索", ToolExposure.OPTIONAL, "网络", setOf("网页搜索")),
        )
        val selected = LocalCapabilityDiscovery.searchCatalogue(tools, "select:read,web_search", 5)
        assertEquals(listOf("read", "web_search"), selected.map { it.name })
        assertEquals(listOf("media_create", "read", "web_search"),
            LocalCapabilityDiscovery.searchCatalogue(tools, "全部工具", 10).map { it.name })
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            LocalCapabilityDiscovery.searchCatalogue(tools, "网页搜索", 0)
        }
    }

    @Test
    fun exactCapabilitySelectionSupportsLargeBatchesWithoutTruncation() {
        val selected = (1..180).map { "mcp_demo_tool$it" }
        assertEquals(selected, LocalToolRouter.exactSelection("select:" + selected.joinToString(",")))
        assertEquals(selected, LocalToolRouter.exactSelection(
            "select:" + (selected + selected.first()).joinToString(",")
        ))
    }

    @Test
    fun metadataKeywordsDiscoverToolsIndependentOfPrefixes() {
        val tools = listOf(
            tool("exec_native", "直接执行本机进程", ToolExposure.OPTIONAL, "运行时", setOf("终端", "运行时")),
            tool("image_capability", "查看视觉配置", ToolExposure.OPTIONAL, "视觉", setOf("视觉", "图片")),
            tool("repo_reader", "读取 REST API", ToolExposure.OPTIONAL, "GitHub", setOf("GitHub", "PR")),
            tool("read", "读取文件", ToolExposure.CORE, "文件"),
        )

        assertEquals("exec_native", LocalToolRouter.search(tools, "终端 运行时").first().name)
        assertEquals("image_capability", LocalToolRouter.search(tools, "视觉 图片").first().name)
        assertEquals("repo_reader", LocalToolRouter.search(tools, "GitHub PR").first().name)
    }

    @Test
    fun taskIntentPreactivatesOnlyExplicitlyRelevantOptionalCapabilities() {
        val tools = listOf(
            tool("read", "读取文件", ToolExposure.CORE, "文件"),
            tool("web_search", "搜索网页", ToolExposure.OPTIONAL, "网络", setOf("联网", "网页搜索")),
            tool("memory_update", "更新记忆", ToolExposure.OPTIONAL, "记忆", setOf("更新记忆", "memory")),
            tool("session_trace", "会话轨迹", ToolExposure.OPTIONAL, "会话", setOf("会话轨迹", "trace")),
        )

        assertEquals(
            listOf("web_search"),
            LocalToolRouter.relevantOptionalToolNames(tools, "联网查一下今天的发布说明"),
        )
        assertEquals(
            listOf("memory_update"),
            LocalToolRouter.relevantOptionalToolNames(tools, "把这条更新记忆修正一下"),
        )
        assertTrue(LocalToolRouter.relevantOptionalToolNames(tools, "继续处理当前代码").isEmpty())
    }

    @Test
    fun shortEnglishKeywordsDoNotActivateUnrelatedOptionalTools() {
        val tools = listOf(
            tool("api_client", "请求 API", ToolExposure.OPTIONAL, "网络", setOf("api")),
            tool("preview_pr", "读取合并请求", ToolExposure.OPTIONAL, "GitHub", setOf("pr")),
            tool("web_lookup", "网页搜索", ToolExposure.OPTIONAL, "网络", setOf("web")),
        )

        // The old substring matcher matched api in capital, pr in preview, web in website.
        assertTrue(LocalToolRouter.relevantOptionalToolNames(
            tools, "Review capital allocations and preview the website",
        ).isEmpty())
        assertEquals(
            listOf("api_client", "preview_pr", "web_lookup"),
            LocalToolRouter.relevantOptionalToolNames(tools, "调用 API 查询 PR，再使用 web"),
        )
    }

    @Test
    fun categoryAloneDoesNotExpandEveryAdvancedToolButExplicitDiscoveryStillWorks() {
        val tools = listOf(
            tool("team_spawn", "创建团队", ToolExposure.OPTIONAL, "智能体", setOf("创建团队")),
            tool("team_stop", "停止团队", ToolExposure.OPTIONAL, "智能体", setOf("停止团队")),
            tool("memory_update", "更新记忆", ToolExposure.OPTIONAL, "记忆", setOf("更新记忆")),
        )
        assertTrue(LocalToolRouter.relevantOptionalToolNames(
            tools, "介绍智能体和记忆功能",
        ).isEmpty())
        assertEquals(
            listOf("team_spawn"),
            LocalToolRouter.relevantOptionalToolNames(tools, "请创建团队"),
        )
        assertEquals(
            listOf("team_spawn", "team_stop"),
            LocalToolRouter.search(tools, "智能体").map(HarnessTool::name),
        )
    }

    @Test
    fun toolIdentifiersAndMixedChineseEnglishIntentStillPreactivate() {
        val tools = listOf(
            tool("github_lookup", "查看 GitHub", ToolExposure.OPTIONAL, "外部", setOf("github")),
            tool("http_request", "HTTP 接口", ToolExposure.OPTIONAL, "网络", setOf("api")),
        )
        assertEquals(
            listOf("http_request", "github_lookup"),
            LocalToolRouter.relevantOptionalToolNames(tools, "请用GitHub项目里的 http_request查询"),
        )
        assertTrue(LocalToolRouter.relevantOptionalToolNames(
            tools, "请检查 github_lookup_result 的日志",
        ).isEmpty())
    }

    @Test
    fun longTaskIntentRecognizesExplicitToolsInTheMiddleWithoutSilentSlicing() {
        val tools = listOf(
            tool("middle_tool", "中间任务工具", ToolExposure.OPTIONAL, "测试", setOf("middle-keyword")),
            tool("unrelated_tool", "无关任务工具", ToolExposure.OPTIONAL, "测试", setOf("other-keyword")),
        )
        val context = "开始说明 " + "无关文字".repeat(3_000) +
            " 请使用 middle-keyword 完成这一项 " +
            "继续说明".repeat(3_000) + " 结束"
        assertEquals(listOf("middle_tool"), LocalToolRouter.relevantOptionalToolNames(tools, context))
    }

    @Test
    fun longTaskContextPreservesExplicitToolIntentAtBothEnds() {
        val tools = listOf(
            tool("first_tool", "首部任务", ToolExposure.OPTIONAL, "测试", setOf("first-keyword")),
            tool("last_tool", "尾部任务", ToolExposure.OPTIONAL, "测试", setOf("last-keyword")),
        )
        val context = "first-keyword " + "无关正文".repeat(5_000) + " last-keyword"
        assertEquals(
            listOf("first_tool", "last_tool"),
            LocalToolRouter.relevantOptionalToolNames(tools, context),
        )
    }

    @Test
    fun fixedGatewayAvoidsPublishingBuiltinSchemasToModel() {
        val registry = com.labteto.dshmobile.harness.tools.ToolRegistry()
        for (name in listOf("tool_search", "tool_call", "read", "bash")) {
            val schema = LocalToolCatalog.specs.first {
                it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == name
            }.jsonObject
            registry.register(HarnessTool(
                name = name,
                schema = schema,
                access = LocalToolPolicy.access(name),
                approvalPolicy = LocalToolPolicy.approval(name),
                exposure = LocalToolPolicy.exposure(name),
                metadata = LocalToolPolicy.metadata(name),
                executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
            ))
        }
        val projection = com.labteto.dshmobile.local.tools.LocalToolSchemaProjection(
            registry,
            com.labteto.dshmobile.local.LocalToolExecutionCoordinator(
                registry, currentSessionId = { "test" }, planMode = { false },
                requestApproval = { _, _, _ -> true },
            )
        )
        val modelSchemas = projection.modelSchemas(
            com.labteto.dshmobile.local.agent.localAgentRunPolicy(
                com.labteto.dshmobile.local.LocalUsageMode.WORK),
        )
        assertEquals(listOf("tool_search", "tool_call"), projection.names(modelSchemas))
        val fullCost = LocalToolCatalog.specs.sumOf {
            estimateModelTokens(it.toString())
        }
        val fixedCost = modelSchemas.sumOf { estimateModelTokens(it.toString()) }
        assertTrue(fixedCost * 100 < fullCost * 10)
    }

    @Test
    fun explicitToolNameAlwaysOutranksMultipleBroadDescriptionMatches() {
        val tools = listOf(
            tool("github_pr", "处理项目 PR", ToolExposure.OPTIONAL, "GitHub", setOf("github", "pr")),
            tool("http_request", "PR 分支 Github 项目 API PR Github 分支", ToolExposure.OPTIONAL, "GitHub", setOf("接口请求")),
            tool("github_pr_review", "查看 PR 的 reviewer", ToolExposure.OPTIONAL, "GitHub", setOf("review")),
        )
        assertEquals("github_pr", LocalToolRouter.search(tools, "github_pr Github PR 项目").first().name)
    }

    @Test
    fun descriptionMatchingCannotSuppressExplicitKeyword() {
        val tools = listOf(
            tool("task_action", "查找文档", ToolExposure.OPTIONAL, "执行", setOf("查找定义")),
            tool("generic_reference", "查找文档和定义，还能查找相关说明", ToolExposure.OPTIONAL, "文档"),
        )
        assertEquals("task_action", LocalToolRouter.search(tools, "查找定义").first().name)
    }

    @Test
    fun capabilitySearchDoesNotHardCapLargeOptionalCatalog() {
        val tools = (0 until 1_000).map { index ->
            tool(
                "remote_" + index.toString().padStart(4, '0'),
                "外部工具批量测试",
                ToolExposure.OPTIONAL,
                "MCP",
                setOf("MCP", "外部工具"),
            )
        }

        val first = LocalToolRouter.search(tools, "MCP 外部工具", limit = Int.MAX_VALUE)
        val second = LocalToolRouter.search(tools.reversed(), "MCP 外部工具", limit = Int.MAX_VALUE)

        assertEquals(1_000, first.size)
        assertEquals(first.map(HarnessTool::name), second.map(HarnessTool::name))
        assertEquals("remote_0000", first.first().name)
    }

    @Test
    fun exactIdentifierDoesNotLoadUnrelatedToolsSharingArbitraryNameSegments() {
        val tools = listOf(
            tool("mcp_middle_lookup", "读取指定仓库", ToolExposure.OPTIONAL,
                "MCP", setOf("专用仓库")),
            tool("unrelated_lookup", "查询其他项目", ToolExposure.OPTIONAL,
                "外部", setOf("其他项目")),
            tool("unrelated_action", "另一执行动作", ToolExposure.OPTIONAL,
                "外部", setOf("执行动作")),
        )
        assertEquals(listOf("mcp_middle_lookup"),
            LocalToolRouter.search(tools, "请用 mcp_middle_lookup 完成当前读取")
                .map(HarnessTool::name))
        assertEquals(listOf("mcp_middle_lookup"),
            LocalToolRouter.search(tools, "先按 mcp_middle_lookup 处理任务")
                .map(HarnessTool::name))
        assertEquals(listOf("mcp_middle_lookup", "unrelated_lookup"),
            LocalToolRouter.search(tools, "lookup").map(HarnessTool::name))
    }

    @Test
    fun capabilityDiscoveryFindsNamesAndKeywordsInsideLongQuery() {
        val tools = listOf(
            tool("middle_tool", "执行远端中间步骤", ToolExposure.OPTIONAL, "远端",
                setOf("middle-keyword", "同步令牌")),
            tool("unrelated_tool", "记录别的东西", ToolExposure.OPTIONAL, "记录",
                setOf("other-keyword")),
        )
        val query = "开头内容 " + "无关文字".repeat(3_000) +
            " 请使用 middle_tool 完成同步令牌操作 " +
            "结尾内容".repeat(3_000)
        assertEquals(listOf("middle_tool"),
            LocalToolRouter.search(tools, query).map(HarnessTool::name))
    }

    @Test
    fun capabilityDiscoveryRecognizesUnspacedChineseAndMixedIdentifiers() {
        val tools = listOf(
            tool("android_click_node", "实际点击手机控件", ToolExposure.OPTIONAL,
                "设备", setOf("点击控件")),
            tool("mcp_middle_lookup", "核对中间项目", ToolExposure.OPTIONAL,
                "MCP", setOf("中间项目")),
        )
        val request = "说明" + "前置说明".repeat(2_000) +
            "请调用mcp_middle_lookup进行处理并点击控件" +
            "后续说明".repeat(2_000)
        assertEquals(listOf("mcp_middle_lookup", "android_click_node"),
            LocalToolRouter.search(tools, request).map(HarnessTool::name))
    }

    @Test
    fun repeatedIrrelevantQueryWordsDoNotDisplaceExactToolMatch() {
        val tools = listOf(
            tool("middle_tool", "准确指定工具", ToolExposure.OPTIONAL, "测试",
                setOf("专用操作")),
            tool("generic_tool", "通用的查找说明", ToolExposure.OPTIONAL, "查找",
                setOf("通用")),
        )
        val query = "查找 ".repeat(4_000) + "middle_tool" + " 查找".repeat(4_000)
        assertEquals("middle_tool", LocalToolRouter.search(tools, query).first().name)
    }

    @Test
    fun capabilitySearchScansWholeQueryWithoutIrrelevantTermAmplification() {
        val target = tool(
            "target", "target capability", ToolExposure.OPTIONAL, "MCP", setOf("mcp"),
        )
        val hugeQuery = buildString {
            repeat(10_000) { append("noise").append(it).append(' ') }
            append("mcp")
        }

        assertEquals(listOf("target"), LocalToolRouter.search(listOf(target), hugeQuery).map(HarnessTool::name))
    }

    private fun tool(
        name: String,
        description: String,
        exposure: ToolExposure,
        family: String,
        keywords: Set<String> = emptySet(),
        requirements: List<String> = emptyList(),
    ): HarnessTool = HarnessTool(
        name = name,
        schema = functionToolSchema(name, description),
        access = ToolAccess.READ_ONLY,
        approvalPolicy = ToolApprovalPolicy.NEVER,
        exposure = exposure,
        metadata = ToolMetadata(
            family = family,
            discoveryKeywords = keywords,
            requirements = requirements,
        ),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    private fun names(array: kotlinx.serialization.json.JsonArray): List<String> =
        array.map { element ->
            element.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
}
