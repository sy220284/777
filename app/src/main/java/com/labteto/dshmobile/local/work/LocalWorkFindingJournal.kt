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

    /** tool_call records the public gateway as its name; evidence uses the actual target. */
    private fun JsonObject.sourceToolName(): String =
        if (text("name") == "tool_call") (get("arguments") as? JsonObject)?.text("name").orEmpty()
        else text("name")

    /** Surface exact settled IDs on an invalid citation, without weakening source verification. */
    private fun recentSourceHint(log: LocalSessionEventLog, goalId: String): String {
        val declarations = log.eventsOfTypesAfter(-1L, setOf("tool/call"))
            .associateBy { it.sequence }
        val eligible = log.eventsOfTypesAfter(-1L, setOf("tool/result"))
            .asReversed().mapNotNull { result ->
                if (result.data.text("is_error") != "false" ||
                    result.data.text("error_code").isNotBlank()) return@mapNotNull null
                val sequence = (result.data["tool_declared_sequence"] as? JsonPrimitive)
                    ?.longOrNull ?: return@mapNotNull null
                val declaration = declarations[sequence] ?: return@mapNotNull null
                val id = result.data.text("id")
                val name = declaration.data.sourceToolName()
                if (id.isBlank() || name.isBlank() || name == "work_finding" ||
                    declaration.sequence >= result.sequence ||
                    declaration.data.text("id") != id ||
                    declaration.data.text("goal_id") != goalId) return@mapNotNull null
                "${id}（${name}）"
            }.distinct().take(5)
        return if (eligible.isEmpty()) {
            "当前工作目标下没有可关联的成功工具回执；请先完成真实工具调用并确认目标一致"
        } else "当前目标可用的来源回执 ID：" + eligible.joinToString("；")
    }

    fun execute(
        log: LocalSessionEventLog,
        goalId: String,
        args: JsonObject,
        projectId: String = "",
        sourceMessageId: String? = null,
    ): String =
        log.withWriterLease {
            when (val action = args.text("action")) {
                "list" -> list(log, goalId)
                "retract" -> {
                    // The source-backed record remains in the append-only event log.
                    // A tombstone removes it from all current task projections.
                    val id = args.text("finding_id").trim()
                    require(id.isNotBlank()) { "撤回发现必须提供 finding_id" }
                    val previous = log.latestMatching(setOf(EVENT)) {
                        it.text("finding_id") == id && it.text("goal_id") == goalId
                    } ?: error("找不到当前目标内的调查发现")
                    require(previous.data.text("status") != "retracted") { "该发现已经撤回" }
                    val reason = args.text("summary").trim()
                    require(reason.length <= 500) { "撤回说明最多 500 字" }
                    log.append(EVENT, buildJsonObject {
                        put("finding_id", id)
                        put("goal_id", goalId)
                        put("project_id", previous.data.text("project_id").ifBlank { projectId })
                        put("source_message_id", sourceMessageId.orEmpty())
                        put("action", "retract")
                        put("summary", previous.data.text("summary"))
                        put("status", "retracted")
                        put("source_call_id", previous.data.text("source_call_id"))
                        previous.data["source_sequence"]?.let { put("source_sequence", it) }
                        put("next_action", "")
                        put("reason", reason)
                    })
                    "已撤回调查发现 ID=$id；保留原始事件审计，后续工作上下文与重规划不再使用该发现"
                }
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
                        "没有找到成功且已结算的来源工具回执；${recentSourceHint(log, goalId)}"
                    }
                    val declaration = log.latestMatching(setOf("tool/call")) { data ->
                        data.text("id") == sourceCallId && data.text("goal_id") == goalId
                    }
                    val sourceDeclaration = (source.data["tool_declared_sequence"] as? JsonPrimitive)?.longOrNull
                    require(declaration != null && declaration.sequence < source.sequence &&
                        declaration.data.sourceToolName() != "work_finding" &&
                        sourceDeclaration == declaration.sequence) {
                        "来源工具不属于当前目标或尚未结算；${recentSourceHint(log, goalId)}"
                    }
                    val findingId = if (action == "record") {
                        require(args.text("finding_id").isBlank()) { "新发现不接受历史 ID" }
                        "finding-" + UUID.randomUUID()
                    } else {
                        val id = args.text("finding_id")
                        val previous = log.latestMatching(setOf(EVENT)) {
                            it.text("finding_id") == id && it.text("goal_id") == goalId
                        }
                        require(id.isNotBlank() && previous != null &&
                            previous.data.text("status") != "retracted") {
                            "找不到当前目标内可修订的发现记录，已撤回的发现需重新记录"
                        }
                        id
                    }
                    val nextAction = args.text("next_action").trim().take(300)
                    log.append(EVENT, buildJsonObject {
                        put("finding_id", findingId)
                        put("goal_id", goalId)
                        put("project_id", projectId)
                        put("source_message_id", sourceMessageId.orEmpty())
                        put("action", action)
                        put("summary", summary)
                        put("status", status)
                        put("source_call_id", sourceCallId)
                        put("source_sequence", source.sequence)
                        put("next_action", nextAction)
                    })
                    "已记录调查线索 ID=$findingId；状态=$status；发现=$summary；来源回执=$sourceCallId" +
                        if (nextAction.isBlank()) "" else "；后续调查=$nextAction"
                }
                else -> error("未知发现操作：$action")
            }
        }

    fun supportedForReplan(log: LocalSessionEventLog, goalId: String, findingId: String): Boolean {
        if (goalId.isBlank() || findingId.isBlank()) return false
        val latest = log.latestMatching(setOf(EVENT)) {
            it.text("finding_id") == findingId && it.text("goal_id") == goalId
        } ?: return false
        return latest.data.text("status") == "supported"
    }

    private fun list(log: LocalSessionEventLog, goalId: String): String {
        val current = linkedMapOf<String, JsonObject>()
        log.forEachOfTypesAfter(-1L, setOf(EVENT)) { event ->
            if (event.data.text("goal_id") == goalId) {
                current[event.data.text("finding_id")] = event.data
            }
        }
        if (current.isEmpty()) return "当前目标没有调查线索"
        val active = current.values.filter { it.text("status") != "retracted" }.takeLast(20)
        if (active.isEmpty()) return "当前目标没有调查线索"
        return active.joinToString("\n") {
            "ID=${it.text("finding_id")}；状态=${it.text("status")}；" +
                "发现=${it.text("summary")}；来源=${it.text("source_call_id")}；" +
                "下一步=${it.text("next_action")}"
        }
    }
}
