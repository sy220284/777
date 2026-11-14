package com.labteto.dshmobile.device

import com.labteto.dshmobile.harness.capability.HarnessAndroidDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessVirtualDisplayProvider
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolResultRetention
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDevicePluginTest {
    @Test
    fun registersDeviceSurfaceWithExplicitRiskPolicies() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        assertSame(provider, registry.context.capabilities.require("android-device", HarnessDeviceProvider::class))
        assertSame(provider, registry.context.capabilities.require("android-device", HarnessVirtualDisplayProvider::class))
        val names = registry.context.tools.names().toSet()
        assertTrue("android_device_info" in names)
        assertTrue("android_vscreen_create" in names)
        assertTrue("android_vscreen_start" in names)
        assertTrue("android_clipboard_clear" in names)
        assertTrue("android_vscreen_screenshot" in names)
        assertTrue("android_find" in names)
        assertTrue("android_input_text" in names)
        assertTrue("android_click_node" in names)
        assertTrue("android_wait" in names)
        assertTrue("android_notification_status" in names)

        val info = requireNotNull(registry.context.tools.get("android_device_info"))
        assertEquals(ToolAccess.READ_ONLY, info.access)
        assertEquals(ToolApprovalPolicy.MUTATION, info.approvalPolicy)

        for (removed in listOf(
            "android_privilege_status",
            "android_privilege_request",
            "android_app_stop",
            "android_settings_set",
            "android_dumpsys",
        )) {
            assertFalse(removed in names)
        }

        val tap = requireNotNull(registry.context.tools.get("android_tap"))
        assertEquals(ToolAccess.DEVICE, tap.access)
        assertEquals(ToolAccess.DEVICE, registry.context.tools.get("android_clipboard_clear")!!.access)
        assertEquals(ToolApprovalPolicy.MUTATION, tap.approvalPolicy)

        val find = requireNotNull(registry.context.tools.get("android_find"))
        assertEquals(ToolAccess.READ_ONLY, find.access)
        val wait = requireNotNull(registry.context.tools.get("android_wait"))
        assertEquals(ToolAccess.READ_ONLY, wait.access)
        val clickNode = requireNotNull(registry.context.tools.get("android_click_node"))
        assertEquals(ToolAccess.DEVICE, clickNode.access)
        val notificationStatus = requireNotNull(registry.context.tools.get("android_notification_status"))
        assertEquals(ToolAccess.READ_ONLY, notificationStatus.access)
    }

    @Test
    fun exposesCompleteCatalogAndUninstallsWithoutLeakingTools() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val names = registry.context.tools.names()
        assertEquals(34, names.size)
        assertEquals(34, names.toSet().size)
        assertTrue(registry.isInstalled("android-device"))

        assertTrue(registry.uninstall("android-device"))
        assertFalse(registry.isInstalled("android-device"))
        assertTrue(registry.context.tools.names().isEmpty())
        assertTrue(provider.closed)
    }

    @Test
    fun screenshotsAreExplicitlyEphemeralWhileOrdinaryReadsStayDurable() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val info = registry.context.tools.execute("android_device_info", buildJsonObject { })
        val screenshot = registry.context.tools.execute(
            "android_screenshot",
            buildJsonObject { },
            context = ToolContext(approval = { true }),
        )

        assertEquals(ToolResultRetention.DURABLE, info.retention)
        assertEquals(ToolResultRetention.EPHEMERAL, screenshot.retention)
    }

    @Test
    fun readOnlyScopeAllowsInfoButBlocksDeviceMutation() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val info = registry.context.tools.execute(
            name = "android_device_info",
            input = buildJsonObject { },
            context = ToolContext(allowMutation = false),
        )
        val tap = registry.context.tools.execute(
            name = "android_tap",
            input = buildJsonObject {
                put("x", 12)
                put("y", 34)
            },
            context = ToolContext(
                allowMutation = false,
                approval = { true },
            ),
        )

        assertFalse(info.isError)
        assertEquals("ok:device_info", info.content)
        assertTrue(tap.isError)
        assertEquals(listOf("device_info"), provider.calls.map { it.first })
    }

    @Test
    fun virtualScreenOneStepStartRequiresApprovalAndForwardsPackage() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))
        val noApproval = registry.context.tools.execute("android_vscreen_start",
            buildJsonObject { put("package", "com.example.notes") })
        assertTrue(noApproval.isError)
        assertTrue(provider.calls.isEmpty())
        val launched = registry.context.tools.execute("android_vscreen_start",
            buildJsonObject { put("package", "com.example.notes") },
            context = ToolContext(approval = { true }))
        assertFalse(launched.isError)
        assertEquals("vscreen_start", provider.calls.single().first)
        assertEquals("com.example.notes", provider.calls.single().second["package"])
        assertEquals(ToolAccess.DEVICE, registry.context.tools.get("android_vscreen_start")!!.access)
    }

    @Test
    fun sensitiveReadsRequireFreshApprovalBeforeProviderInvocation() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))
        val names = listOf("android_screen", "android_find", "android_wait", "android_screenshot",
            "android_notification_list", "android_clipboard_get", "android_vscreen_screenshot")
        for (name in names) {
            assertTrue(registry.context.tools.execute(name, buildJsonObject {}).isError)
            assertTrue(provider.calls.isEmpty())
            assertEquals(ToolApprovalPolicy.ALWAYS, registry.context.tools.get(name)!!.approvalPolicy)
        }
    }

    private class RecordingProvider(
        private val response: (String) -> String = { "ok:$it" },
    ) : HarnessAndroidDeviceProvider {
        override val capabilities: Set<String> = emptySet()
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var closed: Boolean = false

        override suspend fun invoke(
            capability: String,
            arguments: Map<String, String>,
        ): String {
            calls += capability to arguments
            return response(capability)
        }

        override suspend fun acquireAgentVirtualDisplay(owner: String): String = "virtual:$owner"

        override fun releaseAgentVirtualDisplay(id: String) = Unit

        override fun close() {
            closed = true
        }
    }

    @Test
    fun unconfirmedDeviceActionsBecomeFailedToolReceipts() = runTest {
        val provider = RecordingProvider { capability ->
            if (capability == "accessibility_tap" || capability == "accessibility_click_node") "false"
            else "ok:$capability"
        }
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))
        val tap = registry.context.tools.execute(
            "android_tap",
            buildJsonObject { put("x", 8); put("y", 12) },
            context = ToolContext(approval = { true }),
        )
        val click = registry.context.tools.execute(
            "android_click_node",
            buildJsonObject { put("node", 2) },
            context = ToolContext(approval = { true }),
        )
        assertTrue(tap.isError)
        assertTrue(click.isError)
        assertEquals("ANDROID_ACTION_UNCONFIRMED", tap.errorCode)
        assertFalse(tap.retryable)
        assertTrue(tap.recoveryHint.orEmpty().contains("重新检查"))
        assertFalse(androidDeviceToolResult("device_info", "false").isError)
        assertFalse(androidDeviceToolResult("accessibility_tap", "true").isError)
    }

    @Test
    fun emptyAccessibilityTextIsAValidExplicitValueButMissingFieldIsNot() {
        assertEquals("", mapOf("search_text" to "").deviceStringIncludingEmpty("search_text"))
        assertEquals("", mapOf("value" to "").deviceStringIncludingEmpty("value"))
        assertThrows(IllegalArgumentException::class.java) {
            emptyMap<String, String>().deviceStringIncludingEmpty("value")
        }
    }

    @Test
    fun inputToolAcceptsFocusedOrPreciselySelectedFieldAndRequiresRealConfirmation() = runTest {
        val provider = RecordingProvider { capability ->
            if (capability == "accessibility_input_text") "false" else "ok:$capability"
        }
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))
        val input = registry.context.tools.get("android_input_text")!!
        assertEquals(ToolAccess.DEVICE, input.access)
        assertEquals(ToolApprovalPolicy.MUTATION, input.approvalPolicy)
        val emptyFocus = registry.context.tools.execute("android_input_text",
            buildJsonObject { put("value", "") },
            context = ToolContext(approval = { true }))
        assertEquals("ANDROID_ACTION_UNCONFIRMED", emptyFocus.errorCode)
        assertEquals(mapOf("value" to ""), provider.calls.last().second)
        val byId = registry.context.tools.execute("android_input_text",
            buildJsonObject { put("id", "app:id/search"); put("value", "新文字") },
            context = ToolContext(approval = { true }))
        assertEquals("ANDROID_ACTION_UNCONFIRMED", byId.errorCode)
        assertEquals("app:id/search", provider.calls.last().second["id"])
        val bad = registry.context.tools.execute("android_input_text",
            buildJsonObject { put("id", "app:id/search") },
            context = ToolContext(approval = { true }))
        assertTrue(bad.isError)
        assertEquals(2, provider.calls.count { it.first == "accessibility_input_text" })
    }

    @Test
    fun deviceNumericAndBooleanArgumentsRejectMalformedValuesInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("duration_ms" to "abc").deviceOptionalLong("duration_ms", 60L, 1L..60_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("duration_ms" to "-1").deviceOptionalLong("duration_ms", 60L, 1L..60_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("width" to "oops").deviceOptionalInt("width", 1080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("clickable" to "yes").deviceOptionalBoolean("clickable")
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("x" to "NaN").deviceRequiredFiniteFloat("x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapOf("x" to "Infinity").deviceRequiredFiniteFloat("x")
        }

        assertEquals(60L, emptyMap<String, String>().deviceOptionalLong("duration_ms", 60L, 1L..60_000L))
        assertEquals(1080, emptyMap<String, String>().deviceOptionalInt("width", 1080))
        assertEquals(true, mapOf("clickable" to "true").deviceOptionalBoolean("clickable"))
        assertEquals(12.5f, mapOf("x" to "12.5").deviceRequiredFiniteFloat("x"))
    }

}
