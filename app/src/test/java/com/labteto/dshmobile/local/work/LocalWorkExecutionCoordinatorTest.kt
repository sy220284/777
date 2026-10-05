package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendResult
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkExecutionCoordinatorTest {
    @Test
    fun emptyInputIsRejectedInsideWorkFeatureWithoutCallingTurnBridge() {
        val fake = RecordingTurnPort()
        val coordinator = coordinator(fake)

        val result = coordinator.send("   ", emptyList())

        assertEquals(LocalSendResult.Empty, result)
        assertEquals(0, fake.startCalls)
    }

    @Test
    fun preparedWorkInputStartsThroughTheNarrowTurnBridge() {
        val fake = RecordingTurnPort()
        val coordinator = coordinator(fake)

        val result = coordinator.send("  完成这项任务  ", emptyList())

        assertEquals(LocalSendResult.Started, result)
        assertEquals(1, fake.startCalls)
        assertEquals("完成这项任务", fake.lastPrepared?.content)
        assertEquals("完成这项任务", fake.lastPrepared?.memoryInput)
    }

    @Test
    fun regenerateDelegatesWithoutRestoringAnEngineProductEntry() {
        val fake = RecordingTurnPort(regenerateResult = true)
        val coordinator = coordinator(fake)

        assertTrue(coordinator.regenerateReply("assistant-1"))
        assertEquals("assistant-1", fake.lastRegenerateId)

        fake.regenerateResult = false
        assertFalse(coordinator.regenerateReply("assistant-2"))
    }

    private fun coordinator(turn: RecordingTurnPort): LocalWorkExecutionCoordinator {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                sessionId = "work-execution-test",
                loading = false,
                usageMode = LocalUsageMode.WORK,
                modelState = LocalModelState(configured = true),
            ),
        )
        return LocalWorkExecutionCoordinator(
            workRunRegistry = LocalWorkRunRegistry(runtime),
            runtimeStateStore = runtime,
            eventLogFor = { error("首轮启动不得绕回持久事件日志入口") },
            enqueueSnapshot = { error("首轮启动不得写排队快照") },
            turn = turn,
        )
    }

    private class RecordingTurnPort(
        var regenerateResult: Boolean = false,
    ) : LocalWorkTurnPort {
        var startCalls: Int = 0
        var lastPrepared: LocalPreparedSend? = null
        var lastRegenerateId: String? = null

        override fun startPrepared(
            prepared: LocalPreparedSend,
            sessionLease: LocalSessionRuntimeLease,
        ): Job {
            startCalls += 1
            lastPrepared = prepared
            sessionLease.close()
            return Job().also { it.complete() }
        }

        override fun regenerateReply(messageId: String): Boolean {
            lastRegenerateId = messageId
            return regenerateResult
        }
    }
}
