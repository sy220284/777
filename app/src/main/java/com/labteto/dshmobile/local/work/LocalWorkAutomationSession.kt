package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import java.util.UUID

/** Work-owned identity admission, committed before a Worker receives the session ID. */
internal suspend fun resolveLocalWorkAutomationSession(
    preferredSessionId: String?,
    prompt: String,
    coordinator: LocalSessionCoordinator,
    hasEvents: (String) -> Boolean,
    recoverInterrupted: Boolean = false,
): LocalHarnessSession {
    preferredSessionId?.takeIf(String::isNotBlank)?.let { id ->
        val existing = coordinator.read(id)
        if (existing != null) {
            require(existing.usageMode == LocalUsageMode.WORK) { "目标会话不属于工作模式" }
            // Pending snapshots are process-local; execution identity must survive cold restart.
            if (!coordinator.hasDurableSnapshot(id)) coordinator.writeNow(existing)
            return existing
        }
        if (recoverInterrupted || hasEvents(id)) {
            throw com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException(
                "原工作会话快照缺失，须先核实已有执行记录；已停止创建新会话以避免重复副作用", id,
            )
        }
    }

    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    val session = LocalHarnessSession(
        id = id,
        title = prompt.lineSequence().firstOrNull()?.trim()?.take(36)
            ?.takeIf(String::isNotBlank)
            ?: "自动化任务",
        updatedAt = now,
        usageMode = LocalUsageMode.WORK,
        conversationMode = LocalConversationMode.INDEPENDENT,
        lineageId = id,
        projectId = null,
    )
    coordinator.writeNow(session)
    return session

}
