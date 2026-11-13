package com.labteto.dshmobile.local.work

/**
 * Read-only Team wait response, derived exclusively from Team EventLog + Job snapshot.
 * A Job completing one turn becomes idle; it does not destroy the teammate identity.
 * No durable watch cursor or second lifecycle owner is introduced.
 */
internal data class LocalTeamWaitMemberState(
    val id: String,
    val name: String,
    val activity: String,
)
internal data class LocalTeamWaitTaskState(
    val id: String,
    val status: String,
    val revision: Int,
    val ownerId: String?,
)
internal data class LocalTeamWaitSnapshot(
    val eventSequence: Long,
    val members: List<LocalTeamWaitMemberState>,
    val tasks: List<LocalTeamWaitTaskState> = emptyList(),
)

internal fun renderLocalTeamWaitTransition(
    before: LocalTeamWaitSnapshot,
    after: LocalTeamWaitSnapshot,
): String {
    val previous = before.members.associateBy(LocalTeamWaitMemberState::id)
    val current = after.members.associateBy(LocalTeamWaitMemberState::id)
    val ids = (previous.keys + current.keys).toSortedSet()
    val changed = ids.mapNotNull { id ->
        val from = previous[id]
        val to = current[id]
        if (from == to) null else {
            val name = to?.name ?: from?.name ?: id
            "$id($name):${from?.activity ?: "new"}->${to?.activity ?: "absent"}"
        }
    }
    val beforeTasks = before.tasks.associateBy(LocalTeamWaitTaskState::id)
    val afterTasks = after.tasks.associateBy(LocalTeamWaitTaskState::id)
    val taskChanges = (beforeTasks.keys + afterTasks.keys).toSortedSet().mapNotNull { id ->
        val from = beforeTasks[id]
        val to = afterTasks[id]
        if (from == to) null else {
            val previousStatus = from?.status ?: "new"
            val currentStatus = to?.status ?: "absent"
            "$id:$previousStatus->$currentStatus" +
                (to?.let { "@r${it.revision},owner=${it.ownerId ?: "none"}" } ?: "")
        }
    }
    return buildString {
        append("Team 状态已变化：event_seq=").append(after.eventSequence)
        append(" | from_seq=").append(before.eventSequence)
        append(" | member_changes=")
        append(if (changed.isEmpty()) "none" else changed.joinToString(";"))
        append(" | member_count=").append(after.members.size)
        append(" | task_changes=")
        append(if (taskChanges.isEmpty()) "none" else taskChanges.joinToString(";"))
        // This is an observation only; idle does not mean completed work or verified result.
        append(" | task_completion=unverified")
    }
}
