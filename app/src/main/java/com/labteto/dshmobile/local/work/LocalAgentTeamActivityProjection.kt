package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo

internal enum class LocalTeamMemberActivity {
    CREATED,
    PROVISIONING,
    RUNNING,
    WAITING,
    BLOCKED,
    DORMANT,
    DISABLED,
    DISMISSED,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

internal data class LocalTeamMemberActivityView(
    val memberId: String,
    val memberName: String,
    val activity: LocalTeamMemberActivity,
    val currentTaskId: String? = null,
    val currentTask: String? = null,
    val pendingMessageCount: Int = 0,
    val error: String? = null,
)

internal data class LocalAgentTeamActivityProjection(
    val members: List<LocalTeamMemberActivityView> = emptyList(),
    val completedTasks: Int = 0,
    val totalTasks: Int = 0,
)

/**
 * Work-owned read-only projection over Team EventLog facts plus the shared Job snapshot.
 *
 * Rich activity is never persisted back into Team state. The durable member phase remains
 * provisioning -> active | failed; running/dormant/completed are derived from the Child Job.
 */
internal fun projectLocalAgentTeamActivity(
    team: LocalTeamProjection,
    jobs: List<LocalJobInfo>,
): LocalAgentTeamActivityProjection {
    val visibleTasks = team.tasks.filter { it.status != LocalTeamTaskStatus.DELETED }
    val tasksById = visibleTasks.associateBy(LocalTeamTaskSnapshot::id)
    val jobsById = jobs.associateBy(LocalJobInfo::id)

    fun blockersCompleted(task: LocalTeamTaskSnapshot): Boolean =
        task.blockedBy.all { blockerId ->
            tasksById[blockerId]?.status == LocalTeamTaskStatus.COMPLETED
        }

    val members = team.members.map { member ->
        val ownedTask = visibleTasks.firstOrNull {
            it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
        } ?: visibleTasks.firstOrNull {
            it.ownerId == member.id && it.status == LocalTeamTaskStatus.PENDING
        }
        val jobStatus = jobsById[member.jobId]?.status?.lowercase()
        val activity = when (member.phase) {
            LocalTeamMemberPhase.CREATED -> LocalTeamMemberActivity.CREATED
            LocalTeamMemberPhase.PROVISIONING -> LocalTeamMemberActivity.PROVISIONING
            LocalTeamMemberPhase.ACTIVE -> when {
                ownedTask != null && !blockersCompleted(ownedTask) -> LocalTeamMemberActivity.BLOCKED
                jobStatus == "running" || jobStatus == "stopping" -> LocalTeamMemberActivity.RUNNING
                jobStatus == "dormant" -> LocalTeamMemberActivity.DORMANT
                jobStatus == "completed" -> LocalTeamMemberActivity.COMPLETED
                jobStatus == "interrupted" -> LocalTeamMemberActivity.INTERRUPTED
                jobStatus in setOf("failed", "cancelled", "killed") -> LocalTeamMemberActivity.FAILED
                else -> LocalTeamMemberActivity.WAITING
            }
            LocalTeamMemberPhase.DISABLED -> LocalTeamMemberActivity.DISABLED
            LocalTeamMemberPhase.DISMISSED -> LocalTeamMemberActivity.DISMISSED
            LocalTeamMemberPhase.FAILED -> LocalTeamMemberActivity.FAILED
        }
        LocalTeamMemberActivityView(
            memberId = member.id,
            memberName = member.name,
            activity = activity,
            currentTaskId = ownedTask?.id,
            currentTask = ownedTask?.subject,
            pendingMessageCount =
                team.pendingMessages.count { it.targetId == member.id } +
                    (jobsById[member.jobId]?.pendingMessageCount ?: 0),
            error = member.error,
        )
    }
    return LocalAgentTeamActivityProjection(
        members = members,
        completedTasks = visibleTasks.count { it.status == LocalTeamTaskStatus.COMPLETED },
        totalTasks = visibleTasks.size,
    )
}
