package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.HandoffGoal
import com.labteto.dshmobile.harness.session.HandoffMessage
import com.labteto.dshmobile.harness.session.HandoffState
import com.labteto.dshmobile.harness.session.HandoffTodo
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.runtime.projectExecutionJobs

/**
 * WorkFeature projection used by shared Session lifecycle orchestration.
 *
 * Session owns transaction ordering; Work owns how Work state is initialized and how its durable
 * task state is projected into a continuation handoff.
 */
internal object LocalWorkSessionLifecyclePlanner {
    internal fun initialState(
        usageMode: LocalUsageMode,
        sessionId: String,
        jobs: List<LocalJobInfo>,
    ): LocalWorkState =
        LocalWorkState(
            jobs = projectExecutionJobs(usageMode, sessionId, jobs),
            deviceApprovalLease = false,
        )

    internal fun handoffState(
        work: LocalWorkState,
        messages: List<HandoffMessage>,
    ): HandoffState =
        HandoffState(
            goal = work.goal?.let { goal ->
                HandoffGoal(goal.status, goal.description)
            },
            plan = work.plan,
            todos = work.todos.map { todo ->
                HandoffTodo(todo.status, todo.content)
            },
            messages = messages,
        )
}
