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
    private val agentTeams: LocalAgentTeamRuntime,
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
            } else emptyList())
    }
    override fun readOutput(identity: com.labteto.dshmobile.uicontract.UiSessionIdentity, memberId: String, expectedJobId: String): com.labteto.dshmobile.uicontract.UiWorkTeamResult = try {
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            if (snapshot().identity != identity) com.labteto.dshmobile.uicontract.UiWorkTeamResult(
                com.labteto.dshmobile.uicontract.UiCommandReceipt.Rejected(com.labteto.dshmobile.uicontract.UiCommandRejection.STALE_IDENTITY), "会话已改变")
            else com.labteto.dshmobile.uicontract.UiWorkTeamResult(com.labteto.dshmobile.uicontract.UiCommandReceipt.Accepted,
                agentTeams.outputForUi(identity.sessionId, memberId, expectedJobId))
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
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.SendMessage -> agentTeams.sendUiMessage(sessionId, command.memberId, command.text, command.expectedJobId)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopMember -> agentTeams.interruptUiMember(sessionId, command.memberId, command.expectedJobId)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.StopTeam -> agentTeams.interruptAllUi(sessionId, command.expectedJobIds)
                    is com.labteto.dshmobile.uicontract.WorkTeamUiCommand.ReassignTask ->
                        agentTeams.reassignUiTask(sessionId, command.taskId, command.expectedRevision, command.newMemberId)
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

}
