package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalModelReply
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalChatReplyUiDocumentsTest {
    private fun reply(text: String, request: String) = LocalModelReply(
        buildJsonObject { put("role", "assistant"); put("content", text) }, text, null, emptyList(),
        requestId = request, providerContentForReplay = JsonPrimitive(text))

    @Test fun metadataCannotReachNarrativeHistoryOrReplayAndRejectedRepairKeepsOwnCard() {
        val cards = LocalChatReplyUiDocuments()
        val initial = cards.capture(reply("Accepted\n```ui_document\n{original}\n```", "original-request"))
        cards.capture(reply("Rejected repair\n```ui_document\n{rejected}\n```", "repair-request"))
        assertEquals("Accepted", initial.content)
        assertEquals(JsonPrimitive("Accepted"), initial.message["content"])
        assertNull(initial.providerContentForReplay)
        assertEquals("{original}", cards.definitionFor(initial.content.orEmpty()))
        assertEquals("original-request", cards.requestIdFor(initial.content.orEmpty()))
        assertNull(cards.definitionFor("Different final reply"))
    }

    @Test fun identicalTextFromDifferentRepairCandidatesCannotDonateAnAmbiguousCard() {
        val cards = LocalChatReplyUiDocuments()
        cards.capture(reply("Same text\n```ui_document\n{first}\n```", "first"))
        cards.capture(reply("Same text\n```ui_document\n{rejected}\n```", "second"))
        assertNull(cards.definitionFor("Same text"))
        assertEquals("", cards.requestIdFor("Same text"))
    }

    @Test fun groupSpeakerNormalizationPreservesOnlyThatSpeakersActualCandidate() {
        val cards = LocalChatReplyUiDocuments()
        cards.capture(reply("Alice: Choose\n```ui_document\n{card}\n```", "alice-request"))
        cards.normalizeGroup("Alice")
        assertEquals("{card}", cards.definitionFor("Choose"))
        assertEquals("alice-request", cards.requestIdFor("Choose"))
        assertNull(cards.definitionFor("Bob: Choose"))
    }
}
