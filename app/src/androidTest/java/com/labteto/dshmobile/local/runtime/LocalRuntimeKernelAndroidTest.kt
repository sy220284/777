package com.labteto.dshmobile.local.runtime

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalRuntimeKernelAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferences
        get() = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @After
    fun cleanUpPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun startIsIdempotentAndPersistsGeneratedSessionIdentity() = runBlocking {
        val bootstrap = RecordingBootstrap()
        val kernel = LocalRuntimeKernel(
            context = context,
            runtimeStateStore = LocalRuntimeStateStore(),
            bootstrap = bootstrap,
        )

        kernel.start()
        kernel.start()
        withTimeout(5_000L) { bootstrap.restoreStarted.await() }

        assertEquals(1, bootstrap.initializeCalls.get())
        assertEquals(1, bootstrap.restoreCalls.get())
        val sessionId = bootstrap.initializeSessionIds.single()
        assertTrue(sessionId.isNotBlank())
        assertEquals(sessionId, bootstrap.restoreSessionIds.single())
        assertEquals(sessionId, preferences.getString(KEY_SESSION_ID, null))
    }

    @Test
    fun startReusesPersistedSessionIdentity() = runBlocking {
        preferences.edit().putString(KEY_SESSION_ID, "persisted-session").commit()
        val bootstrap = RecordingBootstrap()
        val kernel = LocalRuntimeKernel(
            context = context,
            runtimeStateStore = LocalRuntimeStateStore(),
            bootstrap = bootstrap,
        )

        kernel.start()
        withTimeout(5_000L) { bootstrap.restoreStarted.await() }

        assertEquals(listOf("persisted-session"), bootstrap.initializeSessionIds)
        assertEquals(listOf("persisted-session"), bootstrap.restoreSessionIds)
    }

    @Test
    fun bootstrapFailureIsProjectedWithoutRestartingKernel() = runBlocking {
        val bootstrap = RecordingBootstrap(restoreFailure = IllegalStateException("boom"))
        val runtimeStateStore = LocalRuntimeStateStore()
        val kernel = LocalRuntimeKernel(
            context = context,
            runtimeStateStore = runtimeStateStore,
            bootstrap = bootstrap,
        )

        kernel.start()
        kernel.start()
        withTimeout(5_000L) {
            while (runtimeStateStore.state.value.error?.contains("boom") != true) {
                delay(10L)
            }
        }

        assertEquals(1, bootstrap.initializeCalls.get())
        assertEquals(1, bootstrap.restoreCalls.get())
        assertEquals(false, runtimeStateStore.state.value.loading)
        assertTrue(runtimeStateStore.state.value.error.orEmpty().contains("Runtime 初始化失败"))
    }

    private class RecordingBootstrap(
        private val restoreFailure: Throwable? = null,
    ) : LocalRuntimeBootstrapPort {
        val initializeCalls = AtomicInteger(0)
        val restoreCalls = AtomicInteger(0)
        val initializeSessionIds = CopyOnWriteArrayList<String>()
        val restoreSessionIds = CopyOnWriteArrayList<String>()
        val restoreStarted = CompletableDeferred<Unit>()

        override fun initialize(scope: CoroutineScope, initialSessionId: String) {
            initializeCalls.incrementAndGet()
            initializeSessionIds += initialSessionId
        }

        override suspend fun prepareAndRestore(scope: CoroutineScope, initialSessionId: String) {
            restoreCalls.incrementAndGet()
            restoreSessionIds += initialSessionId
            restoreStarted.complete(Unit)
            restoreFailure?.let { throw it }
        }
    }
}
