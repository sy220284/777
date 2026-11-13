package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.vision.LocalVisionAnalyzer
import com.labteto.dshmobile.local.vision.LocalVisionPlugin
import com.labteto.dshmobile.local.vision.LocalVisionRoute
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionPluginTest {
    @Test
    fun scopedModelRouteAndCredentialOverrideForegroundAcrossVisionTools() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        val selected = LocalModelProfile("subagent-profile", "subagent-model", "https://subagent.example/v1",
            protocol = LocalModelProtocol.RESPONSES)
        registry.install(LocalVisionPlugin(
            device = device,
            keyProvider = { null },
            routeProvider = { LocalVisionRoute("https://foreground.example/v1", "foreground-model") },
            routeKeyProvider = { route -> if (route.profile == selected) "subagent-secret" else null },
            analyzer = analyzer,
        ))
        listOf("vision_analyze_screen", "vision_analyze_vscreen").forEach { name ->
            val result = registry.context.tools.execute(
                name = name,
                input = buildJsonObject { put("prompt", "find button"); if (name.endsWith("vscreen")) put("id", "screen-1") },
                context = ToolContext(attributes = mapOf("model_profile" to selected), approval = { true }),
            )
            assertFalse(result.content, result.isError)
        }
        assertEquals(2, analyzer.calls.size)
        analyzer.calls.forEach { call ->
            assertEquals("subagent-secret", call.key)
            assertEquals(selected, call.route.profile)
            assertEquals(selected.model, call.route.model)
            assertEquals(selected.baseUrl, call.route.baseUrl)
        }
    }

    @Test
    fun fallbackRouteKeepsExactProfileIdentityForCredentialResolution() = runTest {
        val selected = LocalModelProfile(
            id = "account-b",
            model = "same-model",
            baseUrl = "https://same.example/v1",
            protocol = LocalModelProtocol.RESPONSES,
        )
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(
            LocalVisionPlugin(
                device = RecordingDevice(),
                keyProvider = { null },
                routeProvider = {
                    LocalVisionRoute(selected.baseUrl, selected.model, profile = selected)
                },
                routeKeyProvider = { route ->
                    if (route.profile?.id == selected.id) "account-b-key" else null
                },
                analyzer = analyzer,
            ),
        )

        val result = registry.context.tools.execute(
            name = "vision_analyze_screen",
            input = buildJsonObject { put("prompt", "识别按钮") },
            context = ToolContext(approval = { true }),
        )

        assertFalse(result.content, result.isError)
        assertEquals("account-b-key", analyzer.calls.single().key)
        assertEquals(selected.id, analyzer.calls.single().route.profile?.id)
    }

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
        assertEquals(listOf("android_screenshot" to emptyMap<String, String>()), device.calls)
        assertEquals(1, analyzer.calls.size)
        assertTrue(analyzer.calls.single().prompt.contains("找登录按钮"))
        assertEquals("data:image/png;base64,AAAA", analyzer.calls.single().image)
    }

    @Test
    fun virtualScreenAnalysisUsesRequestedDisplayAfterFreshApproval() = runTest {
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
            context = ToolContext(approval = { true }),
        )

        assertFalse(result.isError)
        assertEquals(
            listOf("vscreen_screenshot" to mapOf("id" to "display-1")),
            device.calls,
        )
        assertEquals(1, analyzer.calls.size)
    }

    @Test
    fun workspaceImageAnalysisRequiresApprovalAndStaysInsideWorkspace() = runTest {
        val root = Files.createTempDirectory("vision-workspace").toFile()
        try {
            root.resolve("shot.png").writeBytes(validPngBytes())
            val device = RecordingDevice()
            val analyzer = RecordingAnalyzer()
            val registry = PluginRegistry()
            registry.install(
                LocalVisionPlugin(
                    device = device,
                    keyProvider = { "secret" },
                    routeProvider = { LocalVisionRoute("https://vision.example/v1", "vision-model") },
                    analyzer = analyzer,
                    workspaceRoot = root,
                ),
            )

            val denied = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject { put("path", "shot.png"); put("prompt", "分析") },
            )
            assertTrue(denied.isError)
            assertTrue(analyzer.calls.isEmpty())

            val result = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject { put("path", "shot.png"); put("prompt", "分析") },
                context = ToolContext(approval = { true }),
            )
            assertFalse(result.isError)
            assertTrue(analyzer.calls.single().image.startsWith("data:image/png;base64,"))

            val escaped = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject { put("path", "../escape.png"); put("prompt", "分析") },
                context = ToolContext(approval = { true }),
            )
            assertTrue(escaped.isError)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workspaceImageAnalysisReusesExactCacheAndRefreshBypassesIt() = runTest {
        val root = Files.createTempDirectory("vision-cache-workspace").toFile()
        try {
            root.resolve("shot.png").writeBytes(validPngBytes())
            val analyzer = RecordingAnalyzer()
            val registry = PluginRegistry()
            registry.install(
                LocalVisionPlugin(
                    device = RecordingDevice(),
                    keyProvider = { "secret" },
                    routeProvider = { LocalVisionRoute("https://vision.example/v1", "vision-model") },
                    analyzer = analyzer,
                    workspaceRoot = root,
                ),
            )
            val context = ToolContext(approval = { true })

            val first = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject { put("path", "shot.png"); put("prompt", "分析") },
                context = context,
            )
            val second = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject { put("path", "shot.png"); put("prompt", "分析") },
                context = context,
            )
            val refreshed = registry.context.tools.execute(
                name = "vision_analyze_file",
                input = buildJsonObject {
                    put("path", "shot.png")
                    put("prompt", "分析")
                    put("refresh", true)
                },
                context = context,
            )

            assertFalse(first.isError)
            assertFalse(second.isError)
            assertFalse(refreshed.isError)
            assertEquals(2, analyzer.calls.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun documentedTextOnlyCurrentModelFailsBeforeDeviceCapture() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(
            LocalVisionPlugin(
                device = device,
                keyProvider = { "secret" },
                routeProvider = {
                    LocalVisionRoute(
                        "https://open.bigmodel.cn/api/paas/v4",
                        "glm-5-turbo",
                    )
                },
                analyzer = analyzer,
            ),
        )

        val result = registry.context.tools.execute(
            name = "vision_analyze_vscreen",
            input = buildJsonObject {
                put("id", "display-1")
                put("prompt", "分析")
            },
            context = ToolContext(approval = { true }),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("当前模型不支持图片理解"))
        assertTrue(device.calls.isEmpty())
        assertTrue(analyzer.calls.isEmpty())
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
            context = ToolContext(approval = { true }),
        )

        assertTrue(result.isError)
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun workspaceImageMissingConfigurationUsesCurrentModelWording() = runTest {
        val registry = PluginRegistry()
        registry.install(
            LocalVisionPlugin(
                device = RecordingDevice(),
                keyProvider = { null },
                routeProvider = { null },
                analyzer = RecordingAnalyzer(),
            ),
        )

        val result = registry.context.tools.execute(
            name = "vision_analyze_file",
            input = buildJsonObject {
                put("path", "shot.png")
                put("prompt", "分析")
            },
            context = ToolContext(approval = { true }),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("当前模型尚未配置"))
        assertFalse(result.content.contains("视觉模型"))
    }

    @Test
    fun virtualScreenCannotUploadWithoutApproval() = runTest {
        val device = RecordingDevice()
        val analyzer = RecordingAnalyzer()
        val registry = PluginRegistry()
        registry.install(plugin(device, analyzer))
        val denied = registry.context.tools.execute("vision_analyze_vscreen", buildJsonObject {
            put("id", "display-1")
            put("prompt", "分析")
        })
        assertTrue(denied.isError)
        assertTrue(device.calls.isEmpty())
        assertTrue(analyzer.calls.isEmpty())
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

    private fun validPngBytes(): ByteArray =
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        ) + ByteArray(32) { 1 }

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
