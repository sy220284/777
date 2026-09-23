package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessAndroidTest {
    @Test fun systemProcessGroupWorksOnAndroid() = runBlocking {
        val runtime = AndroidProcessRuntime()
        val normal = runtime.execute(ProcessRequest(listOf("/system/bin/sh", "-c", "printf ready")))
        assertEquals(0, normal.exitCode)
        assertEquals("ready", normal.stdout)
        val timeout = runtime.execute(ProcessRequest(listOf("/system/bin/sh", "-c", "sleep 30"), timeoutMillis = 100))
        assertTrue(timeout.timedOut)
    }
}
