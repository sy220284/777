package com.labteto.dshmobile.device

import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDevicePluginTest {
    @Test
    fun registersDeviceSurfaceWithExplicitRiskPolicies() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val names = registry.context.tools.names().toSet()
        assertTrue("android_device_info" in names)
        assertTrue("android_settings_get" in names)
        assertTrue("android_vscreen_create" in names)
        assertTrue("android_vscreen_screenshot" in names)
        assertTrue("android_find" in names)
        assertTrue("android_click_node" in names)
        assertTrue("android_wait" in names)
        assertTrue("android_notification_status" in names)

        val info = requireNotNull(registry.context.tools.get("android_device_info"))
        assertEquals(ToolAccess.READ_ONLY, info.access)
        assertEquals(ToolApprovalPolicy.MUTATION, info.approvalPolicy)

        val tap = requireNotNull(registry.context.tools.get("android_tap"))
        assertEquals(ToolAccess.DEVICE, tap.access)
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
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(RecordingProvider()))

        val names = registry.context.tools.names()
        assertEquals(31, names.size)
        assertEquals(31, names.toSet().size)
        assertTrue(registry.isInstalled("android-device"))

        assertTrue(registry.uninstall("android-device"))
        assertFalse(registry.isInstalled("android-device"))
        assertTrue(registry.context.tools.names().isEmpty())
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

    private class RecordingProvider : HarnessDeviceProvider {
        override val capabilities: Set<String> = emptySet()
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun invoke(
            capability: String,
            arguments: Map<String, String>,
        ): String {
            calls += capability to arguments
            return "ok:$capability"
        }
    }
}
