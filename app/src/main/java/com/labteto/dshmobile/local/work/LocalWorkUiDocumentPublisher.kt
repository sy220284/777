package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalUiDocumentSessionStore
import com.labteto.dshmobile.local.uiBindingIdentity
import com.labteto.dshmobile.uicontract.UiDocumentBudget
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.UiDocumentSource
import com.labteto.dshmobile.uicontract.UiDocumentNode
import com.labteto.dshmobile.uicontract.UiDocumentBinding
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
        val records = documents.read(binding.sessionId, setOf(sourceMessageId), budget, scenes)
        // Work progress has its own identity, independent of user/model cards on this message.
        val existing = records.firstOrNull { it.document.documentId == docId }
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
        val history = binding.eventLog.pageBeforeChronological(limit = 120)
        val toolRows = history.asSequence().filter { it.type == "tool/result" }.toList().takeLast(24)
            .map { event ->
                val data = event.data
                val name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)
                val failed = data["is_error"]?.jsonPrimitive?.booleanOrNull
                val code = data["error_code"]?.jsonPrimitive?.contentOrNull.orEmpty().take(100)
                listOf(name, when (failed) {
                    true -> "执行报错"
                    false -> "工具已返回（未核验）"
                    null -> "返回状态未知"
                }, code)
            }.toList()
        val nodes = listOf(
            UiDocumentNode.BoundText("work_phase", "实时执行阶段", UiDocumentBinding.EXECUTION_PHASE),
            UiDocumentNode.Table("work_events",
                listOf("工具", "真实回执", "错误代码"), toolRows),
            UiDocumentNode.Text("work_warning",
                "此处仅表示工具事件已经记录；实际文件有效性、完成质量与审批需单独确认。"),
        )
        val revision = previous?.revision?.let { Math.addExact(it, 1L) } ?: 0L
        val document = UiDocument(
            1, docId,
            source, revision, "Work 实时活动", nodes, emptyList(),
            "工具事件已记录；当前结果未经独立核验。",
        )
        documents.publish(
            document, UiDocumentProvenance(null, "", false, UiDocumentOrigin.LOCAL),
            pendingApprovalId = null, expected = previous, budget = budget,
            knownScenes = scenes,
        )
    }
}
