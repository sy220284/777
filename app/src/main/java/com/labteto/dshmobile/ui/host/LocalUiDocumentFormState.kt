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
    companion object {
        private const val FORMAT_V2 = "ui-document-form-v2"
        private const val MAX_RETAINED_DOCUMENTS = 24
        private const val MAX_FIELDS_PER_DOCUMENT = 32
        private const val MAX_FIELD_CHARS = 16_384
        private const val MAX_FIELD_ID_CHARS = 160
        private const val MAX_STATE_CHARS = 65_536
    }

    /**
     * Sensitive fields may be used while the form is on screen, but must never enter
     * Android SavedState (including legacy restored payloads). The classifier uses
     * complete field-name tokens so "secretary" or "tokenizer" remain ordinary fields.
     * UI-only policy: the original Owner still governs tool authorization and submission.
     */
    private fun sensitiveField(id: String): Boolean {
        val components = id.replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase().split(Regex("[^a-z0-9]+")).filter(String::isNotBlank)
        val normalized = components.joinToString("")
        return components.any { it in setOf("password", "passwd", "pwd", "secret",
            "token", "credential", "credentials", "privatekey", "apikey") } ||
            normalized in setOf("apikey", "accesstoken", "refreshtoken",
                "clientsecret", "privatekey", "authorizationcode", "bearertoken")
    }

    private val fields = linkedMapOf<UiDocumentFormKey, Map<String, String>>()
    private val mutableValues = MutableStateFlow<Map<UiDocumentFormKey, Map<String, String>>>(emptyMap())
    override val values = mutableValues.asStateFlow()

    init {
        var scrubLegacySecrets = false
        if (saved.firstOrNull() == FORMAT_V2) {
            saved.asSequence().drop(1)
                .take(MAX_RETAINED_DOCUMENTS * MAX_FIELDS_PER_DOCUMENT * 5)
                .chunked(5).filter { it.size == 5 }.forEach { entry ->
                val revision = entry[2].toLongOrNull() ?: return@forEach
                if (revision < 0 || entry[0].isBlank() || entry[1].isBlank() || entry[3].isBlank()) return@forEach
                val key = UiDocumentFormKey(entry[0], entry[1], revision)
                if (sensitiveField(entry[3])) scrubLegacySecrets = true
                else putWithinBudget(key, entry[3], entry[4])
            }
        } else {
            saved.asSequence()
                .take(MAX_RETAINED_DOCUMENTS * MAX_FIELDS_PER_DOCUMENT * 4)
                .chunked(4).filter { it.size == 4 }.forEach { entry ->
                if (entry[0].isBlank() || entry[1].isBlank() || entry[2].isBlank()) return@forEach
                val key = UiDocumentFormKey(entry[0], entry[1])
                if (sensitiveField(entry[2])) scrubLegacySecrets = true
                else putWithinBudget(key, entry[2], entry[3])
            }
        }
        mutableValues.value = fields.toMap()
        // Rewrite legacy snapshots as soon as secrets are detected. Simply omitting
        // them from the in-memory projection would leave old plaintext in the Host
        // saved-state backing collection until a later user edit.
        if (scrubLegacySecrets) publish()
    }

    /**
     * Android saved-state is an unencrypted IPC-sized payload. Reject new state before
     * mutating revisions when the size, field or document budget would be exceeded.
     * This is not a durable vault: deletion and session disposal still purge drafts.
     */
    private fun putWithinBudget(key: UiDocumentFormKey, fieldId: String, value: String): Boolean {
        if (fieldId.isBlank() || fieldId.length > MAX_FIELD_ID_CHARS ||
            key.sessionId.length > 200 || key.documentId.length > 200 ||
            value.length > MAX_FIELD_CHARS) return false
        val older = fields.keys.filter {
            it.sessionId == key.sessionId && it.documentId == key.documentId &&
                it.revision != key.revision
        }
        // A late renderer cannot recreate stale fields after a newer revision exists.
        if (older.any { it.revision > key.revision }) return false
        val candidate = linkedMapOf<UiDocumentFormKey, Map<String, String>>()
        fields.forEach { (existing, entries) -> if (existing !in older) candidate[existing] = entries }
        val next = candidate[key].orEmpty() + (fieldId to value)
        if (next.size > MAX_FIELDS_PER_DOCUMENT) return false
        candidate[key] = next
        if (candidate.size > MAX_RETAINED_DOCUMENTS) return false
        val chars = candidate.entries.sumOf { (form, entries) ->
            entries.entries.sumOf { (id, data) ->
                form.sessionId.length.toLong() + form.documentId.length +
                    form.revision.toString().length + id.length + data.length + 5L
            }
        }
        if (chars > MAX_STATE_CHARS) return false
        fields.clear()
        fields.putAll(candidate)
        return true
    }

    override fun update(key: UiDocumentFormKey, fieldId: String, value: String) {
        if (putWithinBudget(key, fieldId, value)) publish()
    }

    override fun forgetDocument(key: UiDocumentFormKey) {
        fields.keys.filter { it.sessionId == key.sessionId && it.documentId == key.documentId }
            .forEach(fields::remove)
        publish()
    }

    fun forgetSessions(ids: Set<String>) {
        fields.keys.filter { it.sessionId in ids }.forEach(fields::remove)
        publish()
    }

    private fun publish() {
        mutableValues.value = fields.toMap()
        save(ArrayList<String>().apply {
            add(FORMAT_V2)
            fields.forEach { (form, entries) -> entries.forEach { (id, text) ->
                if (!sensitiveField(id)) {
                    add(form.sessionId); add(form.documentId); add(form.revision.toString())
                    add(id); add(text)
                }
            } }
        })
    }
}
