package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubagentContinuationTest {
    @Test
    fun historyCheckpointRoundTripsHistoryClaimedMessagesAndStep() {
        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-1",
            agentId = "sa-1",
            step = 7,
            history = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", "只读子代理")
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", "继续检查")
                },
            ),
            claimedMessageIds = linkedSetOf("msg-1", "msg-2"),
            softStepLimit = 12,
        )

        val decoded = decodeLocalSubagentHistoryCheckpoint(encoded)

        requireNotNull(decoded)
        assertEquals(7, decoded.step)
        assertEquals(listOf("system", "user"), decoded.history.map { it["role"].toString().trim('"') })
        assertEquals(setOf("msg-1", "msg-2"), decoded.claimedMessageIds)
        assertEquals(12, decoded.softStepLimit)
    }

    @Test
    fun inboxClaimEventRoundTripsFullQueuedInputContract() {
        val modelMessage = buildJsonObject {
            put("role", "user")
            put("content", "结构化内容")
        }
        val encoded = encodeLocalSubagentInboxClaimEvent(
            agentId = "sa-1",
            backgroundJobId = "job-1",
            messages = listOf(
                QueuedAgentInput(
                    id = "msg-1",
                    content = "展示文本",
                    memoryInput = "记忆输入",
                    modelMessage = modelMessage,
                ),
            ),
        )

        val decoded = requireNotNull(decodeLocalSubagentInboxClaimedMessages(encoded))

        assertEquals(1, decoded.size)
        assertEquals("msg-1", decoded.single().id)
        assertEquals("展示文本", decoded.single().content)
        assertEquals("记忆输入", decoded.single().memoryInput)
        assertEquals(modelMessage, decoded.single().modelMessage)
    }

    @Test
    fun turnBoundaryCleanupCannotReplacePrimaryPersistenceFailure() {
        val primary = IllegalStateException("checkpoint write failed")
        val cleanup = IllegalStateException("compaction event write failed")

        runSubagentTurnBoundaryCleanup(primary) { throw cleanup }

        assertEquals(1, primary.suppressed.size)
        assertEquals(cleanup, primary.suppressed.single())
    }

    @Test
    fun turnBoundaryCleanupStillFailsWhenThereIsNoPrimaryFailure() {
        val cleanup = IllegalStateException("compaction event write failed")

        val failure = runCatching {
            runSubagentTurnBoundaryCleanup(null) { throw cleanup }
        }.exceptionOrNull()

        assertEquals(cleanup, failure)
    }

    @Test
    fun duplicateTerminalCheckpointRequiresSameStepAndOutput() {
        assertEquals(
            true,
            isDuplicateLocalSubagentTerminalCheckpoint(
                previousStep = 4,
                previousOutput = "完成",
                step = 4,
                terminalOutput = "完成",
            ),
        )
        assertEquals(
            false,
            isDuplicateLocalSubagentTerminalCheckpoint(
                previousStep = 4,
                previousOutput = "完成",
                step = 5,
                terminalOutput = "完成",
            ),
        )
        assertEquals(
            false,
            isDuplicateLocalSubagentTerminalCheckpoint(
                previousStep = 4,
                previousOutput = "完成",
                step = 4,
                terminalOutput = "补充结果",
            ),
        )
        assertEquals(
            false,
            isDuplicateLocalSubagentTerminalCheckpoint(
                previousStep = 4,
                previousOutput = "完成",
                step = 4,
                terminalOutput = null,
            ),
        )
    }

    @Test
    fun terminalCheckpointRoundTripsCompletionOutput() {
        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-1",
            agentId = "sa-1",
            step = 9,
            history = listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "最终结论")
                },
            ),
            claimedMessageIds = setOf("msg-1"),
            terminalOutput = "最终结论",
        )

        val decoded = requireNotNull(decodeLocalSubagentHistoryCheckpoint(encoded))

        assertEquals(9, decoded.step)
        assertEquals("最终结论", decoded.terminalOutput)
        assertEquals(setOf("msg-1"), decoded.claimedMessageIds)
    }

    @Test
    fun terminalCheckpointIgnoresStaleClaimedInboxButColdResumesForNewMessage() {
        val checkpoint = LocalSubagentHistoryCheckpoint(
            history = emptyList(),
            claimedMessageIds = setOf("msg-old"),
            step = 3,
            softStepLimit = 6,
            terminalOutput = "已完成",
        )

        assertEquals(true, shouldSettleCompletedSubagentCheckpoint(checkpoint, emptySet()))
        assertEquals(
            true,
            shouldSettleCompletedSubagentCheckpoint(checkpoint, setOf("msg-old")),
        )
        assertEquals(
            false,
            shouldColdResumeCompletedSubagent(checkpoint, setOf("msg-old")),
        )
        assertEquals(
            false,
            shouldSettleCompletedSubagentCheckpoint(checkpoint, setOf("msg-new")),
        )
        assertEquals(
            true,
            shouldColdResumeCompletedSubagent(checkpoint, setOf("msg-new")),
        )
    }

    @Test
    fun recoveryBudgetPreservesCrashCeilingAndGrantsNewBudgetOnlyForColdActivation() {
        assertEquals(
            8,
            resolveSubagentTotalBudgetLimit(
                adaptiveStepLimit = 8,
                recoveredStep = 8,
                recoveredSoftStepLimit = 8,
                resumeAfterCompletion = false,
                maxDynamicSteps = 512,
            ),
        )
        assertEquals(
            24,
            resolveSubagentTotalBudgetLimit(
                adaptiveStepLimit = 8,
                recoveredStep = 20,
                recoveredSoftStepLimit = 24,
                resumeAfterCompletion = false,
                maxDynamicSteps = 512,
            ),
        )
        assertEquals(
            28,
            resolveSubagentTotalBudgetLimit(
                adaptiveStepLimit = 8,
                recoveredStep = 20,
                recoveredSoftStepLimit = 24,
                resumeAfterCompletion = true,
                maxDynamicSteps = 512,
            ),
        )
    }

    @Test
    fun terminalCheckpointDistinguishesStaleAckFromNewMessage() {
        val checkpoint = LocalSubagentHistoryCheckpoint(
            history = emptyList(),
            claimedMessageIds = setOf("msg-claimed"),
            step = 4,
            terminalOutput = "已完成",
        )

        assertEquals(
            true,
            shouldSettleCompletedSubagentCheckpoint(checkpoint, setOf("msg-claimed")),
        )
        assertEquals(
            false,
            shouldColdResumeCompletedSubagent(checkpoint, setOf("msg-claimed")),
        )
        assertEquals(
            false,
            shouldSettleCompletedSubagentCheckpoint(
                checkpoint,
                setOf("msg-claimed", "msg-new"),
            ),
        )
        assertEquals(
            true,
            shouldColdResumeCompletedSubagent(
                checkpoint,
                setOf("msg-claimed", "msg-new"),
            ),
        )
    }

    @Test
    fun completedReactivationGetsFreshBudgetWhileInterruptedResumeKeepsSoftLimit() {
        assertEquals(
            12,
            resolveSubagentTotalBudgetLimit(
                adaptiveStepLimit = 5,
                recoveredStep = 7,
                recoveredSoftStepLimit = 9,
                resumeAfterCompletion = true,
                maxDynamicSteps = 64,
            ),
        )
        assertEquals(
            20,
            resolveSubagentTotalBudgetLimit(
                adaptiveStepLimit = 8,
                recoveredStep = 7,
                recoveredSoftStepLimit = 20,
                resumeAfterCompletion = false,
                maxDynamicSteps = 64,
            ),
        )
    }

    @Test
    fun stepZeroForkSeedCheckpointIsRecoverableAndNonTerminal() {
        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-team-seed",
            agentId = localPersistentSubagentId("job-team-seed"),
            step = 0,
            history = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", "继承父历史")
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", "新子任务")
                },
            ),
            claimedMessageIds = emptySet(),
        )

        val decoded = requireNotNull(decodeLocalSubagentHistoryCheckpoint(encoded))

        assertEquals(0, decoded.step)
        assertEquals(null, decoded.terminalOutput)
        assertEquals(
            false,
            shouldSettleCompletedSubagentCheckpoint(decoded, emptySet()),
        )
        assertEquals("sa-team-seed", localPersistentSubagentId("job-team-seed"))
    }

    @Test
    fun rejectsCheckpointWithRolelessHistoryMessage() {
        val encoded = encodeLocalSubagentHistoryCheckpoint(
            backgroundJobId = "job-1",
            agentId = "sa-1",
            step = 1,
            history = listOf(buildJsonObject { put("content", "坏数据") }),
            claimedMessageIds = emptySet(),
        )

        assertNull(decodeLocalSubagentHistoryCheckpoint(encoded))
    }
}
