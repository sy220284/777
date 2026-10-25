package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.AGENT_MESSAGE_SCAN_PAGE
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_AGENT_MESSAGE_LIMIT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_RENDERED_MESSAGE_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.WAIT_POLL_MS
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Codex agent/control/mailbox.rs separates mailbox observation from agent activation.
 * This read-only result stream observes persisted model checkpoints; it does not consume the
 * incoming Job Inbox nor transform delivery receipts into completion claims.
 * Claude teammateMailbox.ts similarly separates durable teammate messages from execution.
 */
internal class LocalTeamResultMailbox(
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val project: (String) -> LocalTeamProjection,
    private val verification: LocalTeamVerificationController,
) {
    internal data class LocalTeamAgentMessagePage(
        val messages: List<LocalTeamAgentMessageSnapshot>,
        val nextCursor: Long,
        val hasMore: Boolean,
    )

    internal fun agentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): List<LocalTeamAgentMessageSnapshot> = scanAgentMessages(sessionId, targetName, limit, afterSequence).messages

    internal fun scanAgentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): LocalTeamAgentMessagePage {
        val state = project(sessionId)
        val membersByJob = state.members.associateBy(LocalTeamMemberSnapshot::jobId)
        val target = targetName?.trim()?.takeIf(String::isNotBlank)
        if (target != null) {
            require(state.members.any { it.name == target }) {
                "TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + target
            }
        }
        val bounded = limit.coerceIn(1, MAX_AGENT_MESSAGE_LIMIT)
        val log = eventLogFor(sessionId)
        val latest = log.latestSequence()

        fun decode(event: LocalSessionEventLog.Event): LocalTeamAgentMessageSnapshot? {
            if (event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT) return null
            val jobId = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val member = membersByJob[jobId] ?: return null
            if (target != null && member.name != target) return null
            if (!verification.isFirstResultCheckpoint(log, event)) return null
            val content = event.data["terminal_output"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return null
            return LocalTeamAgentMessageSnapshot(
                id = verification.resultIdentity(event),
                sequence = event.sequence,
                createdAt = event.createdAt,
                memberId = member.id,
                memberName = member.name,
                content = content,
                step = event.data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
            )
        }

        if (afterSequence >= 0L) {
            val result = ArrayList<LocalTeamAgentMessageSnapshot>(bounded)
            var cursor = afterSequence
            var pages = 0
            while (result.size < bounded && cursor < latest && pages++ < 32) {
                val page = log.pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
                if (page.isEmpty()) break
                for (event in page.sortedBy(LocalSessionEventLog.Event::sequence)) {
                    if (event.sequence > latest) break
                    cursor = maxOf(cursor, event.sequence)
                    decode(event)?.let { message -> if (result.none { it.id == message.id }) result.add(message) }
                    if (result.size >= bounded) break
                }
                if (page.size < AGENT_MESSAGE_SCAN_PAGE) break
            }
            return LocalTeamAgentMessagePage(result, cursor, cursor < latest)
        }

        val result = ArrayList<LocalTeamAgentMessageSnapshot>(bounded)
        var before = Long.MAX_VALUE
        repeat(MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES) {
            if (result.size >= bounded) return@repeat
            val page = log.pageBeforeNewestFirst(before, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) return@repeat
            page.asSequence()
                .sortedByDescending(LocalSessionEventLog.Event::sequence)
                .forEach { event ->
                    if (result.size >= bounded) return@forEach
                    decode(event)?.let { message -> if (result.none { it.id == message.id }) result.add(message) }
                }
            before = page.minOf(LocalSessionEventLog.Event::sequence)
            if (page.size < AGENT_MESSAGE_SCAN_PAGE) return@repeat
        }
        val messages = result.distinctBy(LocalTeamAgentMessageSnapshot::id)
            .sortedBy(LocalTeamAgentMessageSnapshot::sequence).takeLast(bounded)
        return LocalTeamAgentMessagePage(messages, messages.lastOrNull()?.sequence ?: latest, false)
    }

    fun renderAgentMessages(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        limit: Int,
    ): String {
        val page = scanAgentMessages(
            sessionId = sessionId,
            targetName = targetName,
            limit = limit,
            afterSequence = afterSequence,
        )
        val messages = page.messages
        if (messages.isEmpty()) {
            val label = if (targetName == null) {
                "暂无助手消息"
            } else {
                targetName.trim() + " 暂无助手消息"
            }
            return label + "\nnext_cursor=" + page.nextCursor + "\nhas_more=" + page.hasMore
        }
        val nextCursor = page.nextCursor
        return messages.joinToString("\n\n") { message ->
            "[" + message.id + "] " + message.memberName +
                " sequence=" + message.sequence +
                " step=" + message.step + "\n" +
                message.content.take(MAX_RENDERED_MESSAGE_CHARS)
        } + "\n\nnext_cursor=" + nextCursor + "\nhas_more=" + page.hasMore
    }

    private data class AgentMessageForwardScan(
        val message: LocalTeamAgentMessageSnapshot?,
        val scannedThroughSequence: Long,
    )

    private fun nextAgentMessageAfter(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
    ): AgentMessageForwardScan {
        val state = project(sessionId)
        val membersByJob = state.members.associateBy(LocalTeamMemberSnapshot::jobId)
        val target = targetName?.trim()?.takeIf(String::isNotBlank)
        if (target != null) {
            require(state.members.any { it.name == target }) {
                "TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + target
            }
        }

        var cursor = afterSequence
        repeat(16) {
            val page = eventLogFor(sessionId).pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) {
                return AgentMessageForwardScan(null, cursor)
            }
            page.sortedBy(LocalSessionEventLog.Event::sequence).forEach { event ->
                cursor = maxOf(cursor, event.sequence)
                if (event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT) return@forEach
                val jobId = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val member = membersByJob[jobId] ?: return@forEach
                if (target != null && member.name != target) return@forEach
                if (!verification.isFirstResultCheckpoint(eventLogFor(sessionId), event)) return@forEach
                val content = event.data["terminal_output"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                return AgentMessageForwardScan(
                    message = LocalTeamAgentMessageSnapshot(
                        id = verification.resultIdentity(event),
                        sequence = event.sequence,
                        createdAt = event.createdAt,
                        memberId = member.id,
                        memberName = member.name,
                        content = content,
                        step = event.data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
                    ),
                    scannedThroughSequence = cursor,
                )
            }
            if (page.size < AGENT_MESSAGE_SCAN_PAGE) {
                return AgentMessageForwardScan(null, cursor)
            }
        }
        return AgentMessageForwardScan(null, cursor)
    }

    suspend fun waitForMessage(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        timeoutMs: Int,
    ): String {
        var scanAfter = afterSequence

        fun scanNext(): LocalTeamAgentMessageSnapshot? {
            val scan = nextAgentMessageAfter(
                sessionId = sessionId,
                targetName = targetName,
                afterSequence = scanAfter,
            )
            scanAfter = maxOf(scanAfter, scan.scannedThroughSequence)
            return scan.message
        }

        scanNext()?.let { next ->
            return "[" + next.id + "] " + next.memberName +
                " sequence=" + next.sequence +
                " step=" + next.step + "\n" +
                next.content.take(MAX_RENDERED_MESSAGE_CHARS) +
                "\nnext_cursor=" + next.sequence
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(WAIT_POLL_MS)
            scanNext()?.let { next ->
                return "[" + next.id + "] " + next.memberName +
                    " sequence=" + next.sequence +
                    " step=" + next.step + "\n" +
                    next.content.take(MAX_RENDERED_MESSAGE_CHARS) +
                    "\nnext_cursor=" + next.sequence
            }
        }
        return "等待助手消息超时：" + timeoutMs + "ms"
    }

}
