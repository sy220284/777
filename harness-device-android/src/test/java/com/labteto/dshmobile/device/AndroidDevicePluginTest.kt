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
        assertTrue("android_settings_set" in names)
        assertTrue("android_vscreen_create" in names)
        assertTrue("android_vscreen_screenshot" in names)
        assertTrue("android_find" in names)
        assertTrue("android_click_node" in names)
        assertTrue("android_wait" in names)
        assertTrue("android_notification_status" in names)

        val info = requireNotNull(registry.context.tools.get("android_device_info"))
        assertEquals(ToolAccess.READ_ONLY, info.access)
        assertEquals(ToolApprovalPolicy.MUTATION, info.approvalPolicy)

        val settingsSet = requireNotNull(registry.context.tools.get("android_settings_set"))
        assertEquals(ToolAccess.PRIVILEGED, settingsSet.access)
        assertEquals(ToolApprovalPolicy.ALWAYS, settingsSet.approvalPolicy)

        val appStop = requireNotNull(registry.context.tools.get("android_app_stop"))
        assertEquals(ToolAccess.PRIVILEGED, appStop.access)
        assertEquals(ToolApprovalPolicy.ALWAYS, appStop.approvalPolicy)

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
    fun privilegedToolCannotReachProviderWithoutApproval() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val denied = registry.context.tools.execute(
            name = "android_settings_set",
            input = buildJsonObject {
                put("namespace", "secure")
                put("key", "demo")
                put("value", "1")
            },
        )

        assertTrue(denied.isError)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun approvedPrivilegedToolReceivesArgumentsExactlyOnce() = runTest {
        val provider = RecordingProvider()
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val result = registry.context.tools.execute(
            name = "android_settings_set",
            input = buildJsonObject {
                put("namespace", "secure")
                put("key", "demo")
                put("value", "1")
            },
            context = ToolContext(approval = { true }),
        )

        assertFalse(result.isError)
        assertEquals("ok:settings_set", result.content)
        assertEquals(1, provider.calls.size)
        assertEquals("settings_set", provider.calls.single().first)
        assertEquals("secure", provider.calls.single().second["namespace"])
        assertEquals("demo", provider.calls.single().second["key"])
        assertEquals("1", provider.calls.single().second["value"])
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
