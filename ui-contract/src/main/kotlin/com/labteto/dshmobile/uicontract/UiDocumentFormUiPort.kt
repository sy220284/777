package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.StateFlow

data class UiDocumentFormKey(val sessionId: String, val documentId: String)
interface UiDocumentFormUiPort {
    val values: StateFlow<Map<UiDocumentFormKey, Map<String, String>>>
    fun update(key: UiDocumentFormKey, fieldId: String, value: String)
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
    return action.fieldIds.filterTo(linkedSetOf()) { id ->
        val value = values[id]
        when (val node = controls[id]) {
            is UiDocumentNode.Field -> value == null || (node.required && value.isBlank())
            is UiDocumentNode.Select -> value !in node.options
            is UiDocumentNode.Switch -> value != "true" && value != "false"
            else -> true
        }
    }
}
