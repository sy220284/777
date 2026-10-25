package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalAgentActivationWaitTest {
    private fun registration(status: String = "running", activation: Long = 0) = LocalJobRegistration(
        LocalJobInfo("child", "子代理：child", status, ownerSessionId = "session", isAgent = true),
        JobSnapshot("child", "子代理：child", status, ownerId = "session"),
        runtimeActivation = activation,
    )

    @Test fun queueNotificationsAreNotLoadProofButFastCompletedActivationIsObserved() = runTest {
        val changes = MutableStateFlow(0L)
        var current = registration()
        val waiting = async { awaitLocalAgentActivation(changes, 0, { current }) }
        runCurrent()
        changes.value++
        runCurrent()
        assertFalse(waiting.isCompleted)
        current = registration("dormant", 1)
        changes.value++
        val loaded = waiting.await()
        assertEquals(1L, loaded?.runtimeActivation)
        assertFalse(requireNotNull(loaded).executionActive)
    }

    @Test fun stalePriorActivationCannotConfirmAQueuedResume() = runTest {
        val changes = MutableStateFlow(0L)
        val stale = registration(activation = 2)
        assertNull(awaitLocalAgentActivation(changes, 2, { stale }, timeoutMillis = 100))
    }
}
