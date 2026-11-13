package com.labteto.dshmobile.harness.jobs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class JobObservationTest {
    @Test
    fun subscriptionAfterPublicationStillObservesTheChange() = runTest {
        val jobs = HarnessJobManager(scope = backgroundScope, onChanged = {})
        val before = jobs.changes.value
        val started = jobs.startPersistentResult(
            label = "子代理：observer", resumeKind = "subagent_readonly", resumePayload = "{}",
            ownerId = "session", continuable = true, requestedId = "job-observer",
        ) { _, _ -> awaitCancellation() }
        val id = requireNotNull(started.id)
        // Subscribe after the change: the same race exists between Agent inspect and watch.
        jobs.changes.first { it != before }
        assertEquals("running", jobs.infos().single { it.id == id }.status)
        val running = jobs.changes.value
        val stopped = async { jobs.changes.first { it != running } }
        jobs.kill(id, "session")
        stopped.await()
        assertEquals("cancelled", jobs.infos().single { it.id == id }.status)
    }
}
