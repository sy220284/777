package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobInfo

data class LocalAgentTeamUiState(
    val members: List<LocalAgentTeamMemberUiState> = emptyList(),
    val tasks: List<LocalAgentTeamTaskUiState> = emptyList(),
    val pendingMessageCount: Int = 0,
    val failure: String? = null,
) {
    val visible: Boolean
        get() = members.isNotEmpty() || tasks.isNotEmpty() || failure != null
    val runningMemberCount: Int
        get() = members.count { it.activity == "running" }
    val provisioningMemberCount: Int
        get() = members.count { it.phase == "provisioning" }
    val completedMemberCount: Int
        get() = members.count { it.activity == "completed" && !it.awaitingReview }
    val failedMemberCount: Int
        get() = members.count { it.phase == "failed" || it.activity == "failed" }
    val completedTaskCount: Int
        get() = tasks.count { it.status == "completed" }
    val blockedTaskCount: Int
        get() = tasks.count {
            it.status == "pending" && !it.ready && it.blockedByTitles.isNotEmpty()
        }

    fun withJobs(jobs: List<LocalJobInfo>): LocalAgentTeamUiState {
        if (members.isEmpty()) return this
        val byId = jobs.associateBy(LocalJobInfo::id)
        return copy(
            members = members.map { member ->
                val jobStatus = byId[member.jobId]?.status
                val activity = when {
                    member.phase == "failed" -> "failed"
                    member.phase == "provisioning" -> "provisioning"
                    jobStatus != null -> jobStatus
                    else -> member.activity
                }
                member.copy(activity = activity)
            },
        )
    }
}

data class LocalAgentTeamMemberUiState(
    val id: String,
    val jobId: String,
    val name: String,
    val description: String,
    val phase: String,
    val activity: String,
    val currentTask: String? = null,
    val progressPercent: Int = 0,
    val resultMessageCount: Int = 0,
    val pendingMessageCount: Int = 0,
    val error: String? = null,
) {
    val awaitingReview: Boolean
        get() = currentTask != null && resultMessageCount > 0 && activity in setOf("dormant", "completed")
}

data class LocalAgentTeamTaskUiState(
    val id: String,
    val subject: String,
    val description: String,
    val status: String,
    val ownerName: String? = null,
    val blockedByTitles: List<String> = emptyList(),
    val ready: Boolean = false,
    val writeConflict: Boolean = false,
)

/** Work-owned runtime state. Session persistence remains a separate projection boundary. */
data class LocalWorkState(
    val plan: List<String> = emptyList(),
    val todos: List<LocalTodoItem> = emptyList(),
    val goal: LocalGoal? = null,
    val planMode: Boolean = false,
    val jobs: List<LocalJobInfo> = emptyList(),
    val team: LocalAgentTeamUiState = LocalAgentTeamUiState(),
    val workflowProgress: LocalWorkflowProgress? = null,
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
    val deviceApprovalLease: Boolean = false,
)
