package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host presentation state shared by packs; drafts stay outside conversation/model history. */
internal class LocalUiDocumentFormState(saved: List<String>, private val save: (ArrayList<String>) -> Unit) : UiDocumentFormUiPort {
    private val fields = linkedMapOf<UiDocumentFormKey, Map<String, String>>()
    private val mutableValues = MutableStateFlow<Map<UiDocumentFormKey, Map<String, String>>>(emptyMap())
    override val values = mutableValues.asStateFlow()
    init {
        saved.chunked(4).filter { it.size == 4 }.forEach { entry ->
            val key = UiDocumentFormKey(entry[0], entry[1])
            fields[key] = fields[key].orEmpty() + (entry[2] to entry[3])
        }
        mutableValues.value = fields.toMap()
    }
    override fun update(key: UiDocumentFormKey, fieldId: String, value: String) {
        fields[key] = fields[key].orEmpty() + (fieldId to value)
        mutableValues.value = fields.toMap()
        save(ArrayList(fields.flatMap { (form, entries) -> entries.flatMap { (id, text) ->
            listOf(form.sessionId, form.documentId, id, text)
        } }))
    }
}
