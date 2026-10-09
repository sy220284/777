package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobInfo

data class LocalAgentTeamUiState(
    val members: List<LocalAgentTeamMemberUiState> = emptyList(),
    val tasks: List<LocalAgentTeamTaskUiState> = emptyList(),
    val pendingMessageCount: Int = 0,
    val failure: String? = null,
    val activities: List<LocalAgentTeamActivityUiState> = emptyList(),
    val rebuilding: Boolean = false,
) {
    val visible: Boolean
        get() = rebuilding || members.isNotEmpty() || tasks.isNotEmpty() || failure != null
    val runningMemberCount: Int
        get() = members.count { it.activity == "running" }
    val stoppingMemberCount: Int
        get() = members.count { it.activity == "stopping" }
    val provisioningMemberCount: Int
        get() = members.count { it.phase == "provisioning" }
    val completedMemberCount: Int
        get() = members.count { it.activity == "completed" && !it.awaitingReview }
    val failedMemberCount: Int
        get() = members.count { it.phase == "failed" || it.activity == "failed" }
    val completedTaskCount: Int
        get() = tasks.count { it.status == "completed" }
    val returnedMemberCount: Int
        get() = members.count { it.resultMessageCount > 0 || it.activity == "completed" }
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
                // Only an admitted active member derives activity from its Child Job.
                val activity = if (member.phase == "active") jobStatus ?: member.activity else member.phase
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
    val hasCurrentTaskResult: Boolean = false,
    val displayName: String = "",
) {
    val friendlyName: String
        get() = displayName.ifBlank { teamMemberFriendlyName(name) }
    val canReceiveMessage: Boolean
        get() = phase == "active"

    val awaitingReview: Boolean
        get() = hasCurrentTaskResult && activity in setOf("dormant", "completed")
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

/** Bounded display projection of durable team events; never an execution owner. */
@kotlinx.serialization.Serializable
data class LocalAgentTeamActivityUiState(
    val sequence: Long,
    val kind: String,
    val title: String,
    val status: String,
    val memberName: String? = null,
)

internal fun teamMemberFriendlyName(name: String): String = when {
    name.contains("web", true) || name.contains("network", true) -> "联网验证员"
    name.contains("capability", true) || name.contains("tool", true) -> "能力检查员"
    name.contains("workspace", true) || name.contains("audit", true) -> "工作区审计员"
    name.contains("code", true) || name.contains("developer", true) -> "开发工程师"
    name.contains("test", true) || name.contains("qa", true) -> "测试工程师"
    name.contains("research", true) -> "研究助手"
    name.contains("review", true) -> "审查助手"
    else -> "协作助手" + ((name.hashCode().ushr(1) % 90) + 10)
}
