package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only projection of the existing Agent Run ledger. Run checkpoints remain authoritative.
 * A partial history never masquerades as a complete agent tree or a verified running roster.
 */
internal data class LocalAgentTreeNode(
    val runId: String,
    val parentRunId: String?,
    val agentId: String?,
    val kind: String,
    val status: String,
    val lastSequence: Long,
)
internal data class LocalAgentTreeView(
    val nodes: List<LocalAgentTreeNode>,
    val partial: Boolean,
) {
    fun render(): String {
        val byId = nodes.associateBy(LocalAgentTreeNode::runId)
        fun depth(node: LocalAgentTreeNode): Int {
            var current = node
            var depth = 0
            val visited = mutableSetOf(node.runId)
            while (depth < 16) {
                val parent = current.parentRunId?.let(byId::get) ?: break
                if (!visited.add(parent.runId)) break
                depth++
                current = parent
            }
            return depth
        }
        val header = "agent_tree runs=${nodes.size} partial=$partial " +
            "(checkpoint view, not an active-process census)"
        if (nodes.isEmpty()) return "$header\n暂无本窗口内的 Agent Run 检查点"
        return buildString {
            append(header)
            nodes.sortedWith(compareBy<LocalAgentTreeNode>(::depth).thenBy { it.lastSequence }).forEach { node ->
                val parentMissing = node.parentRunId != null && node.parentRunId !in byId
                append("\n")
                append("  ".repeat(depth(node).coerceAtMost(16)))
                append("run=").append(node.runId)
                append(" kind=").append(node.kind)
                append(" status=").append(node.status)
                node.agentId?.let { append(" agent=").append(it) }
                node.parentRunId?.let {
                    append(" parent=").append(it)
                    if (parentMissing) append(" [parent_outside_window]")
                }
            }
        }
    }
}

internal fun projectLocalAgentTree(
    events: List<LocalSessionEventLog.Event>,
    maxNodes: Int = 100,
    historyIncomplete: Boolean = false,
): LocalAgentTreeView {
    require(maxNodes in 1..256)
    val latest = linkedMapOf<String, LocalAgentTreeNode>()
    val types = setOf(LOCAL_AGENT_RUN_CHECKPOINT_EVENT, LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT)
    for (event in events.sortedBy(LocalSessionEventLog.Event::sequence)) {
        if (event.type !in types) continue
        val data = event.data
        val runId = data["run_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
        latest[runId] = LocalAgentTreeNode(
            runId = runId,
            parentRunId = data["parent_run_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            agentId = data["agent_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            kind = data["run_kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = data["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lastSequence = event.sequence,
        )
    }
    val selected = latest.values.sortedByDescending(LocalAgentTreeNode::lastSequence).take(maxNodes)
    return LocalAgentTreeView(
        nodes = selected,
        partial = historyIncomplete || latest.size > maxNodes,
    )
}

internal fun readLocalAgentTree(
    log: LocalSessionEventLog,
    maxNodes: Int = 100,
    maxScannedEvents: Int = 6_400,
): LocalAgentTreeView {
    require(maxNodes in 1..256)
    require(maxScannedEvents in 1..12_800)
    val events = ArrayList<LocalSessionEventLog.Event>()
    var cursor = Long.MAX_VALUE
    var reachedStart = false
    while (events.size < maxScannedEvents) {
        val requestSize = minOf(160, maxScannedEvents - events.size)
        val page = log.pageBeforeNewestFirst(cursor, requestSize)
        if (page.isEmpty()) {
            reachedStart = true
            break
        }
        events.addAll(page)
        cursor = page.last().sequence
        if (page.size < requestSize || cursor <= 0L) {
            reachedStart = true
            break
        }
    }
    return projectLocalAgentTree(events, maxNodes, historyIncomplete = !reachedStart)
}
