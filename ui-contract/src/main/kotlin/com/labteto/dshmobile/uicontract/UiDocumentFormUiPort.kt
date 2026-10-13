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
