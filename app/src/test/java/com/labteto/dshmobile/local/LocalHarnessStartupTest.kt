package com.labteto.dshmobile.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHarnessStartupTest {
    @Test
    fun sessionRestoreRunsInParallelWithRuntimeAndPluginPreparation() = runTest {
        val runtimeStarted = CompletableDeferred<Unit>()
        val pluginsStarted = CompletableDeferred<Unit>()
        val sessionStarted = CompletableDeferred<Unit>()
        val releaseCapabilities = CompletableDeferred<Unit>()

        val startup = async {
            prepareLocalHarnessStartup(
                prepareRuntime = {
                    runtimeStarted.complete(Unit)
                    releaseCapabilities.await()
                },
                installPlugins = {
                    pluginsStarted.complete(Unit)
                    releaseCapabilities.await()
                },
                restoreSession = {
                    sessionStarted.complete(Unit)
                },
            )
        }

        runCurrent()
        assertTrue(runtimeStarted.isCompleted)
        assertTrue(pluginsStarted.isCompleted)
        assertTrue(sessionStarted.isCompleted)
        assertFalse(startup.isCompleted)

        releaseCapabilities.complete(Unit)
        advanceUntilIdle()
        assertTrue(startup.isCompleted)
    }
}
