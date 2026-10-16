package com.labteto.dshmobile.uicontract

/** Reliable typed inputs only. Free text is preserved verbatim, never interpreted as facts. */
sealed interface UiLocalDocumentData {
    data class Status(val approval: UiApprovalSnapshot?) : UiLocalDocumentData
    data class ListItems(val items: List<String>) : UiLocalDocumentData
    /** Receives Owner-issued typed data; never guess a table from untrusted free text. */
    data class TableData(val title: String, val headers: List<String>, val rows: List<List<String>>) : UiLocalDocumentData
    /** Graph opt-in is restricted to typed Owner snapshots, not free-text inference. */
    data class ChartData(val title: String, val unit: String, val points: List<UiChartPoint>) : UiLocalDocumentData
    /** User-selected Work references; existence and matching hash are not proof of a pass. */
    data class EvidenceLinks(
        val links: List<com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink>,
    ) : UiLocalDocumentData
    data class ReplyForm(val initial: String) : UiLocalDocumentData
    /** Session-scoped result identities come from Media Owner, never model-authored URLs. */
    data class MediaResults(val assets: List<UiDocumentNode.Media>) : UiLocalDocumentData
    /** Owner-selected tool result. JSON fields are content, never action or layout instructions. */
    data class StructuredResult(val title: String, val encoded: String) : UiLocalDocumentData
}
enum class UiLocalDocumentTemplate {
    STATUS, MESSAGE, REPLY_FORM, FACTS_TABLE, WORK_TOOL_ACTIVITY, WORK_TOOL_COUNTS, WORK_EVIDENCE, WORK_TOOL_RESULT, MEDIA_RESULTS,
}
data class GenerateLocalUiDocumentCommand(val identity: UiSessionIdentity, val workMode: Boolean,
    val messageId: String, val template: UiLocalDocumentTemplate)

/** Pure layout builder: no network, model, store, permission grant or pack-specific visual types. */
// FEATURE_TREE: {"id":"ui.document.local_builder","path":"第一层｜产品功能与界面 / 应用外壳与统一交互 / 会话通用交互 / 本地交互文档模板生成（迁移中）","entry":"LocalUiDocumentBuilder","owner":"SHELL","test":"ui-contract/src/test/kotlin/com/labteto/dshmobile/uicontract/LocalUiDocumentBuilderTest.kt#freeTextCannotAcquireActionsOrBecomeACompletionFact"}
object LocalUiDocumentBuilder {
    fun build(documentId: String, source: UiDocumentSource, revision: Long, data: UiLocalDocumentData,
        budget: UiDocumentBudget, knownScenes: Set<UiSceneId>): Result<UiDocument> = runCatching {
        val actions = mutableListOf<UiDocumentAction>()
        val nodes: List<UiDocumentNode>
        val title: String
        val fallback: String
        when (data) {
            is UiLocalDocumentData.Status -> {
                title = "会话状态"
                fallback = "状态来自当前会话；历史卡片的实时绑定可能不可用。"
                nodes = buildList {
                    add(UiDocumentNode.BoundText("phase", "执行状态", UiDocumentBinding.EXECUTION_PHASE))
                    add(UiDocumentNode.BoundText("queue", "排队输入", UiDocumentBinding.QUEUED_INPUT_COUNT))
                    add(UiDocumentNode.BoundText("error", "当前错误", UiDocumentBinding.CURRENT_ERROR))
                    data.approval?.let { approval ->
                        add(UiDocumentNode.Text("approval", "${approval.toolName}\n${approval.summary}\n${approval.arguments}"))
                        actions += UiDocumentAction.AnswerApproval("approve", approval.pendingId, true)
                        actions += UiDocumentAction.AnswerApproval("deny", approval.pendingId, false)
                        add(UiDocumentNode.Button("approve_button", "批准当前请求", "approve"))
                        add(UiDocumentNode.Button("deny_button", "拒绝当前请求", "deny"))
                    }
                }
            }
            is UiLocalDocumentData.ListItems -> {
                title = "已有内容"
                fallback = data.items.joinToString("\n")
                nodes = data.items.mapIndexed { index, value -> UiDocumentNode.Text("item_$index", value) }
            }
            is UiLocalDocumentData.TableData -> {
                title = data.title
                fallback = buildString {
                    append(data.headers.joinToString(" | "))
                    data.rows.forEach { row -> append('\n'); append(row.joinToString(" | ")) }
                }
                nodes = listOf(UiDocumentNode.Table("typed_table", data.headers, data.rows))
            }
            is UiLocalDocumentData.ChartData -> {
                title = data.title
                fallback = data.points.joinToString("\n") { "${it.label}: ${it.value} ${data.unit}".trim() }
                nodes = listOf(UiDocumentNode.BarChart("typed_chart", data.title, data.unit, data.points))
            }
            is UiLocalDocumentData.EvidenceLinks -> {
                title = "需求来源关联（未核验）"
                val rows = data.links.take(12).map { link ->
                    listOf(link.requirement, link.artifactPath,
                        when {
                            link.versionNow == null -> "当前文件版本未知/不可访问"
                            link.versionNow != link.versionAtLink -> "文件已变化，旧关联失效"
                            else -> "当前文件版本与关联时相同（结果未核验）"
                        }, link.sourceCallId)
                }
                fallback = "仅显示前 12 条关联；文件版本状态不证明需求已经完成或核验通过。"
                nodes = listOf(UiDocumentNode.Text("evidence_notice", fallback),
                    UiDocumentNode.Table("evidence_sources",
                        listOf("关联需求", "来源文件", "版本状态", "工具调用 ID"), rows))
            }
            is UiLocalDocumentData.ReplyForm -> {
                title = "回复草稿"
                fallback = data.initial
                nodes = listOf(UiDocumentNode.Field("reply", "回复内容", data.initial, true, true),
                    UiDocumentNode.Button("prepare", "放入草稿，确认后发送", "prepare_reply"))
                actions += UiDocumentAction.SendUserInput("prepare_reply", "", listOf("reply"))
            }
            is UiLocalDocumentData.MediaResults -> {
                title = "当前会话媒体成果"
                fallback = if (data.assets.isEmpty()) "当前会话还没有可用的媒体结果" else
                    data.assets.joinToString("\n") { it.label }
                nodes = if (data.assets.isEmpty()) listOf(
                    UiDocumentNode.Text("no_media", fallback)
                ) else data.assets
            }
            is UiLocalDocumentData.StructuredResult -> {
                title = data.title
                fallback = data.encoded
                nodes = LocalStructuredUiDataAdapter.nodes(data.encoded, budget)
            }
        }
        val document = UiDocument(1, documentId, source, revision, title, nodes, actions, fallback)
        val validation = document.validate(budget, knownScenes, (data as? UiLocalDocumentData.Status)?.approval?.pendingId)
        require(validation.valid) { validation.problems.joinToString(",") }
        document
    }
}
