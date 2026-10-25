package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo

/**
 * E14. Source-owned live snapshot alongside the historical Agent Run topology.
 * Never upgrades checkpoint "running" to a live assertion, reveals another session
 * or treats Inbox delivery / Job completion as acceptance of the user's Goal.
 */
internal fun renderLocalAgentLiveRoster(
    sessionId: String,
    infos: List<LocalJobInfo>,
    maxVisible: Int,
): String {
    require(sessionId.isNotBlank()) { "AGENT_ROSTER_SESSION_REQUIRED" }
    require(maxVisible in 1..256) { "AGENT_ROSTER_LIMIT_INVALID" }
    val owned = infos.filter { it.ownerSessionId == sessionId && it.isAgent }
        .sortedWith(compareByDescending<LocalJobInfo> { it.updatedAt }.thenBy { it.id })
    val visible = owned.take(maxVisible)
    val counts = owned.groupingBy { it.status.lowercase() }.eachCount()
    return buildString {
        append("live_job_roster source=JobManager owned=").append(owned.size)
        append(" displayed=").append(visible.size)
        append(" partial=").append(owned.size > visible.size)
        append("\nstatus_counts=")
        append(counts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" })
        append("\njob_status_is_not_goal_acceptance=true")
        append("\ninterruption=agent_inspect/interrupt_agent/agent_resume/agent_close (owner checks apply)")
        visible.forEach { info ->
            append("\njob=").append(info.id)
            append(" status=").append(info.status)
            append(" continuable=").append(info.continuable)
            append(" inbox_pending=").append(info.pendingMessageCount)
        }
    }
}
