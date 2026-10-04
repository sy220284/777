package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One wall-clock budget shared by Automation queueing, ownership acquisition and execution. */
internal class LocalAutomationTimeoutBudget(
    timeoutMillis: Long,
    maxMillis: Long,
) {
    private val deadlineNanos =
        System.nanoTime() + timeoutMillis.coerceIn(5_000L, maxMillis) * 1_000_000L

    fun remainingMillis(): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)

    suspend fun acquireSession(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
    ): LocalSessionRuntimeLease = withTimeout(remainingMillis()) {
        LocalSessionRuntimeRegistry.acquire(sessionId, kind)
    }
}

internal fun requireAutomationChatSession(session: LocalHarnessSession?): LocalHarnessSession {
    val current = session ?: error("定时互动绑定的聊天已不存在")
    require(current.usageMode == LocalUsageMode.CHAT) { "定时互动只能绑定聊天模式会话" }
    require(!current.groupChat.enabled) { "群聊暂不支持定时角色互动" }
    return current
}

internal fun requireAutomationWorkSession(session: LocalHarnessSession?): LocalHarnessSession {
    val current = session ?: error("后台任务会话已不存在")
    require(current.usageMode == LocalUsageMode.WORK) { "后台任务会话已不在工作模式" }
    return current
}

private val AUTOMATION_USER_ACTIVITY_EVENTS =
    setOf("user/message", LOCAL_AGENT_INBOX_EVENT_TYPE)

internal fun LocalSessionEventLog.latestAutomationUserActivitySequence(): Long? =
    latestOf(AUTOMATION_USER_ACTIVITY_EVENTS)?.sequence

internal class LocalAutomationChatOwnership(
    private val sessionLease: LocalSessionRuntimeLease,
    val visibleTurnOwned: Boolean,
    private val targetSessionId: String,
    private val automationJob: Job?,
    private val releaseVisibleTurn: (String, Job?) -> Unit,
) : AutoCloseable {
    override fun close() {
        // Release in reverse acquisition order. releaseVisibleTurn may wake queued foreground work.
        sessionLease.close()
        if (visibleTurnOwned) releaseVisibleTurn(targetSessionId, automationJob)
    }
}

internal suspend fun acquireAutomationChatOwnership(
    targetSessionId: String,
    automationJob: Job?,
    budget: LocalAutomationTimeoutBudget,
    acquireVisibleTurn: suspend (String, Job?) -> Boolean,
    releaseVisibleTurn: (String, Job?) -> Unit,
): LocalAutomationChatOwnership {
    val visibleTurnOwned = withTimeout(budget.remainingMillis()) {
        acquireVisibleTurn(targetSessionId, automationJob)
    }
    val sessionLease = try {
        budget.acquireSession(targetSessionId, LocalSessionRuntimeKind.AUTOMATION_CHAT)
    } catch (error: Throwable) {
        if (visibleTurnOwned) releaseVisibleTurn(targetSessionId, automationJob)
        throw error
    }
    return LocalAutomationChatOwnership(
        sessionLease = sessionLease,
        visibleTurnOwned = visibleTurnOwned,
        targetSessionId = targetSessionId,
        automationJob = automationJob,
        releaseVisibleTurn = releaseVisibleTurn,
    )
}


internal fun rejectStaleAutomationProactiveReply(
    eventLog: LocalSessionEventLog,
    expectedUserActivitySequence: Long?,
    sessionId: String,
    personaId: String,
): LocalAutomationRunResult? {
    if (eventLog.latestAutomationUserActivitySequence() == expectedUserActivitySequence) return null
    val reason = "用户刚有新的互动，本轮主动消息已取消"
    eventLog.append("chat/proactive-skipped", buildJsonObject {
        put("reason", reason)
        put("automation", true)
        put("proactive", true)
        put("user_activity_during_generation", true)
        put("persona_id", personaId)
    })
    return LocalAutomationRunResult(
        sessionId = sessionId,
        output = reason,
        delivered = false,
        skipReason = reason,
    )
}
