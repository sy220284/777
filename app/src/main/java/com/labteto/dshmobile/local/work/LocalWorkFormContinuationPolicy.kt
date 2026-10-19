package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val WORK_REPLY_FORM_WAIT_EVENT = "work/reply-form-awaiting-submission"

/**
 * Read the authoritative Session event stream, not a transient UI flag. A form blocks
 * automated follow-through until a later user-owned send enters this session. Tool
 * receipts, model replies and autonomous goal continuations never release the block.
 */
internal fun hasOutstandingWorkReplyForm(log: LocalSessionEventLog, goal: LocalGoal): Boolean {
    if (goal.id.isBlank()) return false
    val waiting = log.latestMatching(setOf(WORK_REPLY_FORM_WAIT_EVENT)) { data ->
        (data["goal_id"] as? JsonPrimitive)?.contentOrNull == goal.id &&
            (data["goal_revision"] as? JsonPrimitive)?.longOrNull == goal.revision
    } ?: return false
    val submitted = log.latestMatching(setOf("user/message", LOCAL_AGENT_INBOX_EVENT_TYPE)) { data ->
        if ("action" !in data) return@latestMatching true // real user/message event
        val action = (data["action"] as? JsonPrimitive)?.contentOrNull
        action == "queued" && ((data["affected"] as? JsonArray)?.any { item ->
            val payload = item as? JsonObject
            (payload?.get("kind") as? JsonPrimitive)?.contentOrNull == "USER"
        } == true)
    }
    return submitted == null || submitted.sequence <= waiting.sequence
}

/** A published form is a user-turn boundary, not permission for autonomous next turns. */
internal fun shouldAutoContinueWorkGoalAfterForm(
    disposition: LocalWorkGoalDisposition?,
    awaitingSubmission: Boolean,
): Boolean = !awaitingSubmission &&
    disposition in setOf(
        LocalWorkGoalDisposition.NEEDS_EXECUTION,
        LocalWorkGoalDisposition.READY_FOR_SETTLEMENT,
    )
