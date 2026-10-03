package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRouterTest {
    @Test
    fun optionalToolsStayHiddenUntilCapabilitySearchEnablesThem() {
        val core = tool("read", "读取文件", ToolExposure.CORE, "文件")
        val android = tool(
            "device_action", "按屏幕坐标点击", ToolExposure.OPTIONAL, "Android",
            setOf("安卓", "界面"),
        )
        val vision = tool(
            "screen_analysis", "分析当前屏幕", ToolExposure.OPTIONAL, "视觉",
            setOf("视觉", "图片"),
        )
        val all = listOf(core, android, vision)

        assertEquals(listOf("read"), names(LocalToolRouter.visibleSchemas(all, emptySet())))

        val matches = LocalToolRouter.search(all, "安卓 界面")
        assertEquals(listOf("device_action"), matches.map(HarnessTool::name))
        val enabled = matches.map(HarnessTool::name).toSet()
        assertTrue("device_action" in names(LocalToolRouter.visibleSchemas(all, enabled)))
        assertTrue("screen_analysis" !in names(LocalToolRouter.visibleSchemas(all, enabled)))
    }

    @Test
    fun workspaceImageAnalyzerCanBeCoreWithoutDependingOnItsName() {
        val read = tool("read", "读取文件", ToolExposure.CORE, "文件")
        val image = tool("workspace_pixels", "分析工作区图片", ToolExposure.CORE, "视觉")
        val screen = tool(
            "screen_analysis", "分析当前屏幕", ToolExposure.OPTIONAL, "视觉",
            setOf("视觉", "屏幕"),
        )

        val visible = names(LocalToolRouter.visibleSchemas(listOf(read, image, screen), emptySet()))

        assertEquals(listOf("read", "workspace_pixels"), visible)
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
    fun deferredBuiltInsCutBaseSchemaCostWhileKeepingCoreExecutionSurface() {
        val tools = LocalToolCatalog.specs.map { schemaElement ->
            val schema = schemaElement.jsonObject
            val name = schema["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            HarnessTool(
                name = name,
                schema = schema,
                access = LocalToolPolicy.access(name),
                approvalPolicy = LocalToolPolicy.approval(name),
                exposure = LocalToolPolicy.exposure(name),
                metadata = LocalToolPolicy.metadata(name),
                executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
            )
        }
        val fullTokens = tools.sumOf { tool -> estimateModelTokens(tool.schema.toString()) }
        val coreSchemas = LocalToolRouter.visibleSchemas(tools, emptySet())
        val coreTokens = coreSchemas.sumOf { schema -> estimateModelTokens(schema.toString()) }
        val coreNames = names(coreSchemas)

        assertTrue(coreTokens * 100 <= fullTokens * 55)
        assertTrue("read" in coreNames)
        assertTrue("bash" in coreNames)
        assertTrue("subagent" in coreNames)
        assertTrue("session_event_search" in coreNames)
        assertTrue("workflow" !in coreNames)
        assertTrue("environment_info" !in coreNames)
        assertTrue("memory_search" !in coreNames)
        assertTrue("web_search" !in coreNames)
        assertTrue("memory_update" !in coreNames)
        assertEquals(LocalToolCatalog.specs.size, tools.size)
    }

    @Test
    fun optionalToolActivationPreservesStableCoreAndOnlyAppends() {
        val tools = listOf(
            tool("z_core", "核心二", ToolExposure.CORE, "文件"),
            tool("a_core", "核心一", ToolExposure.CORE, "文件"),
            tool("repo_status", "仓库状态", ToolExposure.OPTIONAL, "GitHub", setOf("仓库")),
            tool("external_extra", "外部扩展", ToolExposure.OPTIONAL, "MCP", setOf("外部工具")),
            tool("internal_only", "内部工具", ToolExposure.INTERNAL, "内部"),
        )
        val enabled = linkedSetOf("repo_status")
        val first = names(LocalToolRouter.visibleSchemas(tools, enabled))

        enabled += "external_extra"
        val second = names(LocalToolRouter.visibleSchemas(tools.reversed(), enabled))

        assertEquals(listOf("a_core", "z_core", "repo_status"), first)
        assertEquals(first, second.take(first.size))
        assertEquals("external_extra", second.last())
        assertTrue("internal_only" !in second)
    }

    @Test
    fun capabilitySearchHardCapsLargeOptionalCatalogDeterministically() {
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

        assertEquals(48, first.size)
        assertEquals(first.map(HarnessTool::name), second.map(HarnessTool::name))
        assertEquals("remote_0000", first.first().name)
    }

    @Test
    fun optionalPromptBudgetNeverDropsCoreAndRejectsOversizedOptionalSchema() {
        val core = tool("read", "读取文件", ToolExposure.CORE, "文件")
        val small = tool("small_remote", "小型远端工具", ToolExposure.OPTIONAL, "MCP")
        val huge = tool(
            "huge_remote",
            "超大远端工具" + "x".repeat(12_000),
            ToolExposure.OPTIONAL,
            "MCP",
        )

        assertEquals(
            listOf("read"),
            names(
                LocalToolRouter.visibleSchemas(
                    tools = listOf(core, small, huge),
                    enabledOptional = linkedSetOf("small_remote", "huge_remote"),
                    maxOptionalDefinitionTokens = 0,
                ),
            ),
        )
        assertEquals(
            listOf("read", "small_remote"),
            names(
                LocalToolRouter.visibleSchemas(
                    tools = listOf(core, small, huge),
                    enabledOptional = linkedSetOf("small_remote", "huge_remote"),
                    maxOptionalDefinitionTokens = LocalToolRouter.DEFAULT_OPTIONAL_TOOL_PROMPT_TOKENS,
                ),
            ),
        )
    }

    @Test
    fun staleEnabledNameCannotResurrectAnUnregisteredTool() {
        val core = tool("read", "读取文件", ToolExposure.CORE, "文件")
        val removed = tool("removed", "已卸载工具", ToolExposure.OPTIONAL, "MCP", setOf("MCP"))
        val enabled = setOf("removed")

        assertTrue("removed" in names(LocalToolRouter.visibleSchemas(listOf(core, removed), enabled)))
        assertEquals(listOf("read"), names(LocalToolRouter.visibleSchemas(listOf(core), enabled)))
    }

    @Test
    fun capabilitySearchConsumesOnlyBoundedQueryTerms() {
        val target = tool(
            "target", "target capability", ToolExposure.OPTIONAL, "MCP", setOf("mcp"),
        )
        val hugeQuery = buildString {
            repeat(10_000) { append("noise").append(it).append(' ') }
            append("mcp")
        }

        assertTrue(LocalToolRouter.search(listOf(target), hugeQuery).isEmpty())
    }

    @Test
    fun capabilitySummaryComesFromRegisteredMetadataAndEnabledState() {
        val tools = listOf(
            tool(
                "device_action", "设备操作", ToolExposure.OPTIONAL, "Android",
                setOf("安卓"), listOf("需要无障碍授权"),
            ),
            tool("repo_reader", "仓库读取", ToolExposure.OPTIONAL, "GitHub", setOf("仓库")),
        )

        val summary = LocalToolRouter.capabilitySummary(tools, setOf("repo_reader"))

        assertTrue(summary.contains("Android：未启用 0/1"))
        assertTrue(summary.contains("需要无障碍授权"))
        assertTrue(summary.contains("GitHub：已启用 1/1"))
    }

    @Test
    fun capabilitySummaryBoundsRepeatedFamilyRequirements() {
        val tools = (0 until 8).map { index ->
            tool(
                "remote_$index",
                "远端工具 $index",
                ToolExposure.OPTIONAL,
                "MCP",
                setOf("mcp"),
                listOf("连接条件-$index"),
            )
        }

        val summary = LocalToolRouter.capabilitySummary(tools, emptySet())

        assertTrue(summary.contains("连接条件-0"))
        assertTrue(summary.contains("连接条件-3"))
        assertTrue(summary.contains("另有 4 项"))
        assertTrue(!summary.contains("连接条件-7"))
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
