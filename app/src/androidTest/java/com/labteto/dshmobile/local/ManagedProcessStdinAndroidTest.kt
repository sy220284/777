package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedProcessStdinAndroidTest {
    @Test fun zeroExitCannotClaimSuccessWhenStdinWasNotDelivered() = runBlocking {
        val result = AndroidProcessRuntime().execute(
            ProcessRequest(
                command = listOf(
                    "/system/bin/sh", "-c",
                    "exec 0<&-; printf STARTED; exit 0",
                ),
                stdin = "z".repeat(1024 * 1024),
                timeoutMillis = 5_000L,
            ),
        )
        assertFalse(result.timedOut)
        assertTrue(result.stdout.contains("STARTED"))
        assertTrue("stdin must be reported incomplete", result.stdinWriteFailed)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("STDIN_WRITE_FAILED"))
    }

    @Test fun earlyStdinClosePreservesExitCodeAndProcessOutput() = runBlocking {
        val result = AndroidProcessRuntime().execute(
            ProcessRequest(
                command = listOf("/system/bin/sh", "-c", "exec 0<&-; printf STDOUT; printf STDERR >&2; exit 7"),
                stdin = "x".repeat(256 * 1024),
                timeoutMillis = 5_000L,
            ),
        )
        assertFalse(result.timedOut)
        assertEquals(7, result.exitCode)
        assertTrue(result.stdout.contains("STDOUT"))
        assertTrue(result.stderr.contains("STDERR"))
        if (result.stdinWriteFailed) assertTrue(result.stderr.contains("STDIN_WRITE_FAILED"))
    }
}
