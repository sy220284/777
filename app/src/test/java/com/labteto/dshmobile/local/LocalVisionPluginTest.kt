package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionPluginTest {
    @Test
    fun mainScreenAnalysisRequiresApprovalBeforeCapturingPixels() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(plugin(device, analyzer))

        val denied = registry.context.tools.execute(
            name = "vision_analyze_screen",
            input = buildJsonObject { put("prompt", "找按钮") },
        )

        assertTrue(denied.isError)
        assertTrue(device.calls.isEmpty())
        assertTrue(analyzer.calls.isEmpty())
    }

    @Test
    fun approvedMainScreenAnalysisSendsScreenshotOnce() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(plugin(device, analyzer))

        val result = registry.context.tools.execute(
            name = "vision_analyze_screen",
            input = buildJsonObject { put("prompt", "找登录按钮") },
            context = ToolContext(approval = { true }),
        )

        assertFalse(result.isError)
        assertEquals("视觉结果", result.content)
        assertEquals(listOf("android_screenshot" to emptyMap()), device.calls)
        assertEquals(1, analyzer.calls.size)
        assertTrue(analyzer.calls.single().prompt.contains("找登录按钮"))
        assertEquals("data:image/png;base64,AAAA", analyzer.calls.single().image)
    }

    @Test
    fun virtualScreenAnalysisUsesRequestedDisplayWithoutSecondApproval() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(plugin(device, analyzer))

        val result = registry.context.tools.execute(
            name = "vision_analyze_vscreen",
            input = buildJsonObject {
                put("id", "display-1")
                put("prompt", "识别下一步")
            },
        )

        assertFalse(result.isError)
        assertEquals(
            listOf("vscreen_screenshot" to mapOf("id" to "display-1")),
            device.calls,
        )
        assertEquals(1, analyzer.calls.size)
    }

    @Test
    fun missingConfigurationFailsBeforeDeviceCapture() = runTest {
        val device = RecordingDevice()
        val registry = PluginRegistry()
        registry.install(
            LocalVisionPlugin(
                device = device,
                keyProvider = { null },
                routeProvider = { null },
                analyzer = RecordingAnalyzer(),
            ),
        )

        val result = registry.context.tools.execute(
            name = "vision_analyze_vscreen",
            input = buildJsonObject {
                put("id", "display-1")
                put("prompt", "分析")
            },
        )

        assertTrue(result.isError)
        assertTrue(device.calls.isEmpty())
    }

    private fun plugin(
        device: HarnessDeviceProvider,
        analyzer: LocalVisionAnalyzer,
    ) = LocalVisionPlugin(
        device = device,
        keyProvider = { "secret" },
        routeProvider = { LocalVisionRoute("https://vision.example/v1", "vision-model") },
        analyzer = analyzer,
    )

    private class RecordingDevice : HarnessDeviceProvider {
        override val capabilities: Set<String> = setOf("android_screenshot", "vscreen_screenshot")
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun invoke(
            capability: String,
            arguments: Map<String, String>,
        ): String {
            calls += capability to arguments
            return "data:image/png;base64,AAAA"
        }
    }

    private class RecordingAnalyzer : LocalVisionAnalyzer {
        data class Call(
            val key: String,
            val route: LocalVisionRoute,
            val prompt: String,
            val image: String,
        )

        val calls = mutableListOf<Call>()

        override suspend fun analyze(
            apiKey: String,
            route: LocalVisionRoute,
            prompt: String,
            imageDataUrl: String,
        ): String {
            calls += Call(apiKey, route, prompt, imageDataUrl)
            return "视觉结果"
        }
    }
}
