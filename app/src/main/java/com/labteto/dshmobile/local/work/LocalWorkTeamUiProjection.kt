package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.coroutines.flow.update


/** Publishes derived team state to the owning Work binding or current visible Work surface. */
internal class LocalWorkTeamUiProjection(
    private val agentTeams: LocalAgentTeamRuntime,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    fun publish(sessionId: String) {
        val team = agentTeams.uiState(sessionId)
        workRunRegistry[sessionId]?.let { binding ->
            binding.workState.update { current -> current.copy(team = team) }
            workRunRegistry.mirrorVisible(binding)
            return
        }
        val visible = runtimeStateStore.state.value
        if (visible.sessionId == sessionId && visible.usageMode == LocalUsageMode.WORK) {
            runtimeStateStore.projection.projectVisibleWorkRun(
                sessionId = sessionId,
                snapshot = visible.copy(work = visible.work.copy(team = team)),
            )
        }
    }

}
