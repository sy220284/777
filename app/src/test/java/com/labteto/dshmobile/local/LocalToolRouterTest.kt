package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRouterTest {
    @Test
    fun optionalToolsStayHiddenUntilCapabilitySearchEnablesThem() {
        val core = tool("read", "读取文件")
        val android = tool("android_tap", "按屏幕坐标点击")
        val vision = tool("vision_analyze_screen", "分析当前屏幕")
        val all = listOf(core, android, vision)

        val initial = LocalToolRouter.visibleSchemas(all, emptySet())
        assertEquals(listOf("read"), names(initial))

        val matches = LocalToolRouter.search(all, "安卓 界面")
        assertTrue(matches.any { it.name == "android_tap" })
        val enabled = matches.map(HarnessTool::name).toSet()
        assertTrue("android_tap" in names(LocalToolRouter.visibleSchemas(all, enabled)))
        assertTrue("vision_analyze_screen" !in names(LocalToolRouter.visibleSchemas(all, enabled)))
    }

    @Test
    fun workspaceImageAnalyzerIsAlwaysVisibleForAutomaticFallback() {
        val read = tool("read", "读取文件")
        val image = tool("vision_analyze_file", "分析工作区图片")
        val screen = tool("vision_analyze_screen", "分析当前屏幕")

        val visible = names(LocalToolRouter.visibleSchemas(listOf(read, image, screen), emptySet()))

        assertTrue("read" in visible)
        assertTrue("vision_analyze_file" in visible)
        assertTrue("vision_analyze_screen" !in visible)
    }

    @Test
    fun familyKeywordsDiscoverRuntimeAndVisionTools() {
        val tools = listOf(
            tool("process_exec", "直接执行本机进程"),
            tool("vision_status", "查看视觉配置"),
            tool("github_api_get", "读取 GitHub REST API"),
            tool("read", "读取文件"),
        )

        assertEquals("process_exec", LocalToolRouter.search(tools, "终端 运行时").first().name)
        assertEquals("vision_status", LocalToolRouter.search(tools, "视觉 图片").first().name)
        assertEquals("github_api_get", LocalToolRouter.search(tools, "GitHub PR").first().name)
    }


    @Test
    fun optionalToolActivationPreservesExistingPrefixAndOnlyAppends() {
        val tools = listOf(
            tool("read", "读取文件"),
            tool("github_status", "GitHub 状态"),
            tool("github_api_get", "读取 GitHub"),
            tool("mcp_extra", "MCP 扩展"),
        )
        val enabled = linkedSetOf("github_status")
        val first = names(LocalToolRouter.visibleSchemas(tools, enabled))

        enabled += "mcp_extra"
        val second = names(LocalToolRouter.visibleSchemas(tools.reversed(), enabled))

        assertEquals(first, second.take(first.size))
        assertEquals("mcp_extra", second.last())

        enabled += "github_api_get"
        val third = names(LocalToolRouter.visibleSchemas(tools, enabled))
        assertEquals(second, third.take(second.size))
        assertEquals("github_api_get", third.last())
    }

    @Test
    fun capabilitySearchHardCapsLargeOptionalCatalogDeterministically() {
        val tools = (0 until 1_000).map { index ->
            tool("mcp_tool_" + index.toString().padStart(4, '0'), "MCP 外部工具 批量测试")
        }

        val first = LocalToolRouter.search(tools, "MCP 外部工具", limit = Int.MAX_VALUE)
        val second = LocalToolRouter.search(tools.reversed(), "MCP 外部工具", limit = Int.MAX_VALUE)

        assertEquals(48, first.size)
        assertEquals(first.map(HarnessTool::name), second.map(HarnessTool::name))
        assertEquals("mcp_tool_0000", first.first().name)
    }

    @Test
    fun staleEnabledNameCannotResurrectAnUnregisteredTool() {
        val core = tool("read", "读取文件")
        val removed = tool("mcp_removed", "MCP 已卸载工具")
        val enabled = setOf("mcp_removed")

        assertTrue("mcp_removed" in names(LocalToolRouter.visibleSchemas(listOf(core, removed), enabled)))
        val afterUninstall = names(LocalToolRouter.visibleSchemas(listOf(core), enabled))

        assertEquals(listOf("read"), afterUninstall)
    }

    @Test
    fun capabilitySearchConsumesOnlyBoundedQueryTerms() {
        val tool = tool("mcp_target", "MCP target capability")
        val hugeQuery = buildString {
            repeat(10_000) { append("noise").append(it).append(' ') }
            append("mcp")
        }

        // The router intentionally considers only the first 24 tokens; a huge prompt cannot
        // force unbounded matching work or unexpectedly enable a trailing capability.
        assertTrue(LocalToolRouter.search(listOf(tool), hugeQuery).isEmpty())
    }

    private fun tool(name: String, description: String): HarnessTool = HarnessTool(
        name = name,
        schema = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", JsonObject(emptyMap()))
            })
        },
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    private fun names(array: kotlinx.serialization.json.JsonArray): List<String> =
        array.map { element ->
            element.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
}
