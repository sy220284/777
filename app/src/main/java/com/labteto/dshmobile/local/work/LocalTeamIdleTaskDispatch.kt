package com.labteto.dshmobile.local.work

/** Deterministic claim selection: only idle, mailbox-empty members; no new tool authority. */
internal fun nextIdleTeamTaskAssignment(
    team: LocalTeamProjection,
    activity: LocalAgentTeamActivityProjection,
    currentRootRevision: Long? = null,
): Pair<LocalTeamMemberSnapshot, LocalTeamTaskSnapshot>? {
    if (team.failure != null) return null
    val activityById = activity.members.associateBy(LocalTeamMemberActivityView::memberId)
    val byId = team.tasks.associateBy(LocalTeamTaskSnapshot::id)
    val ready = team.tasks.asSequence()
        .filter { it.status == LocalTeamTaskStatus.PENDING && it.ownerId == null }
        // Previously authorized work cannot silently restart after a new USER instruction.
        .filter { it.rootUserRevision == null || currentRootRevision == null ||
            it.rootUserRevision == currentRootRevision }
        .filter { task -> task.blockedBy.all { byId[it]?.status == LocalTeamTaskStatus.COMPLETED } }
        .sortedWith(compareBy<LocalTeamTaskSnapshot> {
            it.id.removePrefix("task-").toIntOrNull() ?: Int.MAX_VALUE
        }.thenBy { it.id }).toList()
    for (member in team.members.sortedBy(LocalTeamMemberSnapshot::id)) {
        if (member.phase != LocalTeamMemberPhase.ACTIVE) continue
        val view = activityById[member.id] ?: continue
        if (view.activity != LocalTeamMemberActivity.IDLE ||
            view.currentTaskId != null || view.pendingMessageCount != 0) continue
        for (task in ready) {
            if (task.writeScopes.isNotEmpty() && !member.mutableToolsEnabled) continue
            val overlaps = task.writeScopes.any { left ->
                team.tasks.any { other ->
                    other.status == LocalTeamTaskStatus.IN_PROGRESS &&
                        other.writeScopes.any { right ->
                            left == right || left.startsWith("$right/") || right.startsWith("$left/")
                        }
                }
            }
            if (!overlaps) return member to task
        }
    }
    return null
}
