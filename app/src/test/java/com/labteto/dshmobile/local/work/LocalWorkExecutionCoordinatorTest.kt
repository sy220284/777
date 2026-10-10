package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
    fun teamRequestNeverFallsBackToOrdinaryChatAfterModeSwitch() {
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(
            sessionId = "chat-mode", loading = false, usageMode = LocalUsageMode.CHAT,
            modelState = LocalModelState(configured = true),
        ))
        val fake = RecordingTurnPort()
        val result = coordinator(fake, runtime).sendWithTeam("执行团队任务", emptyList())
        assertFalse(result.accepted)
        assertEquals(com.labteto.dshmobile.local.send.LocalSendRejectReason.SESSION_TRANSITION, result.rejectReason)
        assertEquals(0, fake.startCalls)
        assertTrue(runtime.state.value.error.orEmpty().contains("工作模式"))
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
    fun detachedExecutionTargetsExplicitSessionAndReturnsStructuredCompletion() = runTest {
        val fake = RecordingTurnPort()
        val coordinator = coordinator(
            turn = fake,
            prepareDetachedSession = { _, preferred -> preferred ?: "created-session" },
            runDetached = { text, target, timeout, recover ->
                assertEquals("后台任务", text)
                assertEquals("session-b", target)
                assertEquals(12_000L, timeout)
                assertTrue(recover)
                LocalWorkAutomationResult(requireNotNull(target), "完成")
            },
        )

        val result = coordinator.execute(
            LocalWorkExecutionRequest(
                text = " 后台任务 ",
                targetSessionId = "session-b",
                timeoutMillis = 12_000L,
                recoverInterrupted = true,
            ),
        )

        assertEquals(LocalWorkExecutionStatus.DELIVERED, result.status)
        assertEquals("session-b", result.sessionId)
        assertEquals("完成", result.output)
    }

    @Test
    fun cancellingDetachedExecutionReturnsStructuredCancellation() = runTest {
        val fake = RecordingTurnPort()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            turn = fake,
            prepareDetachedSession = { _, preferred -> requireNotNull(preferred) },
            runDetached = { _, target, _, _ ->
                started.complete(Unit)
                never.await()
                LocalWorkAutomationResult(requireNotNull(target), "不会到达")
            },
        )

        val running = async {
            coordinator.execute(
                LocalWorkExecutionRequest(
                    text = "执行",
                    targetSessionId = "session-c",
                ),
            )
        }
        started.await()
        assertTrue(coordinator.cancel("session-c"))

        val result = running.await()
        assertEquals(LocalWorkExecutionStatus.CANCELLED, result.status)
        assertEquals("session-c", result.sessionId)
    }

    @Test
    fun workEditUsesItsOwnExecutionPortAndStartsOneNewWorkTurn() {
        val fake = RecordingTurnPort()
        val runtime = defaultRuntime()
        val coordinator = coordinator(
            fake, runtime,
            prepareEditedTurn = { messageId, text ->
                assertEquals("old-user-message", messageId)
                LocalWorkMessageEditPreparation.Ready(
                    requireNotNull(com.labteto.dshmobile.local.send.prepareLocalSend(text, emptyList())),
                    requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(
                        runtime.state.value.sessionId, LocalSessionRuntimeKind.FOREGROUND,
                    )),
                )
            },
        )
        val result = coordinator.editAndResendUserMessage("old-user-message", "更新任务")
        assertEquals(com.labteto.dshmobile.local.session.LocalUserMessageEditResult.SENT, result)
        assertEquals(1, fake.startCalls)
        assertEquals("更新任务", fake.lastPrepared?.content)
    }

    @Test
    fun workEditReportsCommittedButNotStartedWhenRunAdmissionThrows() {
        val fake = RecordingTurnPort()
        fake.failNextStart = true
        val runtime = defaultRuntime()
        val coordinator = coordinator(fake, runtime, prepareEditedTurn = { _, text ->
            LocalWorkMessageEditPreparation.Ready(
                requireNotNull(com.labteto.dshmobile.local.send.prepareLocalSend(text, emptyList())),
                requireNotNull(LocalSessionRuntimeRegistry.tryAcquire(
                    runtime.state.value.sessionId, LocalSessionRuntimeKind.FOREGROUND,
                )),
            )
        })
        assertEquals(
            com.labteto.dshmobile.local.session.LocalUserMessageEditResult.COMMITTED_NOT_STARTED,
            coordinator.editAndResendUserMessage("user", "revised"),
        )
    }

    @Test
    fun workEditReturnsPreciseHistoryFailureWithoutStartingAnotherRun() {
        val fake = RecordingTurnPort()
        val coordinator = coordinator(fake)
        assertEquals(
            com.labteto.dshmobile.local.session.LocalUserMessageEditResult.HISTORY_UNAVAILABLE,
            coordinator.editAndResendUserMessage("missing", "更新任务"),
        )
        assertEquals(0, fake.startCalls)
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
        prepareDetachedSession: suspend (String, String?) -> String = { _, preferred ->
            preferred ?: "detached-test"
        },
        runDetached: suspend (String, String?, Long, Boolean) -> LocalWorkAutomationResult =
            { _, target, _, _ -> LocalWorkAutomationResult(requireNotNull(target), "ok") },
        prepareEditedTurn: (String, String) -> LocalWorkMessageEditPreparation = { _, _ ->
            LocalWorkMessageEditPreparation.Rejected(
                com.labteto.dshmobile.local.session.LocalUserMessageEditResult.HISTORY_UNAVAILABLE,
            )
        },
    ): LocalWorkExecutionCoordinator =
        LocalWorkExecutionCoordinator(
            workRunRegistry = LocalWorkRunRegistry(runtime),
            runtimeStateStore = runtime,
            eventLogFor = { error("首轮启动不得绕回持久事件日志入口") },
            enqueueSnapshot = { error("首轮启动不得写排队快照") },
            startPreparedTurn = turn::startPrepared,
            startRegeneration = turn::startRegeneration,
            prepareEditedTurn = prepareEditedTurn,
            prepareDetachedSession = prepareDetachedSession,
            runDetached = runDetached,
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
        var failNextStart = false
        var regenerateStartCalls: Int = 0
        var lastPrepared: LocalPreparedSend? = null
        var lastRegenerateId: String? = null

        override fun startPrepared(
            prepared: LocalPreparedSend,
            sessionLease: LocalSessionRuntimeLease,
        ): Job {
            if (failNextStart) {
                sessionLease.close()
                throw IllegalStateException("run failed to start")
            }
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
