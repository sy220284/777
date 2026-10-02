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
