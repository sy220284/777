package com.labteto.dshmobile.local.work

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Execution admission for a provisionally persisted Team child.
 *
 * Creation has two durable facts: the Job has been accepted and the Team member has been
 * committed as ACTIVE. A child's first model/tool step must observe both. Only the
 * PROVISIONING signal may be awaited; revoked, missing or failed membership is terminal.
 */
internal suspend fun awaitCommittedTeamAgentAdmission(
    check: () -> Unit,
    maxWaitMillis: Long = 30_000L,
) {
    require(maxWaitMillis >= 0L) { "TEAM_MEMBER_COMMIT_WAIT_INVALID" }
    val startedAt = System.nanoTime()
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            check()
            return
        } catch (_: LocalTeamSpawnCommitPending) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
            if (elapsed >= maxWaitMillis) throw LocalWorkflowSuperseded()
            delay(minOf(50L, maxWaitMillis - elapsed))
        }
    }
}
