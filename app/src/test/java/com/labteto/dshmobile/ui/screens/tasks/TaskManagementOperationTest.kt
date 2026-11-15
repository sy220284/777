package com.labteto.dshmobile.ui.screens.tasks

import com.labteto.dshmobile.uicontract.AutomationUiReceipt
import com.labteto.dshmobile.uicontract.UiAutomationRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskManagementOperationTest {
    @Test fun failedAcknowledgementReadsCurrentFactsWithoutReplayingMutation() = runBlocking {
        var mutationCalls = 0
        var observations = 0
        val result = runTaskManagementOperation(
            operation = {
                mutationCalls++
                throw IllegalStateException("remote acknowledgement lost after write")
            },
            readTasks = {
                observations++
                emptyList()
            },
        )
        assertEquals(1, mutationCalls)
        assertEquals(1, observations)
        assertFalse(result.accepted)
        assertTrue(result.failed)
        assertEquals(emptyList<com.labteto.dshmobile.automation.AutomationTask>(), result.tasks)
    }

    @Test fun staleTaskShowsItsReasonAndDoesNotClaimSuccess() = runBlocking {
        var reads = 0
        val result = runTaskManagementOperation(
            operation = { AutomationUiReceipt.Rejected(UiAutomationRejection.STALE_TASK) },
            readTasks = { reads++; emptyList() },
        )
        assertFalse(result.accepted)
        assertFalse(result.failed)
        assertTrue(result.failureMessage.orEmpty().contains("刷新"))
        assertEquals(1, reads)
    }

    @Test fun explicitFailurePreservesTheOwnerReasonAfterReadingCurrentFacts() = runBlocking {
        val result = runTaskManagementOperation(
            operation = { AutomationUiReceipt.Failed("系统排程服务不可用") },
            readTasks = { emptyList() },
        )
        assertFalse(result.accepted)
        assertTrue(result.failed)
        assertEquals("系统排程服务不可用", result.failureMessage)
    }

    @Test fun successfulCommitWithFailedRefreshMustNotBeRetriedAsASecondCreation() = runBlocking {
        var writes = 0
        var reads = 0
        val result = runTaskManagementOperation(
            operation = { writes++; AutomationUiReceipt.Applied("created-task") },
            readTasks = { reads++; error("存储暂时不可读取") },
        )
        assertEquals(1, writes)
        assertEquals(1, reads)
        assertTrue("任务写入已受理，编辑器可以关闭", result.accepted)
        assertTrue("列表刷新确实失败，界面需要提示", result.failed)
        assertTrue(result.failureMessage.orEmpty().contains("已受理"))
        assertEquals(null, result.tasks)
    }

    @Test fun userCancellationIsPropagatedWithoutNewMutation() = runBlocking {
        var observed = false
        try {
            runTaskManagementOperation(
                operation = { throw CancellationException("user cancelled") },
                readTasks = { observed = true; emptyList() },
            )
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(observed)
        }
    }
}
