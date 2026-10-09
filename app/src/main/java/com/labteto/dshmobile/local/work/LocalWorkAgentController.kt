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


/** Work-owned UI command policy; no composition callback is involved in command execution. */
internal class LocalWorkAgentController(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val agentTeams: LocalAgentTeamRuntime,
    private val teamProjection: LocalWorkTeamUiProjection,
) : LocalWorkAgentUiPort {
    override suspend fun startBackgroundAgent(task: String): LocalWorkAgentUiResult =
        launchBackgroundAgent(task, instructions = "")

    override suspend fun startResearchAgent(task: String): LocalWorkAgentUiResult =
        launchBackgroundAgent(task, instructions = LocalResearchAgentPreset.instructions)

    private suspend fun launchBackgroundAgent(task: String, instructions: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val clean = task.trim()
            if (clean.isEmpty()) {
                return@withContext LocalWorkAgentUiResult(false, "请输入要交给后台子代理的任务")
            }
            val sessionId = runtimeStateStore.currentSessionId
            val snapshot = runtimeStateStore.state.value
            if (snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK) {
                return@withContext LocalWorkAgentUiResult(false, "请先进入当前工作会话再启动后台子代理")
            }
            try {
                val result = persistentJobs.startReadonlySubagentResult(
                    task = clean,
                    instructions = instructions,
                    model = LocalWorkerModelRouter.resolve(null, snapshot),
                    maxSteps = snapshot.subagentMaxSteps,
                    virtualScreen = false,
                    sessionId = sessionId,
                    boundState = snapshot,
                    historySnapshot = runtimeStateStore.foregroundRunHandle.modelHistory::snapshot,
                )
                LocalWorkAgentUiResult(
                    accepted = result.accepted,
                    message = result.message,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "后台子代理启动失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun sendMessage(agentId: String, message: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val clean = message.trim()
            if (clean.isEmpty()) {
                return@withContext LocalWorkAgentUiResult(false, "请输入要追加给子代理的消息")
            }
            try {
                val admission = persistentJobs.sendInput(
                    agentId = agentId,
                    input = QueuedAgentInput(
                        id = "ui-msg-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
                        content = clean,
                        memoryInput = clean,
                    ),
                    sessionId = runtimeStateStore.currentSessionId,
                )
                LocalWorkAgentUiResult(
                    accepted = admission.accepted,
                    message = admission.message,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "消息发送失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun sendTeamMessage(
        memberId: String,
        message: String,
    ): LocalWorkAgentUiResult = withContext(Dispatchers.IO) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            return@withContext LocalWorkAgentUiResult(false, "请输入要发送给助手的消息")
        }
        val sessionId = runtimeStateStore.currentSessionId
        try {
            val result = agentTeams.sendUiMessage(sessionId, memberId, clean)
            teamProjection.publish(sessionId)
            LocalWorkAgentUiResult(true, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalWorkAgentUiResult(
                false,
                "助手消息发送失败：" + (error.message ?: error::class.java.simpleName),
            )
        }
    }

    override suspend fun stopTeamMember(memberId: String): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val sessionId = runtimeStateStore.currentSessionId
            try {
                val result = agentTeams.interruptUiMember(sessionId, memberId)
                teamProjection.publish(sessionId)
                LocalWorkAgentUiResult(true, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "停止助手失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

    override suspend fun stopTeam(): LocalWorkAgentUiResult =
        withContext(Dispatchers.IO) {
            val sessionId = runtimeStateStore.currentSessionId
            try {
                val result = agentTeams.interruptAllUi(sessionId)
                teamProjection.publish(sessionId)
                LocalWorkAgentUiResult(true, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LocalWorkAgentUiResult(
                    false,
                    "停止 Agent 集群失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
        }

}
