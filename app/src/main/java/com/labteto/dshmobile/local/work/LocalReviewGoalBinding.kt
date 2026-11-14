package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A review belongs to the exact active Goal revision which requested that review type. */
internal data class LocalReviewGoalBinding(val goalId: String, val startSequence: Long)

internal fun currentReviewGoalBinding(log: LocalSessionEventLog, type: String): LocalReviewGoalBinding? {
    require(type == "code" || type == "security")
    val goalEvent = log.latestMatching(setOf("goal/state")) { true } ?: return null
    val data = goalEvent.data
    if ((data["status"] as? JsonPrimitive)?.contentOrNull != "active") return null
    val requested = (data["reviewRequirements"] as? JsonArray)
        ?.any { (it as? JsonPrimitive)?.contentOrNull == type } == true
    if (!requested) return null
    val id = (data["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    val started = (data["startedAtSequence"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it >= 0L } ?: goalEvent.sequence
    return LocalReviewGoalBinding(id, started)
}
