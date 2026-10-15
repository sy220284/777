package com.labteto.dshmobile.uicontract

/** Authored layout and local form values are presentation data, never task completion facts. */
data class UiDocumentSource(
    val sessionId: String,
    val messageId: String,
    val contextGeneration: Long,
    val profileId: String?,
    val projectId: String?,
    val workMode: Boolean = false,
)

data class UiDocument(
    val schemaVersion: Int,
    val documentId: String,
    val source: UiDocumentSource,
    val revision: Long,
    val title: String,
    val nodes: List<UiDocumentNode>,
    val actions: List<UiDocumentAction>,
    val fallbackText: String,
)

enum class UiDocumentBinding { EXECUTION_PHASE, QUEUED_INPUT_COUNT, PENDING_APPROVAL_TOOL, CURRENT_ERROR }
sealed interface UiDocumentBoundValue {
    data class Text(val value: String) : UiDocumentBoundValue
    data class Count(val value: Int) : UiDocumentBoundValue
    data class Phase(val value: UiExecutionPhase) : UiDocumentBoundValue
    data object Unavailable : UiDocumentBoundValue
}
enum class UiDocumentMediaKind { IMAGE, AUDIO, VIDEO }

sealed interface UiDocumentNode {
    val id: String
    data class Text(override val id: String, val text: String) : UiDocumentNode
    /** References an existing Media Owner asset; never a raw URL or local filesystem path. */
    data class Media(override val id: String, val assetId: String, val kind: UiDocumentMediaKind,
        val label: String) : UiDocumentNode
    /** Read-only presentation data. A table never authorizes tools, payment or progress claims. */
    data class Table(override val id: String, val headers: List<String>, val rows: List<List<String>>) : UiDocumentNode
    data class BoundText(override val id: String, val label: String, val binding: UiDocumentBinding) : UiDocumentNode
    data class Column(override val id: String, val children: List<UiDocumentNode>) : UiDocumentNode
    data class Row(override val id: String, val children: List<UiDocumentNode>) : UiDocumentNode
    data class Field(override val id: String, val label: String, val initial: String, val required: Boolean,
        val multiline: Boolean) : UiDocumentNode
    data class Select(override val id: String, val label: String, val options: List<String>, val initial: String) : UiDocumentNode
    data class Switch(override val id: String, val label: String, val initial: Boolean) : UiDocumentNode
    data class Button(override val id: String, val label: String, val actionId: String) : UiDocumentNode
    data class Disclosure(override val id: String, val title: String, val children: List<UiDocumentNode>) : UiDocumentNode
}

sealed interface UiDocumentAction {
    val id: String
    /** User intent only: it cannot introduce a system message, tool call or privileged input. */
    data class SendUserInput(override val id: String, val text: String, val fieldIds: List<String>) : UiDocumentAction
    data class OpenKnownScene(override val id: String, val sceneId: UiSceneId) : UiDocumentAction
    /** The pending identity must be obtained from the current Work owner at dispatch time. */
    data class AnswerApproval(override val id: String, val pendingId: String, val approved: Boolean) : UiDocumentAction
}

data class UiDocumentActionRequest(
    val documentId: String,
    val revision: Long,
    val source: UiDocumentSource,
    val actionId: String,
    val fields: Map<String, String>,
)

/** Explicit caller budget; renderers do not silently truncate a document. */
data class UiDocumentBudget(val maxNodes: Int, val maxDepth: Int, val maxTextChars: Int) {
    init { require(maxNodes > 0 && maxDepth > 0 && maxTextChars > 0) }
}

data class UiDocumentValidation(
    val problems: List<String>,
) { val valid: Boolean get() = problems.isEmpty() }

