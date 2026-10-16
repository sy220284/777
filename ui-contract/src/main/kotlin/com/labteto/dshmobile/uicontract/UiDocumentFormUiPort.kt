package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.StateFlow

/** Fixed contract budgets, enforced both in V6 and again by the original Owner. */
const val MAX_UI_DOCUMENT_SINGLE_LINE_CHARS = 4_096
const val MAX_UI_DOCUMENT_MULTILINE_CHARS = 16_384
const val MAX_UI_DOCUMENT_PREPARED_CHARS = 32_768

/** Revision binds form drafts to one immutable document definition. */
data class UiDocumentFormKey(val sessionId: String, val documentId: String, val revision: Long = 0) {
    init { require(sessionId.isNotBlank() && documentId.isNotBlank() && revision >= 0) }
}
interface UiDocumentFormUiPort {
    val values: StateFlow<Map<UiDocumentFormKey, Map<String, String>>>
    fun update(key: UiDocumentFormKey, fieldId: String, value: String)
    /** Explicit deletion clears all revision drafts for this document. */
    fun forgetDocument(key: UiDocumentFormKey)
}

/** Local form defaults are user data. They never enter model/system context automatically. */
fun UiDocument.initialFields(): Map<String, String> = buildMap {
    fun visit(node: UiDocumentNode) {
        when (node) {
            is UiDocumentNode.Field -> put(node.id, node.initial)
            is UiDocumentNode.Select -> put(node.id, node.initial)
            is UiDocumentNode.Switch -> put(node.id, node.initial.toString())
            is UiDocumentNode.Column -> node.children.forEach(::visit)
            is UiDocumentNode.Row -> node.children.forEach(::visit)
            is UiDocumentNode.Disclosure -> node.children.forEach(::visit)
            else -> Unit
        }
    }
    nodes.forEach(::visit)
}

/** Validate only fields consumed by this action; hidden, unrelated fields do not block navigation. */
fun UiDocument.invalidInputFields(action: UiDocumentAction.SendUserInput, values: Map<String, String>): Set<String> {
    val controls = buildMap<String, UiDocumentNode> {
        fun visit(node: UiDocumentNode) {
            when (node) {
                is UiDocumentNode.Column -> node.children.forEach(::visit)
                is UiDocumentNode.Row -> node.children.forEach(::visit)
                is UiDocumentNode.Disclosure -> node.children.forEach(::visit)
                else -> put(node.id, node)
            }
        }
        nodes.forEach(::visit)
    }
    // A structurally valid form still cannot create an unbounded outgoing Chat/Work
    // message. Count the exact prepared representation used by the Owner.
    if (preparedInput(action, values).length > MAX_UI_DOCUMENT_PREPARED_CHARS)
        return action.fieldIds.toSet() + "__total_input_budget__"
    return action.fieldIds.filterTo(linkedSetOf()) { id ->
        val value = values[id]
        when (val node = controls[id]) {
            is UiDocumentNode.Field -> value == null ||
                value.length > (if (node.multiline) MAX_UI_DOCUMENT_MULTILINE_CHARS
                    else MAX_UI_DOCUMENT_SINGLE_LINE_CHARS) ||
                (node.required && value.isBlank())
            is UiDocumentNode.Select -> value !in node.options
            is UiDocumentNode.Switch -> value != "true" && value != "false"
            else -> true
        }
    }
}

/** The confirmation preview and Owner dispatch use the same exact text. */
fun UiDocument.preparedInput(action: UiDocumentAction.SendUserInput, values: Map<String, String>): String =
    buildString {
        append(action.text)
        val fields = initialFields() + values
        action.fieldIds.forEach { id -> append('\n'); append(id); append(": "); append(fields[id].orEmpty()) }
    }
