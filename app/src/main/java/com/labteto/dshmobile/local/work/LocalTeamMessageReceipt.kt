package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Event-sourced delivery receipt. No new inbox store and no claim of model comprehension.
 * A durable child checkpoint proves that the message entered child model history, but not
 * that the provider processed it successfully or that the user's Goal was fulfilled.
 */
internal data class LocalTeamMessageReceipt(
    val id: String,
    val targetId: String?,
    val queuedSequence: Long?,
    val deliveredSequence: Long?,
    val discardedSequence: Long?,
    val historyCheckpointSequence: Long?,
    val historyWindowComplete: Boolean,
) {
    val status: String get() = when {
        queuedSequence == null -> if (historyWindowComplete) "not_found" else "unknown_history_window"
        discardedSequence != null -> "discarded"
        historyCheckpointSequence != null -> "checkpointed_in_model_history"
        deliveredSequence != null -> "accepted_by_inbox"
        queuedSequence != null -> "queued"
        historyWindowComplete -> "not_found"
        else -> "unknown_history_window"
    }

    fun render(): String = buildString {
        append("message_id=").append(id).append("\n")
        append("status=").append(status).append("\n")
        append("history_complete=").append(historyWindowComplete).append("\n")
        targetId?.let { append("target_id=").append(it).append("\n") }
        queuedSequence?.let { append("queued_sequence=").append(it).append("\n") }
        deliveredSequence?.let { append("delivered_sequence=").append(it).append("\n") }
        discardedSequence?.let { append("discarded_sequence=").append(it).append("\n") }
        historyCheckpointSequence?.let { append("history_checkpoint_sequence=").append(it).append("\n") }
        append("checkpointed means durably inserted in child model history; ")
        append("it does not establish provider consumption, task completion or user authorization")
    }
}

internal fun projectLocalTeamMessageReceipt(
    id: String,
    events: List<LocalSessionEventLog.Event>,
    historyWindowComplete: Boolean,
    sessionId: String,
): LocalTeamMessageReceipt {
    require(id.matches(Regex("team-msg-[a-zA-Z0-9_-]{1,64}"))) { "TEAM_MESSAGE_RECEIPT_ID_INVALID" }
    var queued: Long? = null
    var delivered: Long? = null
    var discarded: Long? = null
    var target: String? = null
    val checkpointJobs = mutableListOf<Pair<String, Long>>()
    for (event in events) {
        val data = event.data
        when (event.type) {
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED -> {
                if (data["teamId"]?.jsonPrimitive?.contentOrNull != sessionId) continue
                val envelope = data["message"] as? JsonObject ?: continue
                if (envelope["id"]?.jsonPrimitive?.contentOrNull != id) continue
                if (queued == null || event.sequence < queued) {
                    queued = event.sequence
                    target = envelope["targetId"]?.jsonPrimitive?.contentOrNull
                }
            }
            LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED -> {
                if (data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId &&
                    data["messageId"]?.jsonPrimitive?.contentOrNull == id &&
                    (delivered == null || event.sequence > delivered)
                ) delivered = event.sequence
            }
            LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED -> {
                if (data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId &&
                    data["messageId"]?.jsonPrimitive?.contentOrNull == id &&
                    (discarded == null || event.sequence > discarded)
                ) discarded = event.sequence
            }
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT -> {
                val claimed = data["claimed_message_ids"] as? JsonArray ?: continue
                if (claimed.any { it.jsonPrimitive.contentOrNull == id }) {
                    data["background_job_id"]?.jsonPrimitive?.contentOrNull?.let {
                        checkpointJobs += it to event.sequence
                    }
                }
            }
        }
    }
    val expectedJob = target?.takeIf { it.startsWith(LocalAgentTeamContract.TEAM_MEMBER_ID_PREFIX) }
        ?.let(LocalAgentTeamEventCodec::teamJobId)
    val queuedAt = queued
    val checkpoint = checkpointJobs.asSequence()
        .filter { it.first == expectedJob && (queuedAt == null || it.second >= queuedAt) }
        .maxOfOrNull { it.second }
    // Do not conclude delivery from a checkpoint alone if the queued event was outside the scan.
    return LocalTeamMessageReceipt(
        id = id,
        targetId = target,
        queuedSequence = queued,
        deliveredSequence = delivered,
        discardedSequence = discarded,
        historyCheckpointSequence = if (queued != null) checkpoint else null,
        historyWindowComplete = historyWindowComplete,
    )
}

/** Bounded historical scan. Unknown/partial is explicit; never infer that an unseen delivery failed. */
internal fun readLocalTeamMessageReceipt(
    log: LocalSessionEventLog,
    sessionId: String,
    id: String,
    maxEvents: Int = 12_800,
): LocalTeamMessageReceipt {
    require(maxEvents in 1..25_600) { "TEAM_MESSAGE_RECEIPT_SCAN_LIMIT_INVALID" }
    require(id.matches(Regex("team-msg-[a-zA-Z0-9_-]{1,64}"))) { "TEAM_MESSAGE_RECEIPT_ID_INVALID" }
    val seen = ArrayList<LocalSessionEventLog.Event>()
    var cursor = Long.MAX_VALUE
    var complete = false
    while (seen.size < maxEvents) {
        val pageSize = minOf(160, maxEvents - seen.size)
        val page = log.pageBeforeNewestFirst(cursor, pageSize)
        if (page.isEmpty()) { complete = true; break }
        check(page.last().sequence < cursor) { "TEAM_RECEIPT_EVENT_SCAN_STALLED" }
        seen.addAll(page)
        cursor = page.last().sequence
        if (page.size < pageSize || cursor <= 0L) { complete = true; break }
        val queuedFound = page.any { event ->
            event.type == LocalAgentTeamContract.TEAM_MESSAGE_QUEUED &&
                event.data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId &&
                (event.data["message"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == id
        }
        if (queuedFound) { complete = true; break }
    }
    return projectLocalTeamMessageReceipt(id, seen, complete, sessionId)
}
