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
internal data class LocalTeamWaitSnapshot(
    val eventSequence: Long,
    val members: List<LocalTeamWaitMemberState>,
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
    return buildString {
        append("Team 状态已变化：event_seq=").append(after.eventSequence)
        append(" | from_seq=").append(before.eventSequence)
        append(" | member_changes=")
        append(if (changed.isEmpty()) "none" else changed.joinToString(";"))
        append(" | member_count=").append(after.members.size)
        // This is an observation only; idle does not mean completed work or verified result.
        append(" | task_completion=unverified")
    }
}
