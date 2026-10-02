package com.labteto.dshmobile.device

import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
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
import org.junit.Assert.assertThrows
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
        assertTrue("android_vscreen_create" in names)
        assertTrue("android_vscreen_screenshot" in names)
        assertTrue("android_find" in names)
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
        assertEquals(31, names.size)
        assertEquals(31, names.toSet().size)
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
        var closed: Boolean = false

        override suspend fun invoke(
            capability: String,
            arguments: Map<String, String>,
        ): String {
            calls += capability to arguments
            return "ok:$capability"
        }

        override fun close() {
            closed = true
        }
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
