package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.observability.AppLog
import android.content.Context
import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.LocalToolApprovalRuntime
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.project.ProjectContextPort
import com.labteto.dshmobile.local.interaction.LocalApprovalPreferences
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.localImageRequestBudgetForModelConcurrency
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.shouldAutoApproveTool
import com.labteto.dshmobile.local.tools.LocalToolPolicy
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.string
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


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
