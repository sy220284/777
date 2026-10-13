package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException


/** Work-owned UI command policy; no composition callback is involved in command execution. */
internal class LocalWorkAgentController(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val agentTeams: LocalAgentTeamRuntime,
    private val teamProjection: LocalWorkTeamUiProjection,
) : LocalWorkAgentUiPort {
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
