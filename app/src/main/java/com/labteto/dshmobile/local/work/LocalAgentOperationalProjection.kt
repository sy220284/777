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

/**
 * U01: Join the real durable Job registrations to their recorded parent links.
 * Historical Run checkpoints can be windowed away, but cold persistent identity
 * must still be visible without creating or resuming a Job. The payload is never
 * surfaced to an unrelated session; damaged records remain explicitly unknown.
 */
internal fun renderLocalAgentDurableLineage(
    sessionId: String,
    registrations: List<com.labteto.dshmobile.local.jobs.LocalJobRegistration>,
    maxVisible: Int,
): String {
    require(sessionId.isNotBlank()) { "AGENT_LINEAGE_SESSION_REQUIRED" }
    require(maxVisible in 1..256) { "AGENT_LINEAGE_LIMIT_INVALID" }
    val owned = registrations.filter {
        it.info.ownerSessionId == sessionId && it.snapshot.ownerId == sessionId &&
            it.info.id == it.snapshot.id && it.info.isAgent
    }.sortedWith(
        compareByDescending<com.labteto.dshmobile.local.jobs.LocalJobRegistration> {
            it.info.updatedAt
        }.thenBy { it.info.id },
    )
    return buildString {
        append("durable_agent_lineage source=JobManager owned=").append(owned.size)
        append(" displayed=").append(minOf(owned.size, maxVisible))
        append(" partial=").append(owned.size > maxVisible)
        owned.take(maxVisible).forEach { record ->
            append("\njob=").append(record.info.id)
            val view = runCatching { projectLocalAgentResidency(record) }.getOrNull()
            if (view == null) {
                append(" lineage=unverifiable persisted_registration_invalid=true")
                return@forEach
            }
            append(" residency=").append(view.residency.name.lowercase())
            val parent = view.recordedParent
            if (parent == null) {
                append(" parent=legacy_or_root_unknown")
            } else {
                append(" parent_run=").append(parent.parentRunId)
                append(" parent_slot=").append(parent.parentSlotId)
                // Stable slot relation is a durable address; it never proves the
                // original parent process is still live or that loading is allowed.
                append(" parent_live=unverified")
            }
        }
    }
}
