package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.labteto.dshmobile.local.uiBindingIdentity


/** Work-owned UI command policy; no composition callback is involved in command execution. */
internal class LocalWorkAgentController(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val agentControl: LocalAgentControlPort,
    private val teamProjection: LocalWorkTeamUiProjection,
) : com.labteto.dshmobile.uicontract.WorkTeamUiPort {
    override val snapshots = runtimeStateStore.state.map { snapshot() }.distinctUntilChanged()
    override fun snapshot(): com.labteto.dshmobile.uicontract.UiWorkTeamSnapshot = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
        val current = runtimeStateStore.state.value
        val active = current.usageMode == com.labteto.dshmobile.local.LocalUsageMode.WORK && !current.loading
        val team = current.work.team
        com.labteto.dshmobile.uicontract.UiWorkTeamSnapshot(
            runtimeStateStore.uiBindingIdentity(current).takeIf { active && !runtimeStateStore.sessionTransitioning },
            if (active) team.members.map { member -> com.labteto.dshmobile.uicontract.UiWorkTeamMember(
                member.id, member.jobId, member.friendlyName, member.phase, member.activity,
                member.currentTask, member.pendingMessageCount, member.error) } else emptyList(),
            active && team.rebuilding, team.failure.takeIf { active },
            if (active) team.tasks.map { task ->
                com.labteto.dshmobile.uicontract.UiWorkTeamTask(
                    task.id, task.revision, task.subject, task.status,
                    task.ownerName, task.blockedByTitles, task.ready)
            } else emptyList(),
            agents = if (active) agentControl.agents(current.sessionId).map { agent ->
                com.labteto.dshmobile.uicontract.UiWorkAgent(
                    agent.id, agent.label, agent.status, agent.continuable, agent.teamManaged,
                )
            } else emptyList())
    }
    override fun readOutput(identity: com.labteto.dshmobile.uicontract.UiSessionIdentity, memberId: String, expectedJobId: String): com.labteto.dshmobile.uicontract.UiWorkTeamResult = try {
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            if (snapshot().identity != identity) com.labteto.dshmobile.uicontract.UiWorkTeamResult(
                com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY), "会话已改变")
            else com.labteto.dshmobile.uicontract.UiWorkTeamResult(com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted,
                agentControl.teamReadOutput(identity.sessionId, memberId, expectedJobId))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { com.labteto.dshmobile.uicontract.UiWorkTeamResult(
        com.labteto.dshmobile.uicontract.UiCommandReceipt.Failed(failure.message ?: "助手输出不可读取"), failure.message ?: "助手输出不可读取") }

    override suspend fun submit(command: com.labteto.dshmobile.uicontract.WorkTeamUiCommand): com.labteto.dshmobile.uicontract.UiWorkTeamResult = withContext(Dispatchers.IO) {
        try {
            synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                val current = runtimeStateStore.state.value
                if (runtimeStateStore.sessionTransitioning || current.loading ||
                    current.usageMode != com.labteto.dshmobile.local.LocalUsageMode.WORK ||
                    runtimeStateStore.uiBindingIdentity(current) != command.identity)
                    return@synchronized com.labteto.dshmobile.uicontract.UiWorkTeamResult(
                        com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY), "会话已改变，请重新打开团队")
                val sessionId = command.identity.sessionId
                val message = when (command) {
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.SendMessage -> agentControl.teamSendMessage(sessionId, command.memberId, command.text, command.expectedJobId)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopMember -> agentControl.teamInterruptMember(sessionId, command.memberId, command.expectedJobId)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopTeam -> agentControl.teamInterruptAll(sessionId, command.expectedJobIds)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.ReassignTask ->
                        agentControl.teamReassignTask(sessionId, command.taskId, command.expectedRevision, command.newMemberId)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.InspectAgent -> {
                        requireStandaloneAgent(sessionId, command.agentId)
                        agentControl.inspect(sessionId, command.agentId)
                    }
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.SendAgentMessage -> {
                        requireStandaloneAgent(sessionId, command.agentId)
                        agentControl.send(sessionId, command.agentId, command.text)
                    }
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.InterruptAgent -> {
                        requireStandaloneAgent(sessionId, command.agentId)
                        agentControl.interrupt(sessionId, command.agentId)
                    }
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.CloseAgent -> {
                        requireStandaloneAgent(sessionId, command.agentId)
                        agentControl.close(sessionId, command.agentId)
                    }
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.ResumeAgent -> {
                        requireStandaloneAgent(sessionId, command.agentId)
                        agentControl.resume(sessionId, command.agentId)
                    }
                }
                val projectionWarning = try { teamProjection.publish(sessionId); "" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    com.labteto.dshmobile.observability.AppLog.failure("WorkTeamUi", "publish", failure)
                    "\n操作已接纳，显示更新失败：${failure.message}"
                }
                com.labteto.dshmobile.uicontract.UiWorkTeamResult(com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted, message + projectionWarning)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { com.labteto.dshmobile.uicontract.UiWorkTeamResult(
            com.labteto.dshmobile.uicontract.UiCommandReceipt.Failed(failure.message ?: "团队操作未完成"), failure.message ?: "团队操作未完成") }
    }

    /** UI may control only the exact current standalone Job; Team uses its membership contract. */
    private fun requireStandaloneAgent(sessionId: String, agentId: String) {
        require(agentControl.agents(sessionId).any { it.id == agentId && !it.teamManaged }) {
            "AGENT_TARGET_NOT_FOUND：助手已变更，请刷新状态"
        }
    }
}
