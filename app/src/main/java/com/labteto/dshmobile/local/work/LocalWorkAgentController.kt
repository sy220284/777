package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException


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
