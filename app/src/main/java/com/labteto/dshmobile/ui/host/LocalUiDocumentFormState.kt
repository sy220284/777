package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiDocumentFormKey
import com.labteto.dshmobile.uicontract.UiDocumentFormUiPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Host-owned form drafts. A new document revision may change the field meaning, so
 * stale drafts are never silently carried into a new revision or across sessions.
 *
 * SavedState format v2: [marker, sessionId, documentId, revision, fieldId, value]...
 * Legacy 4-tuples are interpreted as revision 0 and migrated on the next edit.
 * This is Android saved state, NOT a durable process-death or encrypted form vault.
 */
internal class LocalUiDocumentFormState(
    saved: List<String>,
    private val save: (ArrayList<String>) -> Unit,
) : UiDocumentFormUiPort {
    companion object { private const val FORMAT_V2 = "ui-document-form-v2" }

    private val fields = linkedMapOf<UiDocumentFormKey, Map<String, String>>()
    private val mutableValues = MutableStateFlow<Map<UiDocumentFormKey, Map<String, String>>>(emptyMap())
    override val values = mutableValues.asStateFlow()

    init {
        if (saved.firstOrNull() == FORMAT_V2) {
            saved.drop(1).chunked(5).filter { it.size == 5 }.forEach { entry ->
                val revision = entry[2].toLongOrNull() ?: return@forEach
                if (revision < 0 || entry[0].isBlank() || entry[1].isBlank() || entry[3].isBlank()) return@forEach
                val key = UiDocumentFormKey(entry[0], entry[1], revision)
                fields[key] = fields[key].orEmpty() + (entry[3] to entry[4])
            }
        } else {
            saved.chunked(4).filter { it.size == 4 }.forEach { entry ->
                if (entry[0].isBlank() || entry[1].isBlank() || entry[2].isBlank()) return@forEach
                val key = UiDocumentFormKey(entry[0], entry[1])
                fields[key] = fields[key].orEmpty() + (entry[2] to entry[3])
            }
        }
        mutableValues.value = fields.toMap()
    }

    override fun update(key: UiDocumentFormKey, fieldId: String, value: String) {
        if (fieldId.isBlank()) return
        // A delayed edit from an older renderer cannot erase the newer revision's draft.
        if (fields.keys.any { it.sessionId == key.sessionId && it.documentId == key.documentId &&
                it.revision > key.revision }) return
        // A replacement revision invalidates drafts for the prior definition only.
        fields.keys.filter { it.sessionId == key.sessionId && it.documentId == key.documentId &&
            it.revision != key.revision }.forEach { fields.remove(it) }
        fields[key] = fields[key].orEmpty() + (fieldId to value)
        mutableValues.value = fields.toMap()
        save(ArrayList<String>().apply {
            add(FORMAT_V2)
            fields.forEach { (form, entries) -> entries.forEach { (id, text) ->
                add(form.sessionId); add(form.documentId); add(form.revision.toString())
                add(id); add(text)
            } }
        })
    }
}
