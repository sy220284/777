package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.device.AndroidDevicePlugin
import com.labteto.dshmobile.harness.capability.HarnessAndroidDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.harness.capability.HarnessVirtualDisplayProvider
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.local.security.LocalPolicyLayer
import com.labteto.dshmobile.local.security.mergeLocalExecutionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalManagedDeviceProviderTest {
    private class FakeProvider : HarnessAndroidDeviceProvider {
        override val capabilities = setOf("android_open_uri", "android_tap", "vscreen_create")
        val called = mutableListOf<String>()
        val acquiredOwners = mutableListOf<String>()
        val releasedDisplays = mutableListOf<String>()
        var closed = false

        override suspend fun invoke(capability: String, arguments: Map<String, String>): String {
            called += capability
            return "executed:$capability"
        }

        override suspend fun acquireAgentVirtualDisplay(owner: String): String {
            acquiredOwners += owner
            return "virtual:$owner"
        }

        override fun releaseAgentVirtualDisplay(id: String) {
            releasedDisplays += id
        }

        override fun close() { closed = true }
    }

    @Test fun browserOriginRecheckedBeforeEveryDeviceInvocation() = runTest {
        val fake = FakeProvider()
        var denied = setOf("blocked.example")
        val provider = LocalManagedDeviceProvider(fake) {
            mergeLocalExecutionPolicy(managed = LocalPolicyLayer(deniedHosts = denied))
        }
        assertEquals("executed:android_open_uri", provider.invoke("android_open_uri",
            mapOf("uri" to "https://ok.example/index.html")))
        assertTrue(runCatching { provider.invoke("android_open_uri",
            mapOf("uri" to "https://blocked.example/")) }.isFailure)
        assertTrue(runCatching { provider.invoke("android_open_uri",
            mapOf("uri" to "https://user:password@ok.example/")) }.isFailure)
        denied = setOf("ok.example")
        assertTrue(runCatching { provider.invoke("android_open_uri",
            mapOf("uri" to "https://ok.example/")) }.isFailure)
        assertEquals(listOf("android_open_uri"), fake.called)
    }

    @Test fun nonBrowserDeviceCapabilitiesRetainNativePermissionsAndLifecycle() = runTest {
        val fake = FakeProvider()
        val provider = LocalManagedDeviceProvider(fake) {
            mergeLocalExecutionPolicy(managed = LocalPolicyLayer(networkEnabled = false))
        }
        assertEquals("executed:android_tap",
            provider.invoke("android_tap", mapOf("x" to "12", "y" to "34")))
        assertEquals(listOf("android_tap"), fake.called)
        provider.close()
        assertTrue(fake.closed)
    }

    @Test fun androidDevicePluginPublishesBothContractsAndVirtualDisplayLifecycle() = runTest {
        val fake = FakeProvider()
        val provider = LocalManagedDeviceProvider(fake) {
            mergeLocalExecutionPolicy(managed = LocalPolicyLayer())
        }
        val registry = PluginRegistry()
        registry.install(AndroidDevicePlugin(provider))

        val device = registry.context.capabilities.require("android-device", HarnessDeviceProvider::class)
        val virtual = registry.context.capabilities.require("android-device", HarnessVirtualDisplayProvider::class)
        assertSame(provider, device)
        assertSame(provider, virtual)
        assertEquals("executed:android_tap", device.invoke("android_tap", emptyMap()))

        val displayId = virtual.acquireAgentVirtualDisplay("work-1")
        assertEquals("virtual:work-1", displayId)
        virtual.releaseAgentVirtualDisplay(displayId)
        assertEquals(listOf("work-1"), fake.acquiredOwners)
        assertEquals(listOf(displayId), fake.releasedDisplays)

        assertTrue(registry.uninstall("android-device"))
        assertNull(registry.context.capabilities.get("android-device", HarnessDeviceProvider::class))
        assertNull(registry.context.capabilities.get("android-device", HarnessVirtualDisplayProvider::class))
        assertTrue(fake.closed)
    }
}

