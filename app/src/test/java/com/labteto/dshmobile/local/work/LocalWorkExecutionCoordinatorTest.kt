package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun regenerateAdmissionIsOwnedByWorkFeatureBeforeStartingRegenerationOwner() {
        val fake = RecordingTurnPort()
        val runtime = readyRuntimeForRegeneration()
        val coordinator = coordinator(fake, runtime)

        assertTrue(coordinator.regenerateReply("assistant-1"))
        assertEquals("assistant-1", fake.lastRegenerateId)
        assertEquals(1, fake.regenerateStartCalls)

        assertFalse(coordinator.regenerateReply("assistant-2"))
        assertEquals(1, fake.regenerateStartCalls)
    }

    private fun coordinator(
        turn: RecordingTurnPort,
        runtime: LocalRuntimeStateStore = defaultRuntime(),
    ): LocalWorkExecutionCoordinator =
        LocalWorkExecutionCoordinator(
            workRunRegistry = LocalWorkRunRegistry(runtime),
            runtimeStateStore = runtime,
            eventLogFor = { error("首轮启动不得绕回持久事件日志入口") },
            enqueueSnapshot = { error("首轮启动不得写排队快照") },
            startPreparedTurn = turn::startPrepared,
            startRegeneration = turn::startRegeneration,
        )

    private fun defaultRuntime(): LocalRuntimeStateStore =
        LocalRuntimeStateStore().also { runtime ->
            runtime.initialize(
                LocalHarnessState(
                    sessionId = "work-execution-test",
                    loading = false,
                    usageMode = LocalUsageMode.WORK,
                    modelState = LocalModelState(configured = true),
                ),
            )
        }

    private fun readyRuntimeForRegeneration(): LocalRuntimeStateStore =
        LocalRuntimeStateStore().also { runtime ->
            runtime.initialize(
                LocalHarnessState(
                    sessionId = "work-regenerate-test",
                    loading = false,
                    usageMode = LocalUsageMode.WORK,
                    modelState = LocalModelState(configured = true),
                    messages = listOf(
                        LocalHarnessMessage("user-1", "user", "任务", createdAt = 1L),
                        LocalHarnessMessage("assistant-1", "assistant", "结果", createdAt = 2L),
                    ),
                ),
            )
            runtime.foregroundRunHandle.modelHistory.append(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "结果")
                },
            )
        }

    private class RecordingTurnPort : LocalWorkTurnPort {
        var startCalls: Int = 0
        var regenerateStartCalls: Int = 0
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

        fun startRegeneration(messageId: String): Job {
            regenerateStartCalls += 1
            lastRegenerateId = messageId
            return Job().also { it.complete() }
        }
    }
}
