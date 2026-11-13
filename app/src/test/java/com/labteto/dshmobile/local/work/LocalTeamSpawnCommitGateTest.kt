package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamSpawnCommitGateTest {
    @Test fun admittedChildCannotReachFirstModelStepUntilTeamCommit() = runBlocking {
        var committed = false
        var checks = 0
        val commit = launch {
            delay(85)
            committed = true
        }
        awaitCommittedTeamAgentAdmission({
            checks++
            if (!committed) throw LocalTeamSpawnCommitPending()
        }, maxWaitMillis = 2_000)
        commit.join()
        assertTrue(committed)
        assertTrue(checks >= 2)
    }

    @Test fun cancelledOrMissingTeamMembershipFailsImmediatelyWithoutRetry() = runBlocking {
        var checks = 0
        val result = runCatching {
            awaitCommittedTeamAgentAdmission({
                checks++
                throw LocalWorkflowSuperseded()
            }, maxWaitMillis = 1_000)
        }
        assertTrue(result.exceptionOrNull() is LocalWorkflowSuperseded)
        assertEquals(1, checks)
    }

    @Test fun uncommittedChildTimesOutWithoutRunningTheModel() = runBlocking {
        var checks = 0
        val result = runCatching {
            awaitCommittedTeamAgentAdmission({
                checks++
                throw LocalTeamSpawnCommitPending()
            }, maxWaitMillis = 0)
        }
        assertTrue(result.exceptionOrNull() is LocalWorkflowSuperseded)
        assertEquals(1, checks)
    }

    @Test fun cancellingParentCancelsProvisionalAdmissionWait() = runBlocking {
        var checks = 0
        val child = launch {
            awaitCommittedTeamAgentAdmission({
                checks++
                throw LocalTeamSpawnCommitPending()
            }, maxWaitMillis = 30_000)
        }
        delay(65)
        child.cancelAndJoin()
        assertTrue(child.isCancelled)
        assertTrue(checks > 0)
    }
}
