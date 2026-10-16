package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.SessionRecovery
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocument
import com.labteto.dshmobile.uicontract.UiDocumentProvenance
import com.labteto.dshmobile.uicontract.UiDocumentOrigin
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Work Owner publishes trusted progress without another model request, second task store,
 * invented completion facts, or renderer-held write authority.
 * Source/version arbitration belongs to the shared Session document event ledger.
 */
@Singleton
internal class LocalWorkUiDocumentPublisher @Inject constructor(
    private val documents: LocalUiDocumentSessionStore,
    private val runtime: LocalRuntimeStateStore,
) {
    private val budget = UiDocumentBudget(512, 16, 262_144)
    private val scenes = emptySet<UiSceneId>()

    fun onToolResult(binding: LocalWorkRunBinding, originatingMessageId: String?) {
        val snapshot = binding.state.value
        // The authenticated Work turn already captured its source message before execution.
        // A long tool-only transcript may evict that user message from the in-memory window.
        val sourceMessageId = originatingMessageId?.takeIf(String::isNotBlank)
            ?: snapshot.messages.lastOrNull { it.role == "user" }?.id
            ?: return
        // Only the current session's actual identity can grant live actions or bindings.
        val identity = synchronized(runtime.foregroundRunHandle.lock) {
            val current = runtime.state.value
            if (!runtime.sessionTransitioning && current.sessionId == binding.sessionId &&
                current.usageMode == LocalUsageMode.WORK) runtime.uiBindingIdentity(current)
            else null
        }
        val docId = "work-live:${sourceMessageId}"
        // A stable Work card ID never overwrites user or model cards on the same message.
        val existing = documents.latestDocument(
            binding.sessionId, sourceMessageId, docId, budget, scenes)
        if (existing?.recoveryNotice != null) return
        val previous = existing?.document
        // Preserve the source provenance across foreground/background transitions.
        val source = previous?.source ?: UiDocumentSource(
            sessionId = binding.sessionId,
            messageId = sourceMessageId,
            contextGeneration = identity?.contextGeneration ?: 0L,
            profileId = identity?.profileId ?: snapshot.modelState.modelSelection.activeProfileId,
            projectId = identity?.projectId ?: snapshot.projectId,
            workMode = true,
        )
        // Tool events from other turns in the SAME session must never appear on this card.
        // The original source message ID is emitted together with each durable Work event.
        // Read a bounded newest-first window, then restore chronological row order.
        val window = binding.eventLog.pageBeforeNewestFirst(limit = 256)
        fun isCurrentTurn(event: com.labteto.dshmobile.local.session.LocalSessionEventLog.Event): Boolean =
            event.data["source_message_id"]?.jsonPrimitive?.contentOrNull == sourceMessageId
        // Reconcile the durable started/result pair, keyed by tool call ID.
        // A pending call must not be presented as a successful result, and a result
        // without a visible start remains a result rather than being fabricated away.
        val toolFacts = LinkedHashMap<String, List<String>>()
        window.asReversed().forEach { event ->
            if (!isCurrentTurn(event) || event.type !in setOf("tool/call", "tool/result"))
                return@forEach
            val data = event.data
            val callId = data["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: return@forEach
            val name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)
            val row = if (event.type == "tool/call") {
                listOf(name, "已记录工具调用；等待实际回执", "", "")
            } else {
                val failed = data["is_error"]?.jsonPrimitive?.booleanOrNull
                val code = data["error_code"]?.jsonPrimitive?.contentOrNull.orEmpty().take(100)
                val artifact = data["artifact_path"]?.jsonPrimitive?.contentOrNull
                    ?.take(150).orEmpty()
                val artifactHash = data["artifact_sha256"]?.jsonPrimitive?.contentOrNull
                    ?.take(64).orEmpty()
                listOf(name, when {
                    code == SessionRecovery.TOOL_OUTCOME_UNKNOWN ->
                        "执行结果未知；请核实副作用，勿直接重试"
                    code == SessionRecovery.TOOL_NOT_STARTED -> "中断前尚未执行"
                    code == SessionRecovery.TOOL_READ_RESULT_UNKNOWN ->
                        "只读调用已中断，未取得完整结果"
                    failed == true -> "执行报错"
                    failed == false -> "工具已返回（未核验）"
                    else -> "返回状态未知"
                }, code, if (artifact.isNotEmpty() && artifactHash.isNotEmpty())
                    "$artifact (事件时 SHA256：$artifactHash，未复核文件当前版本)" else "")
            }
            toolFacts[callId] = row
        }
        val toolRows = toolFacts.values.toList().takeLast(24)
        val endReason = window.firstOrNull { it.type in setOf("turn/start", "turn/end") && isCurrentTurn(it) }
            ?.takeIf { it.type == "turn/end" }
            ?.data?.get("reason")?.jsonPrimitive?.contentOrNull
        val endLabel = when (endReason) {
            "completed" -> "本轮已记录结束；输出质量尚未验收"
            "step_limit" -> "本轮因步数上限暂停；进度已保留"
            "error" -> "本轮执行失败；保留已完成工具回执"
            "aborted" -> "本轮已终止"
            "budget_exhausted_continuation", "stream_interrupted_continuation" ->
                "本轮中断后进入自动续跑；等待后续真实结果"
            null -> "本轮还未记录结束事件"
            else -> "本轮结束原因：${endReason.take(80)}"
        }
        val nodes = buildList {
            addAll(listOf(
            UiDocumentNode.Text("work_turn_status", endLabel),
            UiDocumentNode.Text("work_scope",
                "最近 256 条事件窗口内，本轮最多 24 个工具调用与回执；不代表全部历史结果。"),
            UiDocumentNode.Table("work_events",
                listOf("工具", "真实回执", "错误代码", "事件时文件版本（未经复核）"), toolRows),
            UiDocumentNode.Text("work_warning",
                "此处仅表示工具事件已经记录；实际文件有效性、完成质量与审批需单独确认。"),
            ))
            // Native previews use the actual bounded durable result, never model-invented artifacts.
            val results = window.filter { it.type == "tool/result" && isCurrentTurn(it) }
                .distinctBy { it.data["id"]?.jsonPrimitive?.contentOrNull }.take(6).asReversed()
            results.forEachIndexed { index, event ->
                val content = event.data["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (content.isNotBlank()) add(UiDocumentNode.Disclosure("work_output_$index",
                    "${event.data["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)} · 实际回执预览（未核验）",
                    listOf(UiDocumentNode.Text("work_output_text_$index",
                        com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(content, 1600)))))
            }
            window.firstOrNull { it.type == "assistant/message" && isCurrentTurn(it) }
                ?.data?.get("content")?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.contentOrNull?.takeIf(String::isNotBlank)?.let { content ->
                    add(UiDocumentNode.Disclosure("work_findings", "本轮最新阶段输出（模型陈述，未核验）",
                        listOf(UiDocumentNode.Text("work_findings_text",
                            com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair(content, 4000)))))
                }
        }
        val revision = previous?.revision?.let { Math.addExact(it, 1L) } ?: 0L
        val document = UiDocument(
            1, docId,
            source, revision, "Work 渐进成果", nodes, emptyList(),
            "工具回执与阶段输出已保存；当前结果未经独立核验。",
        )
        documents.publish(
            document, UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL),
            pendingApprovalId = null, expected = previous, budget = budget,
            knownScenes = scenes,
        )
    }
}
