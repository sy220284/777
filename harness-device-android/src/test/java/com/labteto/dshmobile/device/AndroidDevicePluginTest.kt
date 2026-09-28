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
    fun registersSupportedDeviceSurfaceWithExplicitRiskPolicies() = runTest {
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(RecordingProvider()))

        val names = registry.context.tools.names().toSet()
        assertEquals(16, names.size)
        assertTrue("android_device_info" in names)
        assertTrue("android_app_launch" in names)
        assertTrue("android_settings_get" in names)
        assertTrue("android_notification_status" in names)
        assertTrue("android_vscreen_create" in names)
        assertTrue("android_vscreen_screenshot" in names)
        assertFalse("android_privilege_status" in names)
        assertFalse("android_screen" in names)
        assertFalse("android_tap" in names)
        assertFalse("android_vscreen_tap" in names)

        val info = requireNotNull(registry.context.tools.get("android_device_info"))
        assertEquals(ToolAccess.READ_ONLY, info.access)
        assertEquals(ToolApprovalPolicy.MUTATION, info.approvalPolicy)

        val notificationList = requireNotNull(registry.context.tools.get("android_notification_list"))
        assertEquals(ToolAccess.READ_ONLY, notificationList.access)
        assertEquals(ToolApprovalPolicy.ALWAYS, notificationList.approvalPolicy)

        val screenshot = requireNotNull(registry.context.tools.get("android_vscreen_screenshot"))
        assertEquals(ToolAccess.READ_ONLY, screenshot.access)
        assertEquals(ToolApprovalPolicy.ALWAYS, screenshot.approvalPolicy)
    }

    @Test
    fun uninstallsWithoutLeakingTools() = runTest {
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(RecordingProvider()))

        assertEquals(16, registry.context.tools.names().size)
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
        val launch = registry.context.tools.execute(
            name = "android_app_launch",
            input = buildJsonObject { put("package", "com.example.demo") },
            context = ToolContext(
                allowMutation = false,
                approval = { true },
            ),
        )

        assertFalse(info.isError)
        assertEquals("ok:device_info", info.content)
        assertTrue(launch.isError)
        assertEquals(listOf("device_info"), provider.calls.map { it.first })
    }

    @Test
    fun sensitiveReadsRequireFreshApprovalBeforeProviderInvocation() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        for (name in listOf("android_notification_list", "android_clipboard_get", "android_vscreen_screenshot")) {
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
