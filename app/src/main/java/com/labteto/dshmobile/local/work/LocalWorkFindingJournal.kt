package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Work-owned observation journal. Entries are durable Session events, not a second
 * memory database or another scheduler. A finding always cites a successful tool
 * receipt from the current Work goal; the author may revise or reject it later.
 *
 * "supported" describes an investigated hypothesis, never machine verification.
 */
internal object LocalWorkFindingJournal {
    private const val EVENT = "work/finding"
    private val STATUSES = setOf("hypothesis", "supported", "rejected")

    private fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun execute(log: LocalSessionEventLog, goalId: String, args: JsonObject): String =
        log.withWriterLease {
            when (val action = args.text("action")) {
                "list" -> list(log, goalId)
                "record", "revise" -> {
                    val summary = args.text("summary").trim()
                    require(summary.length in 8..500) { "发现说明须为 8—500 字" }
                    val status = args.text("status").ifBlank { "hypothesis" }
                    require(status in STATUSES) { "发现状态只允许 hypothesis、supported 或 rejected" }
                    val sourceCallId = args.text("source_call_id").trim()
                    require(sourceCallId.isNotBlank()) { "必须关联已完成的真实工具调用" }
                    val source = log.latestMatching(setOf("tool/result")) { it.text("id") == sourceCallId }
                    require(source != null && source.data.text("is_error") == "false" &&
                        source.data.text("error_code").isBlank()) {
                        "没有找到成功且已结算的来源工具回执"
                    }
                    val declaration = log.latestMatching(setOf("tool/call")) { data ->
                        data.text("id") == sourceCallId && data.text("goal_id") == goalId
                    }
                    val sourceDeclaration = (source.data["tool_declared_sequence"] as? JsonPrimitive)?.longOrNull
                    require(declaration != null && declaration.sequence < source.sequence &&
                        declaration.data.text("name") != "work_finding" &&
                        sourceDeclaration == declaration.sequence) {
                        "来源工具不属于当前目标或尚未结算"
                    }
                    val findingId = if (action == "record") {
                        require(args.text("finding_id").isBlank()) { "新发现不接受历史 ID" }
                        "finding-" + UUID.randomUUID()
                    } else {
                        val id = args.text("finding_id")
                        require(id.isNotBlank() && log.latestMatching(setOf(EVENT)) {
                            it.text("finding_id") == id && it.text("goal_id") == goalId
                        } != null) { "找不到当前目标内可修订的发现记录" }
                        id
                    }
                    val nextAction = args.text("next_action").trim().take(300)
                    log.append(EVENT, buildJsonObject {
                        put("finding_id", findingId)
                        put("goal_id", goalId)
                        put("action", action)
                        put("summary", summary)
                        put("status", status)
                        put("source_call_id", sourceCallId)
                        put("source_sequence", source.sequence)
                        put("next_action", nextAction)
                    })
                    "已记录调查线索 ID=$findingId；状态=$status；来源回执=$sourceCallId" +
                        if (nextAction.isBlank()) "" else "；后续调查=$nextAction"
                }
                else -> error("未知发现操作：$action")
            }
        }

    private fun list(log: LocalSessionEventLog, goalId: String): String {
        val current = linkedMapOf<String, JsonObject>()
        log.forEachOfTypesAfter(-1L, setOf(EVENT)) { event ->
            if (event.data.text("goal_id") == goalId) {
                current[event.data.text("finding_id")] = event.data
            }
        }
        if (current.isEmpty()) return "当前目标没有调查线索"
        return current.values.toList().takeLast(20).joinToString("\n") {
            "ID=${it.text("finding_id")}；状态=${it.text("status")}；" +
                "发现=${it.text("summary")}；来源=${it.text("source_call_id")}；" +
                "下一步=${it.text("next_action")}"
        }
    }
}
