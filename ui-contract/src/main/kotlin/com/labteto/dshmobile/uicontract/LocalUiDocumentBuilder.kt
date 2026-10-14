package com.labteto.dshmobile.uicontract

/** Reliable typed inputs only. Free text is preserved verbatim, never interpreted as facts. */
sealed interface UiLocalDocumentData {
    data class Status(val approval: UiApprovalSnapshot?) : UiLocalDocumentData
    data class ListItems(val items: List<String>) : UiLocalDocumentData
    /** Receives Owner-issued typed data; never guess a table from untrusted free text. */
    data class TableData(val title: String, val headers: List<String>, val rows: List<List<String>>) : UiLocalDocumentData
    data class ReplyForm(val initial: String) : UiLocalDocumentData
}
enum class UiLocalDocumentTemplate { STATUS, MESSAGE, REPLY_FORM, FACTS_TABLE, WORK_TOOL_ACTIVITY }
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
            is UiLocalDocumentData.ReplyForm -> {
                title = "回复草稿"
                fallback = data.initial
                nodes = listOf(UiDocumentNode.Field("reply", "回复内容", data.initial, true, true),
                    UiDocumentNode.Button("prepare", "放入草稿，确认后发送", "prepare_reply"))
                actions += UiDocumentAction.SendUserInput("prepare_reply", "", listOf("reply"))
            }
        }
        val document = UiDocument(1, documentId, source, revision, title, nodes, actions, fallback)
        val validation = document.validate(budget, knownScenes, (data as? UiLocalDocumentData.Status)?.approval?.pendingId)
        require(validation.valid) { validation.problems.joinToString(",") }
        document
    }
}
