package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind

/** Read-only UI facts; model attachment paths and execution handles stay with the queue owner. */
internal data class LocalQueuedUserPreview(val id: String, val text: String)

internal fun AgentInputQueue.userPreviews(): List<LocalQueuedUserPreview> =
    snapshot().filter { it.kind == QueuedAgentInputKind.USER && it.id.isNotBlank() }
        .map { LocalQueuedUserPreview(it.id, it.memoryInput.ifBlank { "附件消息" }.take(4_096)) }
