package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.SessionSummary
import com.labteto.dshmobile.core.wire.dto.WorkspaceView
import com.labteto.dshmobile.core.wire.dto.SessionProjectionsBlock
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the host-wide session/workspace index mirrored by [SessionStore].
 *
 * This type is deliberately state-only: callers keep synchronization and side effects outside,
 * while this class owns the maps, ordering rules and pending-interaction folding that used to make
 * SessionStore carry list/index concerns alongside transcript streaming and RPC orchestration.
 */
internal class SessionIndexState {
    private val sessionRows = LinkedHashMap<String, SessionRow>()
    private val runningBySession = HashMap<String, Boolean>()
    private val titleBySession = HashMap<String, String>()
    private val workspaceRows = LinkedHashMap<String, WorkspaceRow>()
    private val workspaceOrder = ArrayList<String>()
    private val pendingKinds = HashMap<String, MutableSet<String>>()
    private var archived = emptySet<String>()

    fun session(sessionId: String): SessionRow? = sessionRows[sessionId]

    fun running(sessionId: String): Boolean? = runningBySession[sessionId]

    fun archivedIds(): Set<String> = archived

    fun initialSnapshot(): Triple<List<SessionRow>, List<WorkspaceRow>, Set<String>> =
        Triple(sessionRows.values.toList(), orderedWorkspaces(), archived)

    fun renderSessions(): List<SessionRow> =
        sessionRows.values.map { row ->
            row.copy(pendingInteraction = pendingInteractionOf(pendingKinds[row.sessionId]))
        }

    fun orderedWorkspaces(): List<WorkspaceRow> =
        workspaceOrder.mapNotNull { workspaceRows[it] } +
            workspaceRows.values.filter { it.workspaceId !in workspaceOrder }

    fun replaceSessions(items: List<SessionSummary>) {
        sessionRows.clear()
        for (item in items) {
            val title = titleBySession[item.sessionId]
                ?: extractTitle(item.projections)?.also { titleBySession[item.sessionId] = it }
            runningBySession.putIfAbsent(item.sessionId, item.running)
            sessionRows[item.sessionId] = item.toRow(
                title = title,
                running = runningBySession[item.sessionId] ?: item.running,
            )
        }
    }

    fun addSession(item: SessionSummary) {
        val existing = sessionRows[item.sessionId]
        val title = titleBySession[item.sessionId]
        val row = existing?.copy(
            title = title ?: existing.title,
            blank = item.blank,
            parentSessionId = item.parentSessionId,
            origin = item.origin,
            cwd = item.cwd,
            agentPreset = item.agentPreset,
        ) ?: item.toRow(
            title = title,
            running = runningBySession[item.sessionId] ?: item.running,
        )
        if (existing == null) {
            val copy = LinkedHashMap<String, SessionRow>(sessionRows.size + 1)
            copy[item.sessionId] = row
            copy.putAll(sessionRows)
            sessionRows.clear()
            sessionRows.putAll(copy)
        } else {
            sessionRows[item.sessionId] = row
        }
    }

    fun removeSession(sessionId: String) {
        sessionRows.remove(sessionId)
        pendingKinds.remove(sessionId)
        runningBySession.remove(sessionId)
    }

    fun setRunning(sessionId: String, running: Boolean) {
        runningBySession[sessionId] = running
        sessionRows[sessionId]?.let {
            if (it.running != running) sessionRows[sessionId] = it.copy(running = running)
        }
    }

    fun setUpdatedAt(sessionId: String, updatedAt: Long) {
        val row = sessionRows[sessionId] ?: return
        sessionRows[sessionId] = row.copy(updatedAt = updatedAt)
    }

    fun setBlank(sessionId: String, blank: Boolean) {
        sessionRows[sessionId]?.let {
            if (it.blank != blank) sessionRows[sessionId] = it.copy(blank = blank)
        }
    }

    fun setTitle(sessionId: String, title: String) {
        titleBySession[sessionId] = title
        sessionRows[sessionId]?.let {
            if (it.title != title) sessionRows[sessionId] = it.copy(title = title)
        }
    }

    fun replaceWorkspaceBaseline(
        workspaces: List<WorkspaceView>,
        workspaceIds: List<String>,
        archivedSessionIds: List<String>,
    ) {
        workspaceRows.clear()
        workspaceOrder.clear()
        for (workspace in workspaces) workspaceRows[workspace.workspaceId] = workspace.toRow()
        workspaceOrder.addAll(workspaceIds.ifEmpty { workspaces.map { it.workspaceId } })
        archived = archivedSessionIds.toSet()
    }

    fun upsertWorkspace(workspace: WorkspaceView) {
        if (!workspaceRows.containsKey(workspace.workspaceId)) workspaceOrder.add(workspace.workspaceId)
        workspaceRows[workspace.workspaceId] = workspace.toRow()
    }

    fun removeWorkspace(workspaceId: String) {
        workspaceRows.remove(workspaceId)
        workspaceOrder.remove(workspaceId)
    }

    fun setWorkspaceOrder(ids: List<String>) {
        workspaceOrder.clear()
        workspaceOrder.addAll(ids)
    }

    fun setArchived(ids: List<String>): Set<String> {
        archived = ids.toSet()
        return archived
    }

    fun reusableBlankSession(workspaceId: String): String? =
        workspaceRows[workspaceId]?.sessionIds
            ?.mapNotNull { sessionRows[it] }
            ?.firstOrNull { it.blank && it.sessionId !in archived && it.origin != "subagent" }
            ?.sessionId

    fun addPending(sessionId: String, kind: String) {
        pendingKinds.getOrPut(sessionId) { LinkedHashSet() }.add(kind)
    }

    fun removePending(sessionId: String, kind: String) {
        pendingKinds[sessionId]?.remove(kind)
        if (pendingKinds[sessionId].isNullOrEmpty()) pendingKinds.remove(sessionId)
    }

    private fun SessionSummary.toRow(title: String?, running: Boolean): SessionRow = SessionRow(
        sessionId = sessionId,
        title = title,
        running = running,
        blank = blank,
        parentSessionId = parentSessionId,
        origin = origin,
        cwd = cwd,
        agentPreset = agentPreset,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    private fun WorkspaceView.toRow(): WorkspaceRow = WorkspaceRow(
        workspaceId = workspaceId,
        path = path,
        title = title,
        sessionIds = sessionIds,
        updatedAtEpoch = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
    )

    private fun extractTitle(block: SessionProjectionsBlock?): String? {
        val value = block?.values?.get("title") ?: return null
        return when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> value["title"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun pendingInteractionOf(kinds: Set<String>?): String? {
        if (kinds.isNullOrEmpty()) return null
        return when {
            "question" in kinds -> "question"
            "plan-review" in kinds -> "plan-review"
            "approval" in kinds -> "approval"
            else -> null
        }
    }
}
