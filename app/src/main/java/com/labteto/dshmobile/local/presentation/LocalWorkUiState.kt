package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.work.LocalAgentTeamUiState
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.local.work.LocalWorkflowProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Work-surface projection.
 *
 * This is intentionally separate from [LocalHarnessShellState]: live work status changes often,
 * while the drawer/shell should stay asleep. The projection contains only fields rendered by the
 * compact Work status strip and Run Center.
 */
data class LocalWorkUiState(
    val sessionId: String = "",
    val running: Boolean = false,
    val workflowProgress: LocalWorkflowProgress? = null,
    val goal: LocalGoal? = null,
    val todos: List<LocalTodoItem> = emptyList(),
    val activeAgents: Int = 0,
    val jobs: List<LocalJobInfo> = emptyList(),
    val team: LocalAgentTeamUiState = LocalAgentTeamUiState(),
    val pendingApproval: LocalApproval? = null,
    val pendingQuestion: LocalQuestion? = null,
    val plan: List<String> = emptyList(),
    val queuedInputCount: Int = 0,
    val maxAgents: Int = 1,
    val activeTerminals: Int = 0,
    val maxTerminals: Int = 1,
    val activeVirtualDisplays: Int = 0,
    val maxVirtualDisplays: Int = 1,
    val activeLanguageServers: Int = 0,
    val maxLanguageServers: Int = 1,
    val resourcePressure: String = "low",
    val contextChars: Int = 0,
    val contextBudgetChars: Int = 0,
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val handoffSummary: String? = null,
)

internal fun LocalHarnessState.toWorkUiState(): LocalWorkUiState =
    LocalWorkUiState(
        sessionId = sessionId,
        running = kernel.running,
        workflowProgress = work.workflowProgress,
        goal = work.goal,
        todos = work.todos,
        activeAgents = kernel.resources.activeAgents,
        jobs = work.jobs,
        team = work.team.withJobs(work.jobs),
        pendingApproval = work.pendingApproval,
        pendingQuestion = work.pendingQuestion,
        plan = work.plan,
        queuedInputCount = kernel.queuedInputCount,
        maxAgents = kernel.resources.maxAgents,
        activeTerminals = kernel.resources.activeTerminals,
        maxTerminals = kernel.resources.maxTerminals,
        activeVirtualDisplays = kernel.resources.activeVirtualDisplays,
        maxVirtualDisplays = kernel.resources.maxVirtualDisplays,
        activeLanguageServers = kernel.resources.activeLanguageServers,
        maxLanguageServers = kernel.resources.maxLanguageServers,
        resourcePressure = kernel.resources.resourcePressure,
        contextChars = kernel.contextChars,
        contextBudgetChars = kernel.contextBudgetChars,
        conversationMode = conversationMode,
        handoffSummary = handoffSummary,
    )

internal fun StateFlow<LocalHarnessState>.projectWorkState(
    scope: CoroutineScope,
): StateFlow<LocalWorkUiState> =
    map { it.toWorkUiState() }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = value.toWorkUiState(),
        )
