package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun kotlinx.serialization.json.JsonObject.reviewValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A read-only projection from the existing Session EventLog.
 * An independently reviewed snapshot cannot satisfy a different Goal revision or a
 * subsequent review request. A newer Work-owned file mutation also invalidates approval.
 * The UI still re-reads the actual Git diff before any approval is recorded.
 */
internal fun localWorkOutstandingRequiredReviews(
    log: LocalSessionEventLog,
    goal: LocalGoal,
): List<String> = buildList {
    for (type in goal.reviewRequirements) {
        require(type in setOf("code", "security")) { "目标包含无效的独立审查类型" }
        val prefix = "work/" + type + "-review"
        val start = log.latestMatching(setOf("$prefix-start")) { fields ->
            fields.reviewValue("goal_id") == goal.id &&
                fields.reviewValue("goal_revision") == goal.revision.toString()
        }?.takeIf { it.sequence > goal.startedAtSequence }
        if (start == null) {
            add(if (type == "security") "目标要求的独立安全审查尚未开始" else "目标要求的独立代码审查尚未开始")
            continue
        }
        val id = start.data.reviewValue("id")
        val digest = start.data.reviewValue("digest")
        val result = log.latestMatching(setOf("$prefix-result")) { fields ->
            fields.reviewValue("id") == id && fields.reviewValue("digest") == digest &&
                fields.reviewValue("goal_id") == goal.id &&
                fields.reviewValue("goal_revision") == goal.revision.toString()
        }?.takeIf { it.sequence > start.sequence }
        if (result == null) {
            add(if (type == "security") "独立安全审查尚无完整结果" else "独立代码审查尚无完整结果")
            continue
        }
        if (type == "security" && result.data.reviewValue("verdict") != "allow") {
            add("独立安全审查发现风险，需要修复后对新版本重新审查")
            continue
        }
        val approvalType = if (type == "security") "$prefix-approved" else "$prefix-accepted"
        val accepted = log.latestMatching(setOf(approvalType)) { fields ->
            fields.reviewValue("id") == id &&
                fields.reviewValue("digest") == digest &&
                fields.reviewValue("sequence") == result.sequence.toString() &&
                fields.reviewValue("goal_id") == goal.id &&
                fields.reviewValue("goal_revision") == goal.revision.toString()
        }?.takeIf { it.sequence > result.sequence }
        if (accepted == null) {
            add(if (type == "security") "当前安全审查尚待人工确认" else "当前代码审查尚待人工接纳")
            continue
        }
        // A legitimate mutation after acknowledgement needs a new review. Only
        // settled successful Work tool results count; read-only tools do not.
        val newerMutation = log.latestMatching(setOf("tool/result")) { fields ->
            fields.reviewValue("goal_id") == goal.id &&
                fields.reviewValue("is_error") == "false" &&
                fields.reviewValue("side_effect") != "none"
        }?.takeIf { it.sequence > accepted.sequence }
        if (newerMutation != null) {
            add("独立" + if (type == "security") "安全" else "代码" +
                "审查之后已有新的修改，请重新审查当前版本")
        }
    }
}
