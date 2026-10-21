package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.presentation.document.LocalReplyUiDocumentProtocol

/** Per-request candidates; rejected repairs cannot donate a card to a different accepted reply. */
internal class LocalChatReplyUiDocuments {
    private data class Candidate(val definition: String?, val requestId: String, val reported: Boolean)
    private val definitions = mutableMapOf<String, Candidate>()
    private val ambiguous = mutableSetOf<String>()
    private fun register(text: String, candidate: Candidate) {
        val key = text.trim()
        val previous = definitions[key]
        if (previous != null && previous != candidate) {
            ambiguous += key
            definitions.remove(key)
        }
        if (key !in ambiguous) definitions[key] = candidate
    }
    fun capture(reply: LocalModelReply): LocalModelReply {
        if (reply.completionReason != com.labteto.dshmobile.local.model.LocalModelCompletionReason.COMPLETED) return reply
        val parts = LocalReplyUiDocumentProtocol.split(reply.content.orEmpty())
        register(parts.text, Candidate(parts.definition, reply.requestId, reply.usage.reported))
        if (parts.text == reply.content) return reply
        return ChatStyleGuard.withContent(reply, parts.text).copy(providerContentForReplay = null)
    }
    fun definitionFor(content: String): String? = definitions[content.trim()]?.definition
    fun requestIdFor(content: String): String = definitions[content.trim()]?.requestId.orEmpty()
    fun reportedFor(content: String): Boolean = definitions[content.trim()]?.reported == true
    fun normalizeGroup(vararg names: String) {
        definitions.toMap().forEach { (text, candidate) ->
            register(stripGroupSpeakerPrefix(text, *names), candidate)
        }
    }
}
