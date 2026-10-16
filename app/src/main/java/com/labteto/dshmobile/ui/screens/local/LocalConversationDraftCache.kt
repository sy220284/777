package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.uicontract.ConversationDraftUiPort
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val MAX_LOCAL_SESSION_PRESENTATION_STATES = 12

/** Single UI-only owner for both packs. Updates run on the UI thread, like the composer. */
internal class LocalSessionDraftCache(
    private val onSave: (List<String>) -> Unit = {},
) : ConversationDraftUiPort {
    private val values = linkedMapOf<String, String>()
    private val published = MutableStateFlow<Map<String, String>>(emptyMap())
    override val drafts = published.asStateFlow()

    private val attachmentValues = linkedMapOf<String, List<LocalImportedAttachment>>()
    private val publishedAttachments = MutableStateFlow<Map<String, List<LocalImportedAttachment>>>(emptyMap())
    override val attachments = publishedAttachments.asStateFlow()
    private var onSaveAttachments: (List<String>) -> Unit = {}

    override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) {
        setAttachments(sessionId, (attachmentValues[sessionId].orEmpty() + attachment).distinctBy { it.relativePath })
    }
    override fun removeAttachment(sessionId: String, relativePath: String) {
        setAttachments(sessionId, attachmentValues[sessionId].orEmpty().filterNot { it.relativePath == relativePath })
    }
    override fun clearAttachments(sessionId: String) = setAttachments(sessionId, emptyList())

    private fun setAttachments(sessionId: String, entries: List<LocalImportedAttachment>) {
        attachmentValues.remove(sessionId)
        if (entries.isNotEmpty()) attachmentValues[sessionId] = entries.toList()
        while (attachmentValues.size > MAX_LOCAL_SESSION_PRESENTATION_STATES) attachmentValues.remove(attachmentValues.keys.first())
        publishedAttachments.value = attachmentValues.toMap()
        onSaveAttachments(saveAttachments())
    }
    fun saveAttachments(): List<String> = attachmentValues.flatMap { (session, entries) -> listOf(session, Json.encodeToString(entries)) }

    /**
     * SavedState can outlive imported files, and malformed older state must never reintroduce
     * an absolute/traversal reference into the composer. File existence and actual permission
     * remain decisions of the Session/File Owner when the attachment is consumed.
     */
    private fun safeSavedAttachment(attachment: LocalImportedAttachment): Boolean {
        val path = attachment.relativePath
        return path.length in 1..2048 &&
            !path.startsWith('/') && !path.contains('\\') &&
            !path.contains(':') && path.none { it.code == 0 } &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
            attachment.bytes >= 0L &&
            attachment.name.isNotBlank() &&
            attachment.mediaType.isNotBlank()
    }

    fun restoreAttachments(saved: List<String>, onSave: (List<String>) -> Unit = {}) {
        attachmentValues.clear()
        saved.chunked(2).forEach { pair ->
            if (pair.size == 2) {
                val entries = runCatching { Json.decodeFromString<List<LocalImportedAttachment>>(pair[1]) }.getOrNull().orEmpty()
                val valid = entries.filter(::safeSavedAttachment).distinctBy { it.relativePath }
                if (pair[0].isNotBlank() && valid.isNotEmpty()) {
                    attachmentValues.remove(pair[0])
                    attachmentValues[pair[0]] = valid
                }
            }
        }
        while (attachmentValues.size > MAX_LOCAL_SESSION_PRESENTATION_STATES) attachmentValues.remove(attachmentValues.keys.first())
        publishedAttachments.value = attachmentValues.toMap()
        onSaveAttachments = onSave
    }

    operator fun get(sessionId: String): String? = values[sessionId]

    override fun updateDraft(sessionId: String, text: String) = putBoundedLocalDraft(sessionId, text)

    fun putBoundedLocalDraft(sessionId: String, value: String) {
        values.remove(sessionId)
        if (value.isNotBlank()) values[sessionId] = value
        while (values.size > MAX_LOCAL_SESSION_PRESENTATION_STATES) values.remove(values.keys.first())
        published.value = values.toMap()
        onSave(save())
    }

    fun save(): List<String> = values.flatMap { (sessionId, value) -> listOf(sessionId, value) }

    companion object {
        fun restore(saved: List<String>, onSave: (List<String>) -> Unit = {}): LocalSessionDraftCache =
            LocalSessionDraftCache(onSave).apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2) {
                        values.remove(pair[0])
                        if (pair[1].isNotBlank()) values[pair[0]] = pair[1]
                    }
                }
                while (values.size > MAX_LOCAL_SESSION_PRESENTATION_STATES) values.remove(values.keys.first())
                published.value = values.toMap()
            }
    }
}