fun UiDocument.validate(
    budget: UiDocumentBudget,
    knownScenes: Set<UiSceneId>,
    pendingApprovalId: String?,
): UiDocumentValidation {
    val problems = mutableListOf<String>()
    if (schemaVersion != 1) problems += "schema_version"
    if (documentId.isBlank() || source.sessionId.isBlank() || source.messageId.isBlank() || revision < 0) problems += "identity"
    if (actions.map { it.id }.distinct().size != actions.size || actions.any { it.id.isBlank() }) problems += "action_identity"
    val actionById = actions.associateBy { it.id }
    val nodesById = mutableMapOf<String, UiDocumentNode>()
    var count = actions.size
    var textChars = title.length.toLong() + fallbackText.length
    fun walk(node: UiDocumentNode, depth: Int) {
        if (++count > budget.maxNodes) { problems += "node_budget"; return }
        if (depth > budget.maxDepth) { problems += "depth_budget"; return }
        if (node.id.isBlank() || nodesById.put(node.id, node) != null) problems += "node_identity"
        when (node) {
            is UiDocumentNode.Text -> textChars += node.text.length
            is UiDocumentNode.Media -> {
                textChars += node.label.length.toLong() + node.assetId.length
                if (node.assetId.isBlank() || node.assetId.length > 200 ||
                    node.assetId.any(Char::isISOControl) || node.label.isBlank() ||
                    node.label.length > 500) problems += "media_reference"
            }
            is UiDocumentNode.Table -> {
                val cells = node.headers.size.toLong() + node.rows.sumOf { it.size.toLong() }
                val nextCount = count.toLong() + cells
                if (nextCount > budget.maxNodes) problems += "table_budget"
                count = nextCount.coerceAtMost(budget.maxNodes.toLong() + 1).toInt()
                if (node.headers.isEmpty() || node.headers.size > 8 || node.headers.any { it.isBlank() } ||
                    node.headers.distinct().size != node.headers.size ||
                    node.rows.any { it.size != node.headers.size }) problems += "table_shape"
                textChars += node.headers.sumOf { it.length.toLong() } +
                    node.rows.sumOf { row -> row.sumOf { it.length.toLong() } }
            }
            is UiDocumentNode.BoundText -> textChars += node.label.length
            is UiDocumentNode.Column -> node.children.forEach { walk(it, depth + 1) }
            is UiDocumentNode.Row -> node.children.forEach { walk(it, depth + 1) }
            is UiDocumentNode.Disclosure -> { textChars += node.title.length; node.children.forEach { walk(it, depth + 1) } }
            is UiDocumentNode.Field -> textChars += node.label.length.toLong() + node.initial.length
            is UiDocumentNode.Select -> {
                textChars += node.label.length.toLong() + node.options.sumOf { it.length.toLong() }
                if (node.options.isEmpty() || node.options.distinct().size != node.options.size || node.initial !in node.options) problems += "select_options"
            }
            is UiDocumentNode.Switch -> textChars += node.label.length
            is UiDocumentNode.Button -> {
                textChars += node.label.length
                if (node.actionId !in actionById) problems += "button_action"
            }
        }
    }
    nodes.forEach { walk(it, 1) }
    actions.forEach { action ->
        when (action) {
            is UiDocumentAction.SendUserInput -> {
                textChars += action.text.length
                if (action.fieldIds.distinct().size != action.fieldIds.size || action.fieldIds.any {
                    nodesById[it] !is UiDocumentNode.Field && nodesById[it] !is UiDocumentNode.Select && nodesById[it] !is UiDocumentNode.Switch
                }) problems += "input_reference"
            }
            is UiDocumentAction.OpenKnownScene -> if (action.sceneId !in knownScenes) problems += "unknown_scene"
            is UiDocumentAction.AnswerApproval -> if (pendingApprovalId == null || action.pendingId != pendingApprovalId) problems += "approval_identity"
        }
    }
    if (textChars > budget.maxTextChars) problems += "text_budget"
    return UiDocumentValidation(problems.distinct())
}

/** Patches are complete validated replacements; there is no executable or arbitrary JSON patch. */
data class UiDocumentPatch(val documentId: String, val baseRevision: Long, val replacement: UiDocument)
fun UiDocumentPatch.matches(current: UiDocument): Boolean = documentId == current.documentId &&
    replacement.documentId == current.documentId && replacement.source == current.source &&
    baseRevision == current.revision && current.revision < Long.MAX_VALUE && replacement.revision == current.revision + 1
