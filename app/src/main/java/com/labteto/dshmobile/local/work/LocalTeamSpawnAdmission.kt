package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Commit barrier for every Team-managed model activation. The pending durable Job may
 * exist while its Team membership is provisional; it must not call a model or tools until
 * the original Lead EventLog records the corresponding ACTIVE admission.
 *
 * Codex PendingSpawn keeps provisional ownership through initial-input admission.
 * This coroutine gate reuses 777's existing durable Job/Team owners; no shadow state.
 */
internal suspend fun awaitTeamMemberSpawnCommit(
    log: LocalSessionEventLog,
    sessionId: String,
    jobId: String,
    waitMillis: Long = 30_000L,
) {
    require(sessionId.isNotBlank() && jobId.startsWith(LocalAgentTeamContract.TEAM_JOB_ID_PREFIX)) {
        "TEAM_SPAWN_TARGET_INVALID"
    }
    require(waitMillis in 1L..60_000L) { "TEAM_SPAWN_WAIT_LIMIT_INVALID" }
    val memberId = LocalAgentTeamContract.TEAM_MEMBER_ID_PREFIX +
        jobId.removePrefix(LocalAgentTeamContract.TEAM_JOB_ID_PREFIX)
    withTimeout(waitMillis) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val latest = log.latestMatching(setOf(LocalAgentTeamContract.TEAM_MEMBER_EVENT)) { data ->
                data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId &&
                    (data["member"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == memberId
            }
            val member = latest?.data?.get("member") as? JsonObject
            when (member?.get("phase")?.jsonPrimitive?.contentOrNull) {
                "active" -> return@withTimeout
                "failed", "dismissed", "disabled" -> error("TEAM_SPAWN_NOT_COMMITTED:$memberId")
                null, "created", "provisioning" -> delay(40L)
                else -> error("TEAM_SPAWN_PHASE_INVALID:$memberId")
            }
        }
    }
}
