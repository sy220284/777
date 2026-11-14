package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.capability.HarnessDeviceProvider
import com.labteto.dshmobile.local.security.LocalPolicyLayer
import com.labteto.dshmobile.local.security.mergeLocalExecutionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalManagedDeviceProviderTest {
    private class FakeProvider : HarnessDeviceProvider {
        override val capabilities = setOf("android_open_uri", "android_tap")
        val called = mutableListOf<String>()
        override suspend fun invoke(capability: String, arguments: Map<String, String>): String {
            called += capability
            return "executed:$capability"
        }
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
    }
}
