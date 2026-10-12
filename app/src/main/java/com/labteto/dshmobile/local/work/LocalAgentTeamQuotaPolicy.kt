package com.labteto.dshmobile.local.work

/**
 * Runtime admission is based on currently reserved/live capacity, never the append-only
 * number of historical members or finished tasks. Past results remain queryable.
 */
internal fun activeTeamMemberReservations(
    members: List<LocalTeamMemberSnapshot>,
    liveJobIds: Set<String>,
): Int = members.count { member ->
    when (member.phase) {
        LocalTeamMemberPhase.CREATED, LocalTeamMemberPhase.PROVISIONING -> true
        LocalTeamMemberPhase.ACTIVE -> member.jobId in liveJobIds
        else -> false
    }
}

internal fun activeTeamTaskReservations(tasks: List<LocalTeamTaskSnapshot>): Int =
    tasks.count { it.status == LocalTeamTaskStatus.PENDING ||
        it.status == LocalTeamTaskStatus.IN_PROGRESS }
